/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.authentication;

import com.google.common.collect.ImmutableMap;
import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.time.DateUtils;
import org.sonar.api.platform.Server;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.user.UserDto;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static org.elasticsearch.common.Strings.isNullOrEmpty;

@ServerSide
public class JwtHttpHandler {

  private static final Logger LOG = Loggers.get(GenerateJwtTokenFilter.class);

  private static final String JWT_COOKIE = "JWT-SESSION";
  private static final String LAST_REFRESH_TIME_PARAM = "lastRefreshTime";

  // This timeout is used to disconnect the user we he has not browse any page for a while
  private static final int SESSION_TIMEOUT_IN_SECONDS = 3 * 24 * 60 * 60;

  // Time after which a user will be disconnected
  private static final int SESSION_DISCONNECT_IN_SECONDS = 3 * 30 * 24 * 60 * 60;

  // This refresh time is used to refresh the session
  // The value must be lower than SESSION_TIMEOUT_IN_SECONDS
  private static final int SESSION_REFRESH_IN_SECONDS = 5 * 60;

  private static final String RAILS_USER_ID_SESSION = "user_id";

  private final System2 system2;
  private final DbClient dbClient;
  private final Server server;
  private final JwtSerializer jwtSerializer;

  public JwtHttpHandler(System2 system2, DbClient dbClient, Server server, JwtSerializer jwtSerializer) {
    this.jwtSerializer = jwtSerializer;
    this.server = server;
    this.dbClient = dbClient;
    this.system2 = system2;
  }

  void generateToken(String userLogin, HttpServletResponse response) {
    String token = jwtSerializer.encode(new JwtSerializer.JwtSession(
      userLogin,
      SESSION_TIMEOUT_IN_SECONDS,
      ImmutableMap.of(LAST_REFRESH_TIME_PARAM, system2.now())));

    LOG.trace("Create session for {}", userLogin);
    response.addCookie(createCookie(JWT_COOKIE, token, SESSION_TIMEOUT_IN_SECONDS));
  }

  void validateToken(HttpServletRequest request, HttpServletResponse response) {
    Optional<Cookie> jwtCookie = findCookie(JWT_COOKIE, request);
    if (jwtCookie.isPresent()) {
      Cookie cookie = jwtCookie.get();
      String token = cookie.getValue();
      if (!isNullOrEmpty(token)) {
        validateToken(token, request, response);
      }
    }
  }

  private void validateToken(String tokenEncoded, HttpServletRequest request, HttpServletResponse response) {
    Optional<Claims> claims = jwtSerializer.decode(tokenEncoded);
    if (!claims.isPresent()) {
      removeSession(request, response);
      return;
    }

    Date now = new Date(system2.now());

    Claims token = claims.get();
    if (now.after(DateUtils.addSeconds(token.getIssuedAt(), SESSION_DISCONNECT_IN_SECONDS))) {
      removeSession(request, response);
      return;
    }

    Optional<UserDto> user = getUser(claims.get().getSubject());
    if (!user.isPresent()) {
      removeSession(request, response);
      return;
    }

    request.getSession().setAttribute(RAILS_USER_ID_SESSION, user.get().getId());
    if (now.after(DateUtils.addSeconds(getLastRefreshDate(token), SESSION_REFRESH_IN_SECONDS))) {
      refreshToken(user.get(), token, response);
    }
  }

  private static Date getLastRefreshDate(Claims token) {
    Long lastFreshTime = (Long) token.get(LAST_REFRESH_TIME_PARAM);
    requireNonNull(lastFreshTime, "last refresh time is missing in token");
    return new Date(lastFreshTime);
  }

  private void refreshToken(UserDto user, Claims token, HttpServletResponse response) {
    LOG.trace("Refresh session for {}", user.getLogin());
    String refreshToken = jwtSerializer.refresh(token, SESSION_TIMEOUT_IN_SECONDS);
    response.addCookie(createCookie(JWT_COOKIE, refreshToken, SESSION_TIMEOUT_IN_SECONDS));
  }

  private void removeSession(HttpServletRequest request, HttpServletResponse response) {
    LOG.trace("Remove session");
    request.getSession().removeAttribute(RAILS_USER_ID_SESSION);
    response.addCookie(createCookie(JWT_COOKIE, null, 0));
  }

  private Cookie createCookie(String name, @Nullable String value, int expirationInSeconds) {
    Cookie cookie = new Cookie(name, value);
    cookie.setPath(server.getContextPath() + "/");
    cookie.setSecure(server.isSecured());
    cookie.setHttpOnly(true);
    cookie.setMaxAge(expirationInSeconds);
    return cookie;
  }

  private static Optional<Cookie> findCookie(String cookieName, HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return asList(cookies).stream()
      .filter(cookie -> cookieName.equals(cookie.getName()))
      .findFirst();
  }

  private Optional<UserDto> getUser(String userLogin) {
    DbSession dbSession = dbClient.openSession(false);
    try {
      return Optional.ofNullable(dbClient.userDao().selectActiveUserByLogin(dbSession, userLogin));
    } finally {
      dbClient.closeSession(dbSession);
    }
  }
}

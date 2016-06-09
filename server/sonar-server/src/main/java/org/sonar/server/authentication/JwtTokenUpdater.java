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

import io.jsonwebtoken.Claims;
import java.math.BigInteger;
import java.security.SecureRandom;
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
import org.sonar.server.exceptions.UnauthorizedException;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;
import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;
import static org.apache.commons.lang.StringUtils.isBlank;

@ServerSide
public class JwtTokenUpdater {

  private static final Logger LOG = Loggers.get(GenerateJwtTokenFilter.class);

  private static final String JWT_COOKIE = "JWT-SESSION";

  private static final String CSRF_COOKIE = "XSRF-TOKEN";
  private static final String CSRF_JWT_PROPERTY = "xsrfToken";
  private static final String CSRF_HEADER = "x-xsrf-token";

  // This timeout is used to disconnect the user we he has not browse any page for a while
  private static final int SESSION_TIMEOUT_IN_SECONDS = 20 * 24 * 60 * 60;

  // This refresh time is used to generate a new session
  private static final int SESSION_REFRESH_IN_MINUTES = 5;

  private static final String RAILS_USER_ID_SESSION = "user_id";

  private final System2 system2;
  private final DbClient dbClient;
  private final Server server;
  private final JwtToken jwtToken;

  public JwtTokenUpdater(System2 system2, DbClient dbClient, Server server, JwtToken jwtToken) {
    this.jwtToken = jwtToken;
    this.server = server;
    this.dbClient = dbClient;
    this.system2 = system2;
  }

  void createNewJwtToken(String userLogin, HttpServletResponse response) {
    String state = new BigInteger(130, new SecureRandom()).toString(32);

    String token = jwtToken.encode(JwtToken.Jwt.builder()
      .setUserLogin(userLogin)
      .setExpirationTimeInSeconds(SESSION_TIMEOUT_IN_SECONDS)
      .addProperty(CSRF_JWT_PROPERTY, state)
      .build());

    LOG.trace("Create session for {}", userLogin);
    response.addCookie(createCookie(JWT_COOKIE, token, SESSION_TIMEOUT_IN_SECONDS, true));
    response.addCookie(createCookie(CSRF_COOKIE, sha256Hex(state), SESSION_TIMEOUT_IN_SECONDS, false));
  }

  void validateJwtToken(HttpServletRequest request, HttpServletResponse response) {
    Optional<Cookie> jwtCookie = findCookie(JWT_COOKIE, request);
    if (jwtCookie.isPresent()) {
      validateJwtToken(jwtCookie.get(), request, response);
    }
  }

  private void validateJwtToken(Cookie jwtCookie, HttpServletRequest request, HttpServletResponse response) {
    String value = jwtCookie.getValue();
    checkNotNull(value, "JWT cookie is null");
    Optional<Claims> claims = jwtToken.decode(value);
    Optional<UserDto> user = claims.isPresent() ? getUser(claims.get().getSubject()) : Optional.empty();
    if (claims.isPresent() && user.isPresent()) {
      refreshSession(claims.get(), user.get(), request, response);
    } else {
      removeSession(request, response);
    }
  }

  private void refreshSession(Claims token, UserDto user, HttpServletRequest request, HttpServletResponse response) {
    String userLogin = token.getSubject();
    LOG.trace("Validate session of {}", userLogin);
    request.getSession().setAttribute(RAILS_USER_ID_SESSION, user.getId());
    verifyState(request, (String) token.get(CSRF_JWT_PROPERTY));

    Date tokenCreationDatePlusFiveMinutes = DateUtils.addMinutes(token.getIssuedAt(), SESSION_REFRESH_IN_MINUTES);
    if (new Date(system2.now()).after(tokenCreationDatePlusFiveMinutes)) {
      LOG.trace("Create new session for {}", userLogin);
      createNewJwtToken(user.getLogin(), response);
    }
  }

  private void removeSession(HttpServletRequest request, HttpServletResponse response) {
    LOG.trace("Remove session");
    request.getSession().removeAttribute(RAILS_USER_ID_SESSION);
    response.addCookie(createCookie(JWT_COOKIE, null, 0, true));
    response.addCookie(createCookie(CSRF_COOKIE, null, 0, false));
  }

  private Cookie createCookie(String name, @Nullable String value, int expiry, boolean httpOnly) {
    Cookie cookie = new Cookie(name, value);
    cookie.setPath(server.getContextPath() + "/");
    cookie.setSecure(server.isSecured());
    cookie.setHttpOnly(httpOnly);
    cookie.setMaxAge(expiry);
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

  void verifyState(HttpServletRequest request, String state) {
    String path = request.getRequestURI().replaceFirst(request.getContextPath(), "");
    if (path.equals("/issues/bulk_change")) {
      String stateInRequest = request.getHeader(CSRF_HEADER);
      if (isBlank(stateInRequest) || !sha256Hex(state).equals(stateInRequest)) {
        throw new UnauthorizedException("Invalid CSRF");
      }
    }
  }
}

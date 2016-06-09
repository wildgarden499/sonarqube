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
import java.util.Date;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.commons.lang.time.DateUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.sonar.api.platform.Server;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserTesting;
import org.sonar.server.tester.UserSessionRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.sonar.api.utils.System2.INSTANCE;

public class JwtTokenUpdaterTest {

  static final String JWT_TOKEN = "TOKEN";
  static final String USER_LOGIN = "john";

  static final long NOW = 1_000_000_000L;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();

  @Rule
  public DbTester dbTester = DbTester.create(INSTANCE);

  DbClient dbClient = dbTester.getDbClient();

  DbSession dbSession = dbTester.getSession();

  ArgumentCaptor<Cookie> cookieArgumentCaptor = ArgumentCaptor.forClass(Cookie.class);

  HttpServletRequest request = mock(HttpServletRequest.class);
  HttpServletResponse response = mock(HttpServletResponse.class);
  HttpSession httpSession = mock(HttpSession.class);

  System2 system2 = mock(System2.class);
  Server server = mock(Server.class);
  JwtToken jwtToken = mock(JwtToken.class);

  JwtTokenUpdater underTest = new JwtTokenUpdater(system2, dbClient, server, jwtToken);

  @Before
  public void setUp() throws Exception {
    when(system2.now()).thenReturn(NOW);
    when(server.isSecured()).thenReturn(true);
    when(server.getContextPath()).thenReturn("");
    when(request.getSession()).thenReturn(httpSession);
    when(jwtToken.encode(any(JwtToken.Jwt.class))).thenReturn(JWT_TOKEN);
  }

  @Test
  public void create_session() throws Exception {
    underTest.createNewJwtToken(USER_LOGIN, response);

    Optional<Cookie> jwtCookie = findCookie("JWT-SESSION");
    assertThat(jwtCookie).isPresent();

    verifyCookie(jwtCookie.get(), JWT_TOKEN, 20 * 24 * 60 * 60);
  }

  @Test
  public void validate_session() throws Exception {
    addJwtCookie();
    UserDto user = addUser();

    Claims claims = createToken(new Date(NOW));
    when(jwtToken.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateJwtToken(request, response);

    verify(httpSession).setAttribute("user_id", user.getId());
    verify(jwtToken, never()).encode(any(JwtToken.Jwt.class));
  }

  @Test
  public void validate_refresh_session_when_refresh_time_is_reached() throws Exception {
    addJwtCookie();
    UserDto user = addUser();

    // Token was created 6 minutes ago
    Claims claims = createToken(DateUtils.addMinutes(new Date(NOW), -6));
    when(jwtToken.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateJwtToken(request, response);

    verify(httpSession).setAttribute("user_id", user.getId());
    verify(jwtToken).encode(any(JwtToken.Jwt.class));
  }

  @Test
  public void validate_does_not_refresh_session_when_refresh_time_is_not_reached() throws Exception {
    addJwtCookie();
    UserDto user = addUser();

    // Token was created 4 minutes ago
    Claims claims = createToken(DateUtils.addMinutes(new Date(NOW), -4));
    when(jwtToken.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateJwtToken(request, response);

    verify(httpSession).setAttribute("user_id", user.getId());
    verify(jwtToken, never()).encode(any(JwtToken.Jwt.class));
  }

  @Test
  public void validate_remove_session_when_user_is_disabled() throws Exception {
    addJwtCookie();
    addUser(false);

    Claims claims = createToken(new Date(NOW));
    when(jwtToken.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateJwtToken(request, response);
    verify(httpSession).removeAttribute("user_id");
    verifyCookie(findCookie("JWT-SESSION").get(), null, 0);
  }

  @Test
  public void validate_remove_session_when_token_is_no_more_valid() throws Exception {
    addJwtCookie();

    when(jwtToken.decode(JWT_TOKEN)).thenReturn(Optional.empty());

    underTest.validateJwtToken(request, response);

    verify(httpSession).removeAttribute("user_id");
    verifyCookie(findCookie("JWT-SESSION").get(), null, 0);
  }

  @Test
  public void validate_does_nothing_when_no_jwt_cookie() throws Exception {
    underTest.validateJwtToken(request, response);

    verifyZeroInteractions(httpSession, jwtToken);
  }

  private Optional<Cookie> findCookie(String name) {
    verify(response).addCookie(cookieArgumentCaptor.capture());
    return cookieArgumentCaptor.getAllValues().stream()
      .filter(cookie -> name.equals(cookie.getName()))
      .findFirst();
  }

  private void verifyCookie(Cookie cookie, @Nullable String value, int expiry) {
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getMaxAge()).isEqualTo(expiry);
    assertThat(cookie.getSecure()).isEqualTo(true);
    assertThat(cookie.getValue()).isEqualTo(value);
  }

  private UserDto addUser() {
    return addUser(true);
  }

  private UserDto addUser(boolean active) {
    UserDto user = UserTesting.newUserDto()
      .setLogin(USER_LOGIN)
      .setActive(active);
    dbClient.userDao().insert(dbSession, user);
    dbSession.commit();
    return user;
  }

  private Cookie addJwtCookie() {
    Cookie cookie = new Cookie("JWT-SESSION", JWT_TOKEN);
    when(request.getCookies()).thenReturn(new Cookie[] {cookie});
    return cookie;
  }

  private Claims createToken(Date createdAt) {
    Claims claims = mock(Claims.class);
    when(claims.getSubject()).thenReturn(USER_LOGIN);
    when(claims.getIssuedAt()).thenReturn(createdAt);
    return claims;
  }
}

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

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.sonar.server.exceptions.ForbiddenException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class ValidateJwtTokenFilterTest {

  HttpServletRequest request = mock(HttpServletRequest.class);
  HttpServletResponse response = mock(HttpServletResponse.class);
  FilterChain chain = mock(FilterChain.class);

  JwtHttpHandler jwtHttpHandler = mock(JwtHttpHandler.class);

  ValidateJwtTokenFilter underTest = new ValidateJwtTokenFilter(jwtHttpHandler);

  @Before
  public void setUp() throws Exception {
    when(request.getContextPath()).thenReturn("");
    when(request.getRequestURI()).thenReturn("/test");
  }

  @Test
  public void do_get_pattern() throws Exception {
    assertThat(underTest.doGetPattern().getUrl()).isEqualTo("/*");
  }

  @Test
  public void validate_session() throws Exception {
    underTest.doFilter(request, response, chain);

    verify(jwtHttpHandler).validateToken(request, response);
    verify(chain).doFilter(request, response);
  }

  @Test
  public void return_code_403_when_invalid_token_exception() throws Exception {
    doThrow(new ForbiddenException("invalid token")).when(jwtHttpHandler).validateToken(request, response);

    underTest.doFilter(request, response, chain);

    verify(response).setStatus(403);
    verifyZeroInteractions(chain);
  }

}

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
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SignatureException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.sonar.api.Startable;
import org.sonar.api.config.Settings;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.core.util.UuidFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This class can be used to encode or decode a JWT token
 */
@ServerSide
public class JwtToken implements Startable {

  private static final Logger LOG = Loggers.get(JwtToken.class);

  private static final String SECRET_KEY_PROPERTY = "sonar.secretKey";

  private static final SignatureAlgorithm SIGNATURE_ALGORITHM = SignatureAlgorithm.HS256;

  private final Settings settings;
  private final System2 system2;
  private final UuidFactory uuidFactory;

  private SecretKey secretKey;

  public JwtToken(Settings settings, System2 system2, UuidFactory uuidFactory) {
    this.settings = settings;
    this.system2 = system2;
    this.uuidFactory = uuidFactory;
  }

  String encode(Jwt jwt) {
    long now = system2.now();
    JwtBuilder jwtBuilder = Jwts.builder()
      .setId(uuidFactory.create())
      .setSubject(jwt.getUserLogin())
      .setIssuedAt(new Date(now))
      .setExpiration(new Date(now + jwt.getExpirationTimeInSeconds() * 1000))
      .signWith(SIGNATURE_ALGORITHM, secretKey);
    for (Map.Entry<String, Object> entry : jwt.getProperties().entrySet()) {
      jwtBuilder.claim(entry.getKey(), entry.getValue());
    }
    return jwtBuilder.compact();
  }

  Optional<Claims> decode(String token) {
    try {
      Claims claims = Jwts.parser()
        .setSigningKey(secretKey)
        .parseClaimsJws(token)
        .getBody();
      checkArgument(claims.getId() != null, "Token id hasn't been found");
      checkArgument(claims.getSubject() != null, "Token subject hasn't been found");
      checkArgument(claims.getExpiration() != null, "Token expiration date hasn't been found");
      checkArgument(claims.getIssuedAt() != null, "Token creation date hasn't been found");
      return Optional.of(claims);
    } catch (ExpiredJwtException | SignatureException e) {
      LOG.trace("Token is expired or secret key has changed", e);
      return Optional.empty();
    } catch (Exception e) {
      throw new InvalidTokenException(e);
    }
  }

  String refresh(Claims token, int expirationTimeInSeconds){
    long now = system2.now();
    JwtBuilder jwtBuilder = Jwts.builder();
    for (Map.Entry<String, Object> entry : token.entrySet()) {
      jwtBuilder.claim(entry.getKey(), entry.getValue());
    }
    jwtBuilder.setExpiration(new Date(now + expirationTimeInSeconds * 1000))
      .signWith(SIGNATURE_ALGORITHM, secretKey);
    return jwtBuilder.compact();
  }

  @Override
  public void start() {
    String encodedKey = settings.getString(SECRET_KEY_PROPERTY);
    if (encodedKey == null) {
      SecretKey newSecretKey = generateSecretKey();
      settings.setProperty(SECRET_KEY_PROPERTY, Base64.getEncoder().encodeToString(newSecretKey.getEncoded()));
      this.secretKey = newSecretKey;
    } else {
      this.secretKey = decodeSecretKey(encodedKey);
    }
  }

  private static SecretKey generateSecretKey() {
    try {
      return KeyGenerator.getInstance(SIGNATURE_ALGORITHM.getJcaName()).generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SecretKey decodeSecretKey(String encodedKey) {
    byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
    return new SecretKeySpec(decodedKey, 0, decodedKey.length, SIGNATURE_ALGORITHM.getJcaName());
  }

  @Override
  public void stop() {
    secretKey = null;
  }

  static class Jwt {

    private final String userLogin;
    private final int expirationTimeInSeconds;
    private final Map<String, Object> properties;

    Jwt(Builder builder) {
      this.userLogin = builder.userLogin;
      this.expirationTimeInSeconds = builder.expirationTimeInSeconds;
      this.properties = builder.properties;
    }

    String getUserLogin() {
      return userLogin;
    }

    int getExpirationTimeInSeconds() {
      return expirationTimeInSeconds;
    }

    Map<String, Object> getProperties() {
      return properties;
    }

    static Builder builder() {
      return new Builder();
    }

    static class Builder {
      private String userLogin;
      private int expirationTimeInSeconds = 20 * 60;
      private Map<String, Object> properties = new HashMap<>();

      Builder setUserLogin(String userLogin) {
        this.userLogin = userLogin;
        return this;
      }

      Builder setExpirationTimeInSeconds(int expirationTimeInSeconds) {
        this.expirationTimeInSeconds = expirationTimeInSeconds;
        return this;
      }

      Builder addProperty(String key, Object value) {
        this.properties.put(key, value);
        return this;
      }

      Jwt build() {
        checkNotNull(userLogin, "User login cannot be null");
        return new Jwt(this);
      }
    }
  }
}

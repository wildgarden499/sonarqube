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
package org.sonar.api.batch;

import java.util.List;
import org.sonar.api.resources.Resource;

/**
 * A pre-implementation of the CpdMapping extension point
 *
 * @since 1.10
 * @deprecated since 5.6 use {@link SensorContext#newCpdTokens()}
 */
@Deprecated
public abstract class AbstractCpdMapping implements CpdMapping {

  /**
   * {@inheritDoc}
   */
  @Override
  public Resource createResource(java.io.File file, List<java.io.File> sourceDirs) {
    throw new UnsupportedOperationException("Deprecated since 4.2");
  }
}

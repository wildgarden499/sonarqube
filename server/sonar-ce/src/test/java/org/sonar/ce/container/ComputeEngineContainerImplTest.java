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
package org.sonar.ce.container;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import org.apache.commons.dbcp.BasicDataSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.picocontainer.MutablePicoContainer;
import org.sonar.api.database.DatabaseProperties;
import org.sonar.api.utils.System2;
import org.sonar.db.DbTester;
import org.sonar.process.ProcessId;
import org.sonar.process.Props;

import static java.lang.String.valueOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.process.ProcessEntryPoint.PROPERTY_PROCESS_INDEX;
import static org.sonar.process.ProcessEntryPoint.PROPERTY_SHARED_PATH;
import static org.sonar.process.ProcessProperties.PATH_DATA;
import static org.sonar.process.ProcessProperties.PATH_HOME;
import static org.sonar.process.ProcessProperties.PATH_TEMP;
import static org.sonar.process.ProcessProperties.STARTED_AT;

public class ComputeEngineContainerImplTest {
  private static final int CONTAINER_ITSELF = 1;
  private static final int COMPONENTS_IN_LEVEL_1_AT_CONSTRUCTION = CONTAINER_ITSELF + 1;

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();
  @Rule
  public DbTester dbTester = DbTester.create(System2.INSTANCE);

  private ComputeEngineContainerImpl underTest = new ComputeEngineContainerImpl();

  @Test
  public void constructor_does_not_create_container() {
    assertThat(underTest.getComponentContainer()).isNull();
  }

  @Test
  public void real_start() throws IOException {
    Properties properties = new Properties();
    File homeDir = tempFolder.newFolder();
    File dataDir = new File(homeDir, "data");
    File tmpDir = new File(homeDir, "tmp");
    properties.setProperty(STARTED_AT, valueOf(new Date().getTime()));
    properties.setProperty(PATH_HOME, homeDir.getAbsolutePath());
    properties.setProperty(PATH_DATA, dataDir.getAbsolutePath());
    properties.setProperty(PATH_TEMP, tmpDir.getAbsolutePath());
    properties.setProperty(PROPERTY_PROCESS_INDEX, valueOf(ProcessId.COMPUTE_ENGINE.getIpcIndex()));
    properties.setProperty(PROPERTY_SHARED_PATH, tmpDir.getAbsolutePath());
    String url = ((BasicDataSource) dbTester.database().getDataSource()).getUrl();
    properties.setProperty(DatabaseProperties.PROP_URL, url);
    properties.setProperty(DatabaseProperties.PROP_USER, "sonar");
    properties.setProperty(DatabaseProperties.PROP_PASSWORD, "sonar");

    underTest
      .start(new Props(properties));

    MutablePicoContainer picoContainer = underTest.getComponentContainer().getPicoContainer();
    assertThat(picoContainer.getComponentAdapters())
      .hasSize(
        CONTAINER_ITSELF
          + 75 // level 4
          + 7 // content of CeModule
          + 7 // content of CeQueueModule
          + 4 // content of ReportProcessingModule
          + 4 // content of CeTaskProcessorModule
    );
    assertThat(picoContainer.getParent().getComponentAdapters()).hasSize(
      CONTAINER_ITSELF
        + 2 // level 3
    );
    assertThat(picoContainer.getParent().getParent().getComponentAdapters()).hasSize(
      CONTAINER_ITSELF
        + 11 // level 2
    );
    assertThat(picoContainer.getParent().getParent().getParent().getComponentAdapters()).hasSize(
      COMPONENTS_IN_LEVEL_1_AT_CONSTRUCTION
        + 22 // level 1
        + 46 // content of DaoModule
        + 1 // content of EsSearchModule
        + 57 // content of CorePropertyDefinitions
        + 1 // content of CePropertyDefinitions
    );
    assertThat(picoContainer.getParent().getParent().getParent().getParent()).isNull();
    underTest.stop();

    assertThat(picoContainer.getLifecycleState().isStarted()).isFalse();
    assertThat(picoContainer.getLifecycleState().isStopped()).isFalse();
    assertThat(picoContainer.getLifecycleState().isDisposed()).isTrue();
  }
}

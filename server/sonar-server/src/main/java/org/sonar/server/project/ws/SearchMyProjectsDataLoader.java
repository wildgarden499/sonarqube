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
package org.sonar.server.project.ws;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.stream.Collectors;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentLinkDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.measure.MeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.user.UserRoleDto;
import org.sonar.server.user.UserSession;

import static java.util.Collections.singletonList;
import static org.sonar.server.project.ws.SearchMyProjectsData.builder;

public class SearchMyProjectsDataLoader {
  private final UserSession userSession;
  private final DbClient dbClient;

  public SearchMyProjectsDataLoader(UserSession userSession, DbClient dbClient) {
    this.userSession = userSession;
    this.dbClient = dbClient;
  }

  SearchMyProjectsData load() {
    DbSession dbSession = dbClient.openSession(false);
    try {
      SearchMyProjectsData.Builder data = builder();
      List<ComponentDto> projects = searchProjects(dbSession);
      List<ComponentLinkDto> projectLinks = dbClient.componentLinkDao().selectByComponentUuids(dbSession, Lists.transform(projects, ComponentDto::uuid));
      List<SnapshotDto> snapshots = dbClient.snapshotDao().selectLastSnapshotByComponentIds(dbSession, Lists.transform(projects, ComponentDto::getId));
      MetricDto alertStatusMetric = dbClient.metricDao().selectOrFailByKey(dbSession, CoreMetrics.ALERT_STATUS_KEY);
      List<MeasureDto> qualityGates = dbClient.measureDao().selectBySnapshotIdsAndMetricIds(dbSession,
        Lists.transform(snapshots, SnapshotDto::getId),
        singletonList(alertStatusMetric.getId()));

      data.setProjects(projects)
        .setProjectLinks(projectLinks)
        .setSnapshots(snapshots)
        .setQualityGates(qualityGates);

      return data.build();
    } finally {
      dbClient.closeSession(dbSession);
    }
  }

  private List<ComponentDto> searchProjects(DbSession dbSession) {
    List<UserRoleDto> userRoles = dbClient.roleDao().selectUserPermissionsByPermissionAndUserId(dbSession, UserRole.ADMIN, userSession.getUserId());
    return dbClient.componentDao().selectByIds(dbSession, Lists.transform(userRoles, UserRoleDto::getResourceId))
      .stream()
      .filter(component -> Qualifiers.PROJECT.equals(component.qualifier()))
      .collect(Collectors.toList());
  }

}

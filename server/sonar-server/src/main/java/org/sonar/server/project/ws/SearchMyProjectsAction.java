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

import java.util.Optional;
import java.util.function.Function;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentLinkDto;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.WsProjects.SearchMyProjectsWsResponse;
import org.sonarqube.ws.WsProjects.SearchMyProjectsWsResponse.Link;
import org.sonarqube.ws.WsProjects.SearchMyProjectsWsResponse.Project;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.sonar.server.ws.WsUtils.writeProtobuf;

public class SearchMyProjectsAction implements ProjectsWsAction {
  private final DbClient dbClient;
  private final SearchMyProjectsDataLoader dataLoader;
  private final UserSession userSession;

  public SearchMyProjectsAction(DbClient dbClient, SearchMyProjectsDataLoader dataLoader, UserSession userSession) {
    this.dbClient = dbClient;
    this.dataLoader = dataLoader;
    this.userSession = userSession;
  }

  @Override
  public void define(WebService.NewController context) {
    context.createAction("search_my_projects")
      .setDescription("Return list of projects for which the current user has 'Administer' permission.")
      .setResponseExample(getClass().getResource("search_my_projects-example.json"))
      .setSince("6.0")
      .setInternal(true)
      .setHandler(this);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    SearchMyProjectsWsResponse searchMyProjectsWsResponse = doHandle();
    writeProtobuf(searchMyProjectsWsResponse, request, response);
  }

  private SearchMyProjectsWsResponse doHandle() {
    checkAuthenticated();
    DbSession dbSession = dbClient.openSession(false);
    try {
      SearchMyProjectsData data = dataLoader.load();
      return buildResponse(data);
    } finally {
      dbClient.closeSession(dbSession);
    }
  }

  private static SearchMyProjectsWsResponse buildResponse(SearchMyProjectsData data) {
    SearchMyProjectsWsResponse.Builder response = SearchMyProjectsWsResponse.newBuilder();

    data.projects().stream()
      .map(new ProjectDtoToWs(data))
      .forEach(response::addProjects);

    return response.build();
  }

  private void checkAuthenticated() {
    userSession.checkLoggedIn();
  }

  private static class ProjectDtoToWs implements Function<ComponentDto, Project> {
    private final SearchMyProjectsData data;

    private ProjectDtoToWs(SearchMyProjectsData data) {
      this.data = data;
    }

    @Override
    public Project apply(ComponentDto dto) {
      Project.Builder project = Project.newBuilder();
      project
        .setId(dto.uuid())
        .setKey(dto.key())
        .setName(dto.name());
      Optional<String> lastAnalysisDate = data.lastAnalysisDateFor(dto.getId());
      if (lastAnalysisDate.isPresent()) {
        project.setLastAnalysisDate(lastAnalysisDate.get());
      }
      Optional<String> qualityGate = data.qualityGateStatusFor(dto.getId());
      if (qualityGate.isPresent()) {
        project.setQualityGate(qualityGate.get());
      }
      if (!isNullOrEmpty(dto.description())) {
        project.setDescription(dto.description());
      }

      data.projectLinksFor(dto.uuid()).stream()
        .map(ProjectLinkDtoToWs.INSTANCE)
        .forEach(project::addLinks);

      return project.build();
    }
  }

  private enum ProjectLinkDtoToWs implements Function<ComponentLinkDto, Link> {
    INSTANCE;

    @Override
    public Link apply(ComponentLinkDto dto) {
      Link.Builder link = Link.newBuilder();
      link.setHref(dto.getHref());

      if (!isNullOrEmpty(dto.getName())) {
        link.setName(dto.getName());
      }
      if (!isNullOrEmpty(dto.getType())) {
        link.setType(dto.getType());
      }

      return link.build();
    }
  }
}

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

import com.google.common.base.Throwables;
import java.io.IOException;
import java.io.InputStream;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.measures.Metric.Level;
import org.sonar.api.measures.Metric.ValueType;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentLinkDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.user.UserDbTester;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserRoleDto;
import org.sonar.server.exceptions.UnauthorizedException;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.MediaTypes;
import org.sonarqube.ws.WsProjects.SearchMyProjectsWsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.api.measures.CoreMetrics.ALERT_STATUS_KEY;
import static org.sonar.db.component.ComponentTesting.newDeveloper;
import static org.sonar.db.component.ComponentTesting.newProjectDto;
import static org.sonar.db.component.ComponentTesting.newView;
import static org.sonar.db.component.SnapshotTesting.newSnapshotForProject;
import static org.sonar.db.measure.MeasureTesting.newMeasureDto;
import static org.sonar.db.metric.MetricTesting.newMetricDto;
import static org.sonar.db.user.UserTesting.newUserDto;
import static org.sonar.test.JsonAssert.assertJson;

public class SearchMyProjectsActionTest {
  private static final String USER_LOGIN = "TESTER";

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();
  @Rule
  public DbTester db = DbTester.create(System2.INSTANCE);
  ComponentDbTester componentDb = new ComponentDbTester(db);
  UserDbTester userDb = new UserDbTester(db);
  DbClient dbClient = db.getDbClient();
  DbSession dbSession = db.getSession();

  WsActionTester ws;

  UserDto user;
  MetricDto alertStatusMetric;

  @Before
  public void setUp() {
    user = userDb.insertUser(newUserDto().setLogin(USER_LOGIN));
    userSession.login(this.user.getLogin()).setUserId(user.getId().intValue());
    alertStatusMetric = dbClient.metricDao().insert(dbSession, newMetricDto().setKey(ALERT_STATUS_KEY).setValueType(ValueType.LEVEL.name()));
    db.commit();

    ws = new WsActionTester(new SearchMyProjectsAction(dbClient, new SearchMyProjectsDataLoader(userSession, dbClient), userSession));
  }

  @Test
  public void search_json_example() {
    // keep default TZ to reset it after the test
    TimeZone defaultTimezone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("GMT"));

    ComponentDto jdk7 = insertJdk7();
    ComponentDto cLang = insertClang();
    dbClient.componentLinkDao().insert(dbSession,
      new ComponentLinkDto().setComponentUuid(jdk7.uuid()).setHref("http://www.oracle.com").setType(ComponentLinkDto.TYPE_HOME_PAGE).setName("Home"));
    dbClient.componentLinkDao().insert(dbSession,
      new ComponentLinkDto().setComponentUuid(jdk7.uuid()).setHref("http://download.java.net/openjdk/jdk8/").setType(ComponentLinkDto.TYPE_SOURCES).setName("Sources"));
    long oneTime = DateUtils.parseDateTime("2016-06-10T13:17:53+0000").getTime();
    long anotherTime = DateUtils.parseDateTime("2016-06-11T14:25:53+0000").getTime();
    SnapshotDto jdk7Snapshot = dbClient.snapshotDao().insert(dbSession, newSnapshotForProject(jdk7).setCreatedAt(oneTime));
    SnapshotDto cLangSnapshot = dbClient.snapshotDao().insert(dbSession, newSnapshotForProject(cLang).setCreatedAt(anotherTime));
    dbClient.measureDao().insert(dbSession, newMeasureDto(alertStatusMetric, jdk7Snapshot.getId()).setData(Level.ERROR.name()));
    dbClient.measureDao().insert(dbSession, newMeasureDto(alertStatusMetric, cLangSnapshot.getId()).setData(Level.OK.name()));
    insertUserPermission(UserRole.ADMIN, user.getId(), jdk7.getId());
    insertUserPermission(UserRole.ADMIN, user.getId(), cLang.getId());
    db.commit();
    System.setProperty("user.timezone", "UTC");

    String result = ws.newRequest().execute().getInput();

    assertJson(result).isSimilarTo(getClass().getResource("search_my_projects-example.json"));

    TimeZone.setDefault(defaultTimezone);
  }

  @Test
  public void return_only_current_user_projects() {
    ComponentDto jdk7 = insertJdk7();
    ComponentDto cLang = insertClang();
    UserDto anotherUser = userDb.insertUser(newUserDto());
    insertUserPermission(UserRole.ADMIN, user.getId(), jdk7.getId());
    insertUserPermission(UserRole.ADMIN, anotherUser.getId(), cLang.getId());

    SearchMyProjectsWsResponse result = call_ws();

    assertThat(result.getProjectsCount()).isEqualTo(1);
    assertThat(result.getProjects(0).getId()).isEqualTo(jdk7.uuid());
  }

  @Test
  public void return_only_projects_when_user_is_admin() {
    ComponentDto jdk7 = insertJdk7();
    ComponentDto clang = insertClang();

    insertUserPermission(UserRole.ADMIN, user.getId(), jdk7.getId());
    insertUserPermission(UserRole.ISSUE_ADMIN, user.getId(), clang.getId());

    SearchMyProjectsWsResponse result = call_ws();

    assertThat(result.getProjectsCount()).isEqualTo(1);
    assertThat(result.getProjects(0).getId()).isEqualTo(jdk7.uuid());
  }

  @Test
  public void do_not_return_views_or_developers() {
    ComponentDto jdk7 = insertJdk7();
    ComponentDto dev = insertDeveloper();
    ComponentDto view = insertView();

    insertUserPermission(UserRole.ADMIN, user.getId(), jdk7.getId());
    insertUserPermission(UserRole.ADMIN, user.getId(), dev.getId());
    insertUserPermission(UserRole.ADMIN, user.getId(), view.getId());

    SearchMyProjectsWsResponse result = call_ws();

    assertThat(result.getProjectsCount()).isEqualTo(1);
    assertThat(result.getProjects(0).getId()).isEqualTo(jdk7.uuid());
  }

  @Test
  public void empty_response() {
    String result = ws.newRequest().execute().getInput();
    assertJson(result).isSimilarTo("{\"projects\":[]}");
  }

  @Test
  public void fail_if_not_authenticated() {
    userSession.anonymous();
    expectedException.expect(UnauthorizedException.class);

    call_ws();
  }

  private ComponentDto insertClang() {
    return componentDb.insertComponent(newProjectDto("project-uuid-2")
      .setName("Clang")
      .setKey("clang")
      .setUuid("ce4c03d6-430f-40a9-b777-ad877c00aa4d"));
  }

  private ComponentDto insertJdk7() {
    return componentDb.insertComponent(newProjectDto("project-uuid-1")
      .setName("JDK 7")
      .setKey("net.java.openjdk:jdk7")
      .setUuid("0bd7b1e7-91d6-439e-a607-4a3a9aad3c6a")
      .setDescription("JDK"));
  }

  private ComponentDto insertView() {
    return componentDb.insertComponent(newView()
      .setUuid("752d8bfd-420c-4a83-a4e5-8ab19b13c8fc")
      .setName("Java")
      .setKey("Java"));
  }

  private ComponentDto insertDeveloper() {
    return componentDb.insertComponent(newDeveloper("Joda")
      .setUuid("4e607bf9-7ed0-484a-946d-d58ba7dab2fb")
      .setKey("joda"));
  }

  private void insertUserPermission(String permission, long userId, long componentId) {
    dbClient.roleDao().insertUserRole(dbSession, new UserRoleDto()
      .setRole(permission)
      .setUserId(userId)
      .setResourceId(componentId));
    db.commit();
  }

  private SearchMyProjectsWsResponse call_ws() {
    InputStream responseStream = ws
      .newRequest()
      .setMediaType(MediaTypes.PROTOBUF)
      .execute().getInputStream();

    try {
      return SearchMyProjectsWsResponse.parseFrom(responseStream);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }
}

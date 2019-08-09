/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
package org.sonar.server.setting.ws;

import javax.annotation.Nullable;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.BranchType;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.newcodeperiod.NewCodePeriodDao;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.user.UserSession;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static org.sonar.server.component.ComponentFinder.ParamNames.PROJECT_ID_AND_KEY;

public class DeleteNewCodePeriodAction implements SettingsWsAction {
  private static final String PARAM_BRANCH = "branch";
  private static final String PARAM_PROJECT = "project";

  private final DbClient dbClient;
  private final UserSession userSession;
  private final ComponentFinder componentFinder;
  private final NewCodePeriodDao newCodePeriodDao;

  public DeleteNewCodePeriodAction(DbClient dbClient, UserSession userSession, ComponentFinder componentFinder, NewCodePeriodDao newCodePeriodDao) {
    this.dbClient = dbClient;
    this.userSession = userSession;
    this.componentFinder = componentFinder;
    this.newCodePeriodDao = newCodePeriodDao;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction("delete_new_code_period")
      .setDescription("Removes the New Code Period setting for a branch, project or global.<br>" +
        "Requires one of the following permissions: " +
        "<ul>" +
        "<li>'Administer System' to change the global setting</li>" +
        "<li>'Administer' rights for a specified component</li>" +
        "</ul>")
      .setSince("8.0")
      .setHandler(this);

    action.createParam(PARAM_PROJECT)
      .setDescription("Project key");
    action.createParam(PARAM_BRANCH)
      .setDescription("Branch key");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    String projectStr = request.getParam(PARAM_PROJECT).emptyAsNull().or(() -> null);
    String branchStr = request.getParam(PARAM_BRANCH).emptyAsNull().or(() -> null);

    if (projectStr == null && branchStr != null) {
      throw new IllegalArgumentException("If branch key is specified, project key needs to be specified too");
    }

    try (DbSession dbSession = dbClient.openSession(false)) {
      String projectUuid = null;
      String branchUuid = null;
      ComponentDto projectBranch = null;

      if (projectStr != null) {
        projectBranch = getProject(dbSession, projectStr, branchStr);
        userSession.checkComponentPermission(UserRole.ADMIN, projectBranch);
        if (branchStr != null) {
          branchUuid = projectBranch.uuid();
        }
        // depending whether it's the main branch or not
        projectUuid = projectBranch.getMainBranchProjectUuid() != null ? projectBranch.getMainBranchProjectUuid() : projectBranch.uuid();
      } else {
        userSession.checkIsSystemAdministrator();
      }

      newCodePeriodDao.delete(dbSession, projectUuid, branchUuid);
      dbSession.commit();
    }
  }

  private ComponentDto getProject(DbSession dbSession, String projectKey, @Nullable String branchKey) {
    if (branchKey == null) {
      return componentFinder.getByUuidOrKey(dbSession, null, projectKey, PROJECT_ID_AND_KEY);
    }
    ComponentDto project = componentFinder.getByKeyAndBranch(dbSession, projectKey, branchKey);

    BranchDto branchDto = dbClient.branchDao().selectByUuid(dbSession, project.uuid())
      .orElseThrow(() -> new NotFoundException(format("Branch '%s' is not found", branchKey)));

    checkArgument(branchDto.getBranchType() == BranchType.LONG,
      "Not a long-living branch: '%s'", branchKey);

    return project;
  }
}
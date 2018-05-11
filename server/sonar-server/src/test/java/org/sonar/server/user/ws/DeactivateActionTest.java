/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
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
package org.sonar.server.user.ws;

import java.util.Optional;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.AlwaysIncreasingSystem2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.permission.template.PermissionTemplateDto;
import org.sonar.db.permission.template.PermissionTemplateUserDto;
import org.sonar.db.property.PropertyDto;
import org.sonar.db.property.PropertyQuery;
import org.sonar.db.qualityprofile.QProfileDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.exceptions.UnauthorizedException;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.user.index.UserIndexDefinition;
import org.sonar.server.user.index.UserIndexer;
import org.sonar.server.ws.TestResponse;
import org.sonar.server.ws.WsActionTester;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.sonar.api.web.UserRole.CODEVIEWER;
import static org.sonar.api.web.UserRole.USER;
import static org.sonar.core.permission.GlobalPermissions.SYSTEM_ADMIN;
import static org.sonar.db.organization.OrganizationTesting.newOrganizationDto;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER_QUALITY_PROFILES;
import static org.sonar.db.permission.OrganizationPermission.SCAN;
import static org.sonar.db.property.PropertyTesting.newUserPropertyDto;
import static org.sonar.server.user.index.UserIndexDefinition.FIELD_ACTIVE;
import static org.sonar.server.user.index.UserIndexDefinition.FIELD_UUID;
import static org.sonar.test.JsonAssert.assertJson;

public class DeactivateActionTest {

  private System2 system2 = AlwaysIncreasingSystem2.INSTANCE;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public DbTester db = DbTester.create(system2);

  @Rule
  public EsTester es = EsTester.create();

  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();

  private DefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);
  private DbClient dbClient = db.getDbClient();
  private UserIndexer userIndexer = new UserIndexer(dbClient, es.client());
  private DbSession dbSession = db.getSession();

  private WsActionTester ws = new WsActionTester(new DeactivateAction(
    dbClient, userIndexer, userSession, new UserJsonWriter(userSession), defaultOrganizationProvider));

  @Test
  public void deactivate_user_and_delete_his_related_data() {
    UserDto user = insertUser(u -> u
      .setLogin("ada.lovelace")
      .setEmail("ada.lovelace@noteg.com")
      .setName("Ada Lovelace")
      .setScmAccounts(singletonList("al")));
    logInAsSystemAdministrator();

    deactivate(user.getLogin()).getInput();

    verifyThatUserIsDeactivated(user.getLogin());
    assertThat(es.client().prepareSearch(UserIndexDefinition.INDEX_TYPE_USER)
      .setQuery(boolQuery()
        .must(termQuery(FIELD_UUID, user.getUuid()))
        .must(termQuery(FIELD_ACTIVE, "false")))
      .get().getHits().getHits()).hasSize(1);
  }

  @Test
  public void deactivate_user_deletes_his_group_membership() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    GroupDto group1 = db.users().insertGroup();
    db.users().insertGroup();
    db.users().insertMember(group1, user);

    deactivate(user.getLogin()).getInput();

    assertThat(db.getDbClient().groupMembershipDao().selectGroupIdsByUserId(dbSession, user.getId())).isEmpty();
  }

  @Test
  public void deactivate_user_deletes_his_tokens() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    db.users().insertToken(user);
    db.users().insertToken(user);
    db.commit();

    deactivate(user.getLogin()).getInput();

    assertThat(db.getDbClient().userTokenDao().selectByUser(dbSession, user)).isEmpty();
  }

  @Test
  public void deactivate_user_deletes_his_properties() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    ComponentDto project = db.components().insertPrivateProject();
    db.properties().insertProperty(newUserPropertyDto(user));
    db.properties().insertProperty(newUserPropertyDto(user));
    db.properties().insertProperty(newUserPropertyDto(user).setResourceId(project.getId()));

    deactivate(user.getLogin()).getInput();

    assertThat(db.getDbClient().propertiesDao().selectByQuery(PropertyQuery.builder().setUserId(user.getId()).build(), dbSession)).isEmpty();
    assertThat(db.getDbClient().propertiesDao().selectByQuery(PropertyQuery.builder().setUserId(user.getId()).setComponentId(project.getId()).build(), dbSession)).isEmpty();
  }

  @Test
  public void deactivate_user_deletes_his_permissions() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    ComponentDto project = db.components().insertPrivateProject();
    db.users().insertPermissionOnUser(user, SCAN);
    db.users().insertPermissionOnUser(user, ADMINISTER_QUALITY_PROFILES);
    db.users().insertProjectPermissionOnUser(user, USER, project);
    db.users().insertProjectPermissionOnUser(user, CODEVIEWER, project);

    deactivate(user.getLogin()).getInput();

    assertThat(db.getDbClient().userPermissionDao().selectGlobalPermissionsOfUser(dbSession, user.getId(), db.getDefaultOrganization().getUuid())).isEmpty();
    assertThat(db.getDbClient().userPermissionDao().selectProjectPermissionsOfUser(dbSession, user.getId(), project.getId())).isEmpty();
  }

  @Test
  public void deactivate_user_deletes_his_permission_templates() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    PermissionTemplateDto template = db.permissionTemplates().insertTemplate();
    PermissionTemplateDto anotherTemplate = db.permissionTemplates().insertTemplate();
    db.permissionTemplates().addUserToTemplate(template.getId(), user.getId(), USER);
    db.permissionTemplates().addUserToTemplate(anotherTemplate.getId(), user.getId(), CODEVIEWER);

    deactivate(user.getLogin()).getInput();

    assertThat(db.getDbClient().permissionTemplateDao().selectUserPermissionsByTemplateId(dbSession, template.getId())).extracting(PermissionTemplateUserDto::getUserId).isEmpty();
    assertThat(db.getDbClient().permissionTemplateDao().selectUserPermissionsByTemplateId(dbSession, anotherTemplate.getId())).extracting(PermissionTemplateUserDto::getUserId)
      .isEmpty();
  }

  @Test
  public void deactivate_user_deletes_his_qprofiles_permissions() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    QProfileDto profile = db.qualityProfiles().insert(db.getDefaultOrganization());
    db.qualityProfiles().addUserPermission(profile, user);

    deactivate(user.getLogin()).getInput();

    assertThat(db.getDbClient().qProfileEditUsersDao().exists(dbSession, profile, user)).isFalse();
  }

  @Test
  public void deactivate_user_deletes_his_default_assignee_settings() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto anotherProject = db.components().insertPrivateProject();
    db.properties().insertProperty(new PropertyDto().setKey("sonar.issues.defaultAssigneeLogin").setValue(user.getLogin()).setResourceId(project.getId()));
    db.properties().insertProperty(new PropertyDto().setKey("sonar.issues.defaultAssigneeLogin").setValue(user.getLogin()).setResourceId(anotherProject.getId()));
    db.properties().insertProperty(new PropertyDto().setKey("other").setValue(user.getLogin()).setResourceId(anotherProject.getId()));

    deactivate(user.getLogin()).getInput();

    assertThat(db.getDbClient().propertiesDao().selectByQuery(PropertyQuery.builder().setKey("sonar.issues.defaultAssigneeLogin").build(), db.getSession())).isEmpty();
    assertThat(db.getDbClient().propertiesDao().selectByQuery(PropertyQuery.builder().build(), db.getSession())).extracting(PropertyDto::getKey).containsOnly("other");
  }

  @Test
  public void deactivate_user_deletes_his_organization_membership() {
    logInAsSystemAdministrator();
    UserDto user = insertUser();
    OrganizationDto organization = db.organizations().insert();
    db.organizations().addMember(organization, user);
    OrganizationDto anotherOrganization = db.organizations().insert();
    db.organizations().addMember(anotherOrganization, user);

    deactivate(user.getLogin()).getInput();

    assertThat(dbClient.organizationMemberDao().select(db.getSession(), organization.getUuid(), user.getId())).isNotPresent();
    assertThat(dbClient.organizationMemberDao().select(db.getSession(), anotherOrganization.getUuid(), user.getId())).isNotPresent();
  }

  @Test
  public void cannot_deactivate_self() {
    UserDto user = createUser();
    userSession.logIn(user.getLogin()).setSystemAdministrator();

    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("Self-deactivation is not possible");

    deactivate(user.getLogin());

    verifyThatUserExists(user.getLogin());
  }

  @Test
  public void deactivation_requires_to_be_logged_in() {
    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("Authentication is required");

    deactivate("someone");
  }

  @Test
  public void deactivation_requires_administrator_permission() {
    userSession.logIn();

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("Insufficient privileges");

    deactivate("someone");
  }

  @Test
  public void fail_if_user_does_not_exist() {
    logInAsSystemAdministrator();

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("User 'someone' doesn't exist");

    deactivate("someone");
  }

  @Test
  public void fail_if_login_is_blank() {
    logInAsSystemAdministrator();

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("User '' doesn't exist");

    deactivate("");
  }

  @Test
  public void fail_to_deactivate_last_administrator_of_default_organization() {
    UserDto admin = createUser();
    db.users().insertPermissionOnUser(admin, ADMINISTER);
    logInAsSystemAdministrator();

    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("User is last administrator, and cannot be deactivated");

    deactivate(admin.getLogin());
  }

  @Test
  public void fail_to_deactivate_last_administrator_of_organization() {
    // user1 is the unique administrator of org1 and org2.
    // user1 and user2 are both administrators of org3
    UserDto user1 = insertUser(u -> u.setLogin("test"));
    OrganizationDto org1 = db.organizations().insert(newOrganizationDto().setKey("org1"));
    OrganizationDto org2 = db.organizations().insert(newOrganizationDto().setKey("org2"));
    OrganizationDto org3 = db.organizations().insert(newOrganizationDto().setKey("org3"));
    db.users().insertPermissionOnUser(org1, user1, SYSTEM_ADMIN);
    db.users().insertPermissionOnUser(org2, user1, SYSTEM_ADMIN);
    db.users().insertPermissionOnUser(org3, user1, SYSTEM_ADMIN);
    UserDto user2 = createUser();
    db.users().insertPermissionOnUser(org3, user2, SYSTEM_ADMIN);
    logInAsSystemAdministrator();

    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("User 'test' is last administrator of organizations [org1, org2], and cannot be deactivated");

    deactivate(user1.getLogin());
  }

  @Test
  public void administrators_can_be_deactivated_if_there_are_still_other_administrators() {
    UserDto admin = createUser();
    UserDto anotherAdmin = createUser();
    db.users().insertPermissionOnUser(admin, ADMINISTER);
    db.users().insertPermissionOnUser(anotherAdmin, ADMINISTER);
    db.commit();
    logInAsSystemAdministrator();

    deactivate(admin.getLogin());

    verifyThatUserIsDeactivated(admin.getLogin());
    verifyThatUserExists(anotherAdmin.getLogin());
  }

  @Test
  public void test_definition() {
    assertThat(ws.getDef().isPost()).isTrue();
    assertThat(ws.getDef().isInternal()).isFalse();
    assertThat(ws.getDef().params()).hasSize(1);
  }

  @Test
  public void test_example() {
    UserDto user = insertUser(u -> u
      .setLogin("ada.lovelace")
      .setEmail("ada.lovelace@noteg.com")
      .setName("Ada Lovelace")
      .setLocal(true)
      .setScmAccounts(singletonList("al")));
    logInAsSystemAdministrator();

    String json = deactivate(user.getLogin()).getInput();

    assertJson(json).isSimilarTo(ws.getDef().responseExampleAsString());
  }

  private UserDto createUser() {
    return insertUser();
  }

  @SafeVarargs
  private final UserDto insertUser(Consumer<UserDto>... populators) {
    UserDto user = db.users().insertUser(populators);
    db.users().insertToken(user);
    db.properties().insertProperties(new PropertyDto().setUserId(user.getId()).setKey("foo").setValue("bar"));
    userIndexer.commitAndIndex(dbSession, user);
    return user;
  }

  private void logInAsSystemAdministrator() {
    userSession.logIn().setSystemAdministrator();
  }

  private TestResponse deactivate(String login) {
    return ws.newRequest()
      .setMethod("POST")
      .setParam("login", login)
      .execute();
  }

  private void verifyThatUserExists(String login) {
    assertThat(db.users().selectUserByLogin(login)).isPresent();
  }

  private void verifyThatUserIsDeactivated(String login) {
    Optional<UserDto> user = db.users().selectUserByLogin(login);
    assertThat(user).isPresent();
    assertThat(user.get().isActive()).isFalse();
    assertThat(user.get().getEmail()).isNull();
    assertThat(user.get().getScmAccountsAsList()).isEmpty();
  }
}

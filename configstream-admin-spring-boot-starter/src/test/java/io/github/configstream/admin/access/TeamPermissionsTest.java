package io.github.configstream.admin.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.configstream.admin.registry.ServiceSummary;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeamPermissionsTest {

    private final TeamPermissions permissions = new TeamPermissions(List.of("config-admins"));

    private static final ServiceSummary ORDERS = new ServiceSummary("orders", "team-a", List.of());
    private static final ServiceSummary NO_TEAM = new ServiceSummary("legacy", null, List.of());

    @Test
    void aTeamMemberCanDoEverythingWithTheTeamsServices() {
        AdminUser alice = user("alice", "team-a");

        assertThat(permissions.canView(alice, ORDERS)).isTrue();
        assertThat(permissions.canEdit(alice, ORDERS)).isTrue();
        assertThat(permissions.canDelete(alice, ORDERS)).isTrue();
    }

    @Test
    void othersCantSeeIt() {
        AdminUser bob = user("bob", "team-b");

        assertThat(permissions.canView(bob, ORDERS)).isFalse();
        assertThat(permissions.canEdit(bob, ORDERS)).isFalse();
    }

    @Test
    void adminGroupsCanDoEverything() {
        AdminUser dave = user("dave", "config-admins");

        assertThat(permissions.canEdit(dave, ORDERS)).isTrue();
        assertThat(permissions.canEdit(dave, NO_TEAM)).isTrue();
    }

    @Test
    void aServiceWithoutATeamIsOnlyForAdminGroups() {
        assertThat(permissions.canView(user("alice", "team-a"), NO_TEAM)).isFalse();
    }

    @Test
    void companiesCanReplaceTheRules() {
        // E.g. testers may look but not change anything
        ConfigStreamAdminPermissions readOnlyTesters = new ConfigStreamAdminPermissions() {
            @Override
            public boolean canView(AdminUser user, ServiceSummary service) {
                return permissions.canView(user, service) || user.isInGroup("testers");
            }

            @Override
            public boolean canEdit(AdminUser user, ServiceSummary service) {
                return permissions.canEdit(user, service);
            }
        };
        AdminUser tina = user("tina", "testers");

        assertThat(readOnlyTesters.canView(tina, ORDERS)).isTrue();
        assertThat(readOnlyTesters.canEdit(tina, ORDERS)).isFalse();
        assertThat(readOnlyTesters.canDelete(tina, ORDERS)).as("defaults to canEdit").isFalse();
    }

    private static AdminUser user(String name, String... groups) {
        Set<String> memberOf = Set.of(groups);
        return new AdminUser() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isInGroup(String group) {
                return memberOf.contains(group);
            }
        };
    }
}

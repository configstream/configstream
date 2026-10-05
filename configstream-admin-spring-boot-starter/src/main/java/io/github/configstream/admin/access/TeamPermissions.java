package io.github.configstream.admin.access;

import io.github.configstream.admin.registry.ServiceSummary;
import java.util.List;

/**
 * The default permissions: a person can view and change a service when one of their login groups is the service's
 * team ({@code configstream.team} on the service), or one of the admin groups
 * ({@code configstream.admin-server.admin-groups}), which can do everything. A service that declares no team is only
 * open to admin groups.
 */
public class TeamPermissions implements ConfigStreamAdminPermissions {

    private final List<String> adminGroups;

    public TeamPermissions(List<String> adminGroups) {
        this.adminGroups = List.copyOf(adminGroups);
    }

    @Override
    public boolean canView(AdminUser user, ServiceSummary service) {
        if (adminGroups.stream().anyMatch(user::isInGroup)) {
            return true;
        }
        String team = service.team();
        return team != null && !team.isBlank() && user.isInGroup(team);
    }
}

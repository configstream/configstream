package io.github.configstream.admin.web;

import io.github.configstream.admin.access.AdminUser;
import io.github.configstream.admin.access.ConfigStreamAdminPermissions;
import io.github.configstream.admin.registry.ServiceSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Applies {@link ConfigStreamAdminPermissions} to the person making the current request. Without a login there is no
 * person to ask about, so everything is allowed, as the header's warning says.
 */
class AdminAccess {

    enum Action { VIEW, EDIT, DELETE }

    private static final boolean SPRING_SECURITY = ClassUtils.isPresent(
            "org.springframework.security.core.context.SecurityContextHolder", AdminAccess.class.getClassLoader());

    private final ConfigStreamAdminPermissions permissions;

    AdminAccess(ConfigStreamAdminPermissions permissions) {
        this.permissions = permissions;
    }

    boolean allows(Action action, ServiceSummary service) {
        AdminUser user = currentUser();
        if (user == null) {
            return true;
        }
        return switch (action) {
            case VIEW -> permissions.canView(user, service);
            case EDIT -> permissions.canEdit(user, service);
            case DELETE -> permissions.canDelete(user, service);
        };
    }

    /** The signed-in person, or {@code null} if the host application has no login. */
    private static AdminUser currentUser() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            return null;
        }
        String name = principal.getName();
        return new AdminUser() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isInGroup(String group) {
                return request.isUserInRole(group) || (SPRING_SECURITY && SpringAuthorities.has(group));
            }
        };
    }
}

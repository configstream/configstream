package io.github.configstream.admin.access;

import io.github.configstream.admin.registry.ServiceSummary;

/**
 * Decides what a signed-in person may do with a service in the admin server. Asked before a service is listed or
 * shown, and again on the server before any change, so hiding a button is never the only protection.
 *
 * <p>Define a bean of this type to replace the default, {@link TeamPermissions}. It is only consulted when the host
 * application has a login; without one, everyone can do everything (and the header says so).
 */
public interface ConfigStreamAdminPermissions {

    /** Whether the service appears on the dashboard and its page, values and history can be opened. */
    boolean canView(AdminUser user, ServiceSummary service);

    /** Whether the person can change the service's values. Defaults to {@link #canView}. */
    default boolean canEdit(AdminUser user, ServiceSummary service) {
        return canView(user, service);
    }

    /** Whether the person can delete the service's orphaned properties. Defaults to {@link #canEdit}. */
    default boolean canDelete(AdminUser user, ServiceSummary service) {
        return canEdit(user, service);
    }
}

package io.github.configstream.admin.web;

import java.security.Principal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Gives the dashboard templates the path prefix for their links ({@code configStreamAdminBase}), the signed-in user
 * if the host application has a login ({@code configStreamAdminUser}), and where to sign out
 * ({@code configStreamAdminLogout}). A top-level class, not nested in {@link ConfigStreamAdminWebConfiguration}, which
 * would register it a second time.
 */
@ControllerAdvice(assignableTypes = {DashboardController.class, ConfigEditController.class})
class DashboardModel {

    private final String basePath;
    private final String logoutPath;

    DashboardModel(String basePath, String logoutPath) {
        this.basePath = basePath;
        this.logoutPath = logoutPath;
    }

    @ModelAttribute("configStreamAdminBase")
    String basePath() {
        return basePath;
    }

    /** The signed-in user's name, or {@code null} when the host application has no login. */
    @ModelAttribute("configStreamAdminUser")
    String user(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    @ModelAttribute("configStreamAdminLogout")
    String logoutPath() {
        return logoutPath;
    }
}

package io.github.configstream.admin.web;

import io.github.configstream.admin.ConfigStreamAdminProperties;
import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.admin.registry.InstanceRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The dashboard, served under {@code configstream.admin-server.dashboard.path} (default {@code /}) so it can sit
 * beside the host app's own pages. Templates live under {@code templates/configstream-admin/} and static
 * files under {@code /configstream-admin/}, clear of the host app's.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigStreamAdminWebConfiguration implements WebMvcConfigurer {

    private final String basePath;
    private final String logoutPath;

    ConfigStreamAdminWebConfiguration(ConfigStreamAdminProperties properties) {
        this.basePath = properties.getDashboard().basePath();
        this.logoutPath = properties.getDashboard().getLogoutPath();
    }

    /** Used by the templates as {@code ${@configStreamAdminTime.ago(instant)}}. */
    @Bean
    TimeFormat configStreamAdminTime() {
        return new TimeFormat(Clock.systemUTC());
    }

    @Bean
    DashboardController configStreamAdminDashboardController(InstanceRegistry registry, ServiceClient client) {
        return new DashboardController(registry, client);
    }

    @Bean
    ConfigEditController configStreamAdminConfigEditController(InstanceRegistry registry, ServiceClient client) {
        return new ConfigEditController(registry, client, basePath);
    }

    @Bean
    DashboardModel configStreamAdminDashboardModel() {
        return new DashboardModel(basePath, logoutPath);
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        if (!basePath.isEmpty()) {
            configurer.addPathPrefix(basePath,
                    HandlerTypePredicate.forAssignableType(DashboardController.class, ConfigEditController.class));
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (!basePath.isEmpty()) {
            registry.addRedirectViewController(basePath, basePath + "/");
        }
    }
}

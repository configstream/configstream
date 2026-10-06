package io.github.configstream.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.admin.registry.InstanceRegistration;
import io.github.configstream.admin.registry.InstanceRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConfigStreamAdminServerAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigStreamAdminServerAutoConfiguration.class));

    @Test
    void theDependencyAloneActivatesNothing() {
        runner.withUserConfiguration(PlainApp.class).run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(InstanceRegistry.class)
                .doesNotHaveBean(ServiceClient.class)
                .doesNotHaveBean("configStreamAdminDashboardController"));
    }

    @Test
    void theAnnotationTurnsTheAppIntoTheAdminServer() {
        runner.withUserConfiguration(AdminApp.class).run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(InstanceRegistry.class)
                .hasSingleBean(ServiceClient.class)
                .hasSingleBean(ConfigStreamAdminProperties.class)
                .hasBean("configStreamAdminRegistrationController")
                .hasBean("configStreamAdminDashboardController")
                .hasBean("configStreamAdminConfigEditController")
                .hasBean("configStreamAdminTime"));
    }

    @Test
    void offInNonWebApps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigStreamAdminServerAutoConfiguration.class))
                .withUserConfiguration(AdminApp.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(InstanceRegistry.class));
    }

    @Test
    void callsServicesWithTheAdminServersTokenWhenConfigured() throws IOException {
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer tokens = server("/oauth2/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(form).contains("grant_type=client_credentials");
            respond(exchange, "{\"access_token\":\"token-for-admin\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        HttpServer service = server("/internal/config", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{}");
        });
        try {
            runner.withUserConfiguration(AdminApp.class)
                    .withBean(ClientRegistrationRepository.class, () -> new InMemoryClientRegistrationRepository(
                            ClientRegistration.withRegistrationId("configstream")
                                    .clientId("configstream-admin")
                                    .clientSecret("admin-client-secret")
                                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                                    .tokenUri("http://localhost:" + tokens.getAddress().getPort() + "/oauth2/token")
                                    .build()))
                    .withPropertyValues("configstream.admin-server.oauth2-client=configstream")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        context.getBean(InstanceRegistry.class).register(new InstanceRegistration("orders", "o-1",
                                "localhost", service.getAddress().getPort(), null, null));
                        ServiceClient client = context.getBean(ServiceClient.class);

                        // No secret is configured for orders: the token alone is enough
                        assertThat(client.currentConfig("orders")).isEmpty();
                        assertThat(client.currentConfig("orders")).isEmpty();
                        assertThat(authorizations).containsExactly("Bearer token-for-admin", "Bearer token-for-admin");
                    });
        } finally {
            tokens.stop(0);
            service.stop(0);
        }
    }

    @Test
    void failsClearlyWhenTheTokenRegistrationIsMissing() {
        runner.withUserConfiguration(AdminApp.class)
                .withPropertyValues("configstream.admin-server.oauth2-client=configstream")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("there is no spring.security.oauth2.client.registration.configstream"));
    }

    private static HttpServer server(String path, HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void dashboardPathIsNormalisedToALinkPrefix() {
        ConfigStreamAdminProperties.Dashboard dashboard = new ConfigStreamAdminProperties().getDashboard();
        assertThat(dashboard.basePath()).as("default").isEmpty();
        for (String path : new String[] {"/admin", "/admin/", "admin", " /admin// "}) {
            dashboard.setPath(path);
            assertThat(dashboard.basePath()).as(path).isEqualTo("/admin");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PlainApp {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigStreamAdminServer
    static class AdminApp {
    }
}

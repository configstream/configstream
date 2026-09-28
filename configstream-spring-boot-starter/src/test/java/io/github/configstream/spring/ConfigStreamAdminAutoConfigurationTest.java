package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConfigStreamAdminAutoConfigurationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private FakeAdmin admin;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigStreamAutoConfiguration.class, ConfigStreamAdminAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(ConfigStreamAutoConfigurationTest.FakeSourceConfig.class);

    @BeforeEach
    void startAdmin() throws IOException {
        admin = new FakeAdmin();
    }

    @AfterEach
    void stopAdmin() {
        admin.close();
    }

    private ApplicationContextRunner withAdmin() {
        return runner.withPropertyValues(
                "configstream.admin.url=" + admin.url(),
                "configstream.admin.heartbeat-interval=100ms",
                "configstream.team=team-a",
                "configstream.instance.id=orders-1",
                "configstream.instance.host=10.0.0.5",
                "configstream.instance.port=8080",
                "spring.application.name=orders");
    }

    @Test
    void offUnlessAdminUrlIsSet() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AdminRegistration.class));
    }

    @Test
    void registersThenSendsHeartbeatsThenDeregistersOnShutdown() {
        withAdmin().run(context -> {
            await().atMost(WAIT).until(() -> admin.count("PUT /api/instances/orders-1/heartbeat") >= 2);

            assertThat(admin.requests.get(0)).isEqualTo("POST /api/instances");
            assertThat(admin.registrationBodies.get(0))
                    .contains("\"serviceName\":\"orders\"", "\"instanceId\":\"orders-1\"",
                            "\"host\":\"10.0.0.5\"", "\"port\":8080", "\"team\":\"team-a\"");
        });

        assertThat(admin.requests).last().isEqualTo("DELETE /api/instances/orders-1");
    }

    @Test
    void registersAgainWhenAdminForgetsTheInstance() {
        withAdmin().run(context -> {
            await().atMost(WAIT).until(() -> admin.count("PUT /api/instances/orders-1/heartbeat") >= 1);
            admin.forgetInstances();

            await().atMost(WAIT).until(() -> admin.count("POST /api/instances") >= 2);
        });
    }

    @Test
    void appStartsWithAdminDownAndRegistersOnceItComesUp() {
        admin.failWith(503);
        withAdmin().run(context -> {
            assertThat(context).hasNotFailed();
            AdminRegistration registration = context.getBean(AdminRegistration.class);
            await().atMost(WAIT).until(() -> admin.count("POST /api/instances") >= 2);
            assertThat(registration.isRegistered()).isFalse();

            admin.failWith(0);
            await().atMost(WAIT).until(registration::isRegistered);
        });
    }

    @Test
    void appStartsWhenAdminIsUnreachable() {
        admin.close();
        withAdmin().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ConfigService.class).values()).containsKey("a.key");
        });
    }

    /** Minimal stand-in for the admin server API, recording every request it receives. */
    static class FakeAdmin implements AutoCloseable {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> registrationBodies = new CopyOnWriteArrayList<>();
        private final AtomicInteger failStatus = new AtomicInteger();
        private volatile boolean forgotten;
        private final HttpServer server;

        FakeAdmin() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/instances", this::handle);
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        long count(String request) {
            return requests.stream().filter(request::equals).count();
        }

        void failWith(int status) {
            failStatus.set(status);
        }

        void forgetInstances() {
            forgotten = true;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            requests.add(method + " " + exchange.getRequestURI().getPath());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status;
            if (failStatus.get() != 0) {
                status = failStatus.get();
            } else if (method.equals("POST")) {
                registrationBodies.add(body);
                forgotten = false;
                status = 201;
            } else if (method.equals("PUT") && forgotten) {
                status = 404;
            } else {
                status = 204;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

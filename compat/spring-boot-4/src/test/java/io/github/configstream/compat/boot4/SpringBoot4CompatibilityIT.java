package io.github.configstream.compat.boot4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.configstream.compat.boot4.admin.AdminApplication;
import io.github.configstream.compat.boot4.orders.OrdersApplication;
import io.github.configstream.testsupport.TestMongo;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The starters, built against Spring Boot 3, in Spring Boot 4 applications: two instances of an orders service that
 * connect through Boot 4's {@code spring.mongodb.*} settings, register with a Boot 4 admin server, take a change made in
 * the admin UI, and report their health through Boot 4's Actuator.
 */
class SpringBoot4CompatibilityIT {

    private static final String SECRET = "0123456789abcdef-boot4-secret";
    private static final String COLLECTION = "orders_config_" + UUID.randomUUID().toString().substring(0, 8);
    private static final Duration PROPAGATION = Duration.ofSeconds(5);

    static final HttpClient http = HttpClient.newHttpClient();
    static ConfigurableApplicationContext admin;
    static ConfigurableApplicationContext first;
    static ConfigurableApplicationContext second;
    static String adminUrl;

    @BeforeAll
    static void start() {
        admin = new SpringApplicationBuilder(AdminApplication.class).run(
                "--server.port=0",
                "--configstream.enabled=false",
                // The admin server needs no database; MongoDB is on this module's classpath only for the services
                "--spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
                        + "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration,"
                        + "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration",
                "--configstream.admin-server.default-service-secret=" + SECRET);
        adminUrl = urlOf(admin);
        first = startOrders();
        second = startOrders();
    }

    @AfterAll
    static void stop() {
        for (ConfigurableApplicationContext context : new ConfigurableApplicationContext[] {second, first, admin}) {
            if (context != null) {
                context.close();
            }
        }
    }

    @Test
    void runsOnSpringBoot4() {
        assertThat(SpringBootVersion.getVersion()).startsWith("4.");
    }

    @Test
    void createsTheManifestsPropertiesInTheDatabaseFromSpringMongodbSettings() throws Exception {
        assertThat(get(urlOf(first) + "/demo")).contains("\"limits.max\":", "\"feature.x.enabled\":");
        try (var mongo = TestMongo.client()) {
            assertThat(mongo.getDatabase(TestMongo.database()).getCollection(COLLECTION)
                    .countDocuments()).isEqualTo(2);
        }
    }

    @Test
    void aChangeMadeInTheAdminUiReachesEveryInstance() throws Exception {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(get(adminUrl + "/services/orders")).contains("2 active instances"));

        assertThat(postForm("/services/orders/edit/review",
                Map.of("key", "limits.max", "value", "150", "changedBy", "alice", "comment", "Black Friday")).body())
                .contains("Review change", ">150<");
        HttpResponse<String> applied = postForm("/services/orders/update",
                Map.of("key", "limits.max", "value", "150", "changedBy", "alice", "comment", "Black Friday"));
        assertThat(applied.statusCode()).isEqualTo(302);

        await().atMost(PROPAGATION).untilAsserted(() -> {
            for (ConfigurableApplicationContext instance : new ConfigurableApplicationContext[] {first, second}) {
                assertThat(get(urlOf(instance) + "/demo")).contains("\"limits.max\":150");
                assertThat(get(urlOf(instance) + "/demo/changes")).contains("limits.max: 100 -> 150");
            }
        });
        assertThat(get(adminUrl + "/services/orders/history?key=limits.max"))
                .contains("v2", "alice", "Black Friday", "Created from configstream.yml");
    }

    @Test
    void reportsItsHealthThroughSpringBoot4sActuator() throws Exception {
        assertThat(get(urlOf(first) + "/actuator/health"))
                .contains("\"configstream\":{", "\"status\":\"UP\"", "\"connected\":true");
    }

    private static ConfigurableApplicationContext startOrders() {
        return new SpringApplicationBuilder(OrdersApplication.class).run(
                "--server.port=0",
                "--spring.application.name=orders",
                // Spring Boot 4's names for the application's MongoDB settings, which configstream follows
                "--spring.mongodb.uri=" + TestMongo.uri(),
                "--spring.mongodb.database=" + TestMongo.database(),
                "--configstream.mongo.config-collection=" + COLLECTION,
                "--configstream.team=team-a",
                "--configstream.internal.secret=" + SECRET,
                "--configstream.admin.url=" + adminUrl,
                "--configstream.admin.heartbeat-interval=200ms",
                "--configstream.instance.host=localhost",
                "--management.endpoint.health.show-details=always");
    }

    private static String urlOf(ConfigurableApplicationContext context) {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url).isEqualTo(200);
        return response.body();
    }

    /** Submits a form to the admin UI the way a browser would (redirects are not followed). */
    private static HttpResponse<String> postForm(String path, Map<String, String> fields)
            throws IOException, InterruptedException {
        String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

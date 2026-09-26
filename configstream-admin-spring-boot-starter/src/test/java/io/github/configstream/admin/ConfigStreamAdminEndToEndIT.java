package io.github.configstream.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.github.configstream.adminhost.AdminHostApplication;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.demoservice.DemoServiceApplication;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real configstream services registering with a real configstream-admin, against a real MongoDB: the admin
 * UI shows their live instances, config and history, and changes made in the UI reach every instance.
 */
@Testcontainers
class ConfigStreamAdminEndToEndIT {

    private static final String SECRET = "0123456789abcdef-e2e-secret";
    private static final String NO_MONGO_AUTOCONFIG =
            "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    static final HttpClient http = HttpClient.newHttpClient();
    static ConfigurableApplicationContext admin;
    static String adminUrl;

    @BeforeAll
    static void startAdmin() {
        admin = new SpringApplicationBuilder(AdminHostApplication.class).run(
                "--server.port=0",
                "--configstream.enabled=false",
                NO_MONGO_AUTOCONFIG,
                "--server.servlet.session.tracking-modes=cookie", // as the README recommends for admin hosts
                "--configstream.admin-server.default-service-secret=" + SECRET);
        adminUrl = "http://localhost:" + admin.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stopAdmin() {
        admin.close();
    }

    @Test
    void serviceRegistersAndItsConfigAndHistoryShowUpInTheAdminUi() throws Exception {
        try (MongoClient mongo = MongoClients.create(MONGO.getReplicaSetUrl())) {
            mongo.getDatabase("orders").getCollection("config")
                    .insertOne(new Document("_id", "feature.x.enabled").append("value", "true"));
        }
        InstanceRegistry registry = admin.getBean(InstanceRegistry.class);

        try (ConfigurableApplicationContext service = startService()) {
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> registry.service("orders").map(s -> s.activeCount() == 1).orElse(false));
            String serviceUrl = "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
            updateThroughService(serviceUrl, "limits.max", "50");

            assertThat(getHtml(adminUrl + "/")).contains("href=\"/services/orders\"", "team-a", "1 active instance");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(getHtml(adminUrl + "/services/orders"))
                    .contains("Active instances", "feature.x.enabled", "limits.max", "50")
                    .doesNotContain("class=\"error\""));
            assertThat(getHtml(adminUrl + "/services/orders/history?key=limits.max"))
                    .contains("v1", "alice", "(created)", "launch");
        }

        // A clean shutdown deregisters the instance
        assertThat(registry.service("orders")).isEmpty();
        assertThat(getHtml(adminUrl + "/")).doesNotContain("href=\"/services/orders\"");
    }

    /**
     * The Phase 5B goal: a change made in the admin UI is written by the service, reaches every
     * instance's cache through the change stream, and shows up in the UI's history. Same for deletes.
     */
    @Test
    void changesMadeInTheAdminUiReachEveryInstanceAndTheHistory() throws Exception {
        InstanceRegistry registry = admin.getBean(InstanceRegistry.class);

        try (ConfigurableApplicationContext first = startService("payments");
             ConfigurableApplicationContext second = startService("payments")) {
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> registry.service("payments").map(s -> s.activeCount() == 2).orElse(false));
            List<String> instanceUrls = List.of(urlOf(first), urlOf(second));

            // Review first: nothing is written until the change is applied
            assertThat(postForm("/services/payments/edit/review", Map.of(
                    "key", "limits.max", "value", "75", "changedBy", "carol", "comment", "more traffic")))
                    .satisfies(r -> assertThat(r.statusCode()).isEqualTo(200))
                    .satisfies(r -> assertThat(r.body()).contains("<h1>Review change</h1>", "this creates the key"));

            HttpResponse<String> applied = postForm("/services/payments/update", Map.of(
                    "key", "limits.max", "value", "75", "changedBy", "carol", "comment", "more traffic"));
            assertThat(applied.statusCode()).isEqualTo(302);
            assertThat(applied.headers().firstValue("Location")).get().asString().endsWith("/services/payments");

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                for (String url : instanceUrls) {
                    assertThat(currentConfigOf(url)).contains("\"limits.max\":\"75\"");
                }
            });
            assertThat(getHtml(adminUrl + "/services/payments/history?key=limits.max"))
                    .contains("v1", "carol", "(created)", "more traffic");

            HttpResponse<String> deleted = postForm("/services/payments/delete", Map.of(
                    "key", "limits.max", "changedBy", "dave", "comment", "retired"));
            assertThat(deleted.statusCode()).isEqualTo(302);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                for (String url : instanceUrls) {
                    assertThat(currentConfigOf(url)).doesNotContain("limits.max");
                }
            });
            assertThat(getHtml(adminUrl + "/services/payments/history?key=limits.max"))
                    .contains("v2", "dave", "(deleted)", "retired", "Restore");
        }
    }

    private static ConfigurableApplicationContext startService() {
        return startService("orders");
    }

    private static ConfigurableApplicationContext startService(String name) {
        return new SpringApplicationBuilder(DemoServiceApplication.class).run(
                "--server.port=0",
                "--spring.application.name=" + name,
                NO_MONGO_AUTOCONFIG,
                "--configstream.team=team-a",
                "--configstream.mongo.uri=" + MONGO.getReplicaSetUrl(name),
                "--configstream.internal.secret=" + SECRET,
                "--configstream.admin.url=" + adminUrl,
                "--configstream.admin.heartbeat-interval=200ms",
                "--configstream.instance.host=localhost");
    }

    private static void updateThroughService(String serviceUrl, String key, String value) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serviceUrl + "/internal/config/update"))
                .header("Content-Type", "application/json")
                .header("X-ConfigStream-Secret", SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"key\":\"%s\",\"value\":\"%s\",\"changedBy\":\"alice\",\"comment\":\"launch\"}"
                                .formatted(key, value)))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private static String urlOf(ConfigurableApplicationContext service) {
        return "http://localhost:" + service.getEnvironment().getProperty("local.server.port");
    }

    /** What this one instance's cache holds, as JSON. */
    private static String currentConfigOf(String serviceUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serviceUrl + "/internal/config"))
                .header("X-ConfigStream-Secret", SECRET)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
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

    private static String getHtml(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url).isEqualTo(200);
        return response.body();
    }
}

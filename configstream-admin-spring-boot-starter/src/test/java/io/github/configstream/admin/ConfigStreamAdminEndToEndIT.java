package io.github.configstream.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.github.configstream.adminhost.AdminHostApplication;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.demoservice.DemoServiceApplication;
import io.github.configstream.testsupport.TestMongo;
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
import java.util.UUID;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Real configstream services registering with a real admin server, against a real MongoDB: services create their
 * declared properties on startup, the admin UI shows their live instances, values and history, and changes made in
 * the UI reach every instance. The services declare feature.x.enabled (boolean) and limits.max (int, initially 10).
 */
class ConfigStreamAdminEndToEndIT {

    private static final String SECRET = "0123456789abcdef-e2e-secret";
    private static final String NO_MONGO_AUTOCONFIG =
            "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration";
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final Duration PROPAGATION = Duration.ofSeconds(5);

    static final HttpClient http = HttpClient.newHttpClient();
    static MongoClient mongo;
    static ConfigurableApplicationContext admin;
    static String adminUrl;

    @BeforeAll
    static void startAdmin() {
        mongo = TestMongo.client();
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
        mongo.close();
    }

    @Test
    void serviceCreatesItsPropertiesAndTheyShowUpInTheAdminUi() throws Exception {
        InstanceRegistry registry = admin.getBean(InstanceRegistry.class);

        try (ConfigurableApplicationContext service = startService("orders")) {
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> registry.service("orders").map(s -> s.activeCount() == 1).orElse(false));
            updateThroughService(urlOf(service), "limits.max", "50");

            assertThat(getHtml(adminUrl + "/")).contains("href=\"/services/orders\"", "team-a", "1 active instance");
            await().atMost(PROPAGATION).untilAsserted(() -> assertThat(getHtml(adminUrl + "/services/orders"))
                    .contains("Active instances", "feature.x.enabled", "limits.max", "50")
                    .doesNotContain("cs-banner error"));
            assertThat(getHtml(adminUrl + "/services/orders/history?key=limits.max"))
                    .contains("v2", "alice", "launch", "v1", "orders (manifest)", "(created)", "Created from configstream.yml");
        }

        // A clean shutdown deregisters the instance
        assertThat(registry.service("orders")).isEmpty();
        assertThat(getHtml(adminUrl + "/")).doesNotContain("href=\"/services/orders\"");
    }

    /**
     * A change made in the admin UI is written by the service, reaches every instance's cache through the change
     * stream, and shows up in the UI's history. Properties can't be added from the UI, and only orphans can be deleted.
     */
    @Test
    void changesMadeInTheAdminUiFollowThePropertyRules() throws Exception {
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
                    .satisfies(r -> assertThat(r.body()).contains("<h1>Review change</h1>", ">10<", ">75<"));

            HttpResponse<String> applied = postForm("/services/payments/update", Map.of(
                    "key", "limits.max", "value", "75", "changedBy", "carol", "comment", "more traffic"));
            assertThat(applied.statusCode()).isEqualTo(302);
            assertThat(applied.headers().firstValue("Location")).get().asString().endsWith("/services/payments");
            await().atMost(PROPAGATION).untilAsserted(() -> {
                for (String url : instanceUrls) {
                    assertThat(currentConfigOf(url)).contains("\"limits.max\":{\"type\":\"int\",\"value\":\"75\"}");
                }
            });
            assertThat(getHtml(adminUrl + "/services/payments/history?key=limits.max"))
                    .contains("v2", "carol", "more traffic");

            // A value that doesn't fit the type is rejected by the service, with its reason shown
            assertThat(postForm("/services/payments/update", Map.of(
                    "key", "limits.max", "value", "lots", "changedBy", "carol")).body())
                    .contains("Update failed", "is not a valid int.");

            // Properties are created only from the manifest
            assertThat(postForm("/services/payments/update", Map.of(
                    "key", "brand.new", "value", "1", "changedBy", "carol")).body())
                    .contains("Update failed", "Properties are created only from the application manifest");

            // A declared property is in use, so it can't be deleted
            assertThat(postForm("/services/payments/delete", Map.of("key", "limits.max", "changedBy", "dave")).body())
                    .contains("is declared by 2 active instances of payments", "can&#39;t be deleted");

            // An orphan (in the store, declared by no instance) can be deleted, and its history stays
            configCollection("payments").insertOne(new Document("_id", "feature.old.flag")
                    .append("type", "boolean").append("value", true).append("version", 1L));
            await().atMost(PROPAGATION).untilAsserted(() -> assertThat(currentConfigOf(instanceUrls.get(0))).contains("feature.old.flag"));
            // The instances registered what their manifest declares, so the admin knows it is an orphan
            assertThat(getHtml(adminUrl + "/services/payments?view=orphans"))
                    .contains("Orphaned", "<span class=\"cs-type\">boolean</span>", "/services/payments/delete?key=feature.old.flag")
                    .doesNotContain("/services/payments/delete?key=limits.max", "Add entry");
            HttpResponse<String> deleted = postForm("/services/payments/delete", Map.of(
                    "key", "feature.old.flag", "changedBy", "dave", "comment", "retired"));
            assertThat(deleted.statusCode()).isEqualTo(302);
            await().atMost(PROPAGATION).untilAsserted(() -> {
                for (String url : instanceUrls) {
                    assertThat(currentConfigOf(url)).doesNotContain("feature.old.flag");
                }
            });
            assertThat(getHtml(adminUrl + "/services/payments/history?key=feature.old.flag"))
                    .contains("v2", "dave", "(deleted)", "retired");
        }
    }

    private static ConfigurableApplicationContext startService(String name) {
        return new SpringApplicationBuilder(DemoServiceApplication.class).run(
                "--server.port=0",
                "--spring.application.name=" + name,
                NO_MONGO_AUTOCONFIG,
                "--configstream.team=team-a",
                "--configstream.manifest=classpath:e2e/configstream.yml",
                "--configstream.mongo.uri=" + TestMongo.uri(),
                "--configstream.mongo.database=" + TestMongo.database(),
                "--configstream.mongo.config-collection=" + collectionName(name),
                "--configstream.internal.secret=" + SECRET,
                "--configstream.admin.url=" + adminUrl,
                "--configstream.admin.heartbeat-interval=200ms",
                "--configstream.instance.host=localhost");
    }

    /** One collection per service, as in production; unique per run so reruns against a shared database start clean. */
    private static String collectionName(String service) {
        return service + "_config_" + RUN;
    }

    private static MongoCollection<Document> configCollection(String service) {
        return mongo.getDatabase(TestMongo.database()).getCollection(collectionName(service));
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

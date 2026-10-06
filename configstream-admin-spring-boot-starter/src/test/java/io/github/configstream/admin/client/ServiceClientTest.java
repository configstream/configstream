package io.github.configstream.admin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.github.configstream.admin.ConfigStreamAdminProperties;
import io.github.configstream.admin.registry.InstanceRegistration;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.api.ConfigHistoryEntry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

class ServiceClientTest {

    private static final String SECRET = "0123456789abcdef-secret";
    private static final String ADMIN_TOKEN = "Bearer token-for-configstream-admin";

    private final ConfigStreamAdminProperties properties = new ConfigStreamAdminProperties();
    private final InstanceRegistry registry = new InstanceRegistry(properties, Clock.systemUTC());
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private ServiceClient client;

    ServiceClientTest() {
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.getServiceSecrets().put("orders", SECRET);
        RestClient.Builder builder = RestClient.builder()
                .messageConverters(c -> c.add(0, new MappingJackson2HttpMessageConverter(json)));
        client = new ServiceClient(builder, registry, properties);
    }

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    @Test
    void readsCurrentConfigWithTheSecret() throws IOException {
        int port = fakeService(200, "{\"a\":{\"type\":\"int\",\"value\":\"1\"},\"b\":{\"type\":\"string\",\"value\":\"2\"}}");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThat(client.currentConfig("orders")).isEqualTo(Map.of("a", new ConfigEntry("int", "1"), "b", new ConfigEntry("string", "2")));
    }

    @Test
    void readsHistory() throws IOException {
        ConfigHistoryEntry entry = new ConfigHistoryEntry("a", 2, "1", "2", "alice",
                Instant.parse("2026-09-25T10:00:00Z"), "why");
        int port = fakeService(200, json.writeValueAsString(List.of(entry)));
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThat(client.history("orders", "a", 10)).containsExactly(entry);
    }

    @Test
    void failsOverToTheNextHealthyInstance() throws IOException {
        registry.register(new InstanceRegistration("orders", "o-1-dead", "localhost", unusedPort(), null, null));
        int port = fakeService(200, "{\"a\":{\"type\":\"int\",\"value\":\"1\"}}");
        registry.register(new InstanceRegistration("orders", "o-2", "localhost", port, null, null));

        assertThat(client.currentConfig("orders")).containsEntry("a", new ConfigEntry("int", "1"));
    }

    @Test
    void reportsWhenNoInstanceAnswers() throws IOException {
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", unusedPort(), null, null));

        assertThatThrownBy(() -> client.currentConfig("orders"))
                .isInstanceOf(ServiceCallException.class)
                .hasMessageContaining("Could not reach any instance of 'orders' (1 tried)");
    }

    @Test
    void reportsRejectedSecret() throws IOException {
        int port = fakeService(401, "");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThatThrownBy(() -> client.currentConfig("orders"))
                .isInstanceOf(ServiceCallException.class)
                .hasMessageContaining("rejected the admin server's secret");
    }

    @Test
    void reportsMissingEndpoint() throws IOException {
        int port = fakeService(404, "");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThatThrownBy(() -> client.currentConfig("orders"))
                .hasMessageContaining("does not expose the configstream internal endpoints");
    }

    @Test
    void updatePostsTheChangeAndReturnsTheRecordedEntry() throws IOException {
        ConfigHistoryEntry entry = new ConfigHistoryEntry("a", 2, "1", "2", "alice",
                Instant.parse("2026-09-25T10:00:00Z"), "why");
        int port = fakeService(200, json.writeValueAsString(entry));
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThat(client.update("orders", "a", "2", "int", "alice", "why")).contains(entry);
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/internal/config/update");
            assertThat(json.readTree(r.body())).isEqualTo(json.readTree(
                    "{\"key\":\"a\",\"value\":\"2\",\"type\":\"int\",\"changedBy\":\"alice\",\"comment\":\"why\"}"));
        });
    }

    @Test
    void deletePostsTheKeyAndReturnsTheRecordedEntry() throws IOException {
        ConfigHistoryEntry entry = new ConfigHistoryEntry("a", 3, "2", null, "bob",
                Instant.parse("2026-09-25T10:00:00Z"), null);
        int port = fakeService(200, json.writeValueAsString(entry));
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThat(client.delete("orders", "a", "bob", null)).contains(entry);
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/internal/config/delete");
            assertThat(json.readTree(r.body())).isEqualTo(json.readTree(
                    "{\"key\":\"a\",\"changedBy\":\"bob\",\"comment\":null}"));
        });
    }

    @Test
    void noContentMeansNothingChanged() throws IOException {
        int port = fakeService(204, "");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThat(client.update("orders", "a", "1", null, "alice", null)).isEmpty();
        assertThat(client.delete("orders", "a", "alice", null)).isEmpty();
    }

    @Test
    void writesFailOverOnlyWhenTheInstanceWasNeverReached() throws IOException {
        registry.register(new InstanceRegistration("orders", "o-1-dead", "localhost", unusedPort(), null, null));
        int port = fakeService(204, "");
        registry.register(new InstanceRegistration("orders", "o-2", "localhost", port, null, null));

        assertThat(client.update("orders", "a", "1", null, "alice", null)).isEmpty();
        assertThat(requests).hasSize(1);
    }

    @Test
    void writesDoNotRetryElsewhereAfterAServerError() throws IOException {
        int failing = fakeService(500, "");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", failing, null, null));
        int healthy = fakeService(200, "{}");
        registry.register(new InstanceRegistration("orders", "o-2", "localhost", healthy, null, null));

        // The first instance may have committed the change before failing, so the outcome is unknown
        assertThatThrownBy(() -> client.update("orders", "a", "1", null, "alice", null))
                .isInstanceOf(ServiceCallException.class)
                .hasMessageContaining("did not confirm the change")
                .hasMessageContaining("check the key's history");
        assertThat(requests).hasSize(1);

        // Reads are safe to retry, so they still fail over
        assertThat(client.currentConfig("orders")).isEmpty();
    }

    @Test
    void reportsRejectedRequests() throws IOException {
        int port = fakeService(400, "");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThatThrownBy(() -> client.update("orders", "a", "1", null, "alice", null))
                .isInstanceOf(ServiceCallException.class)
                .hasMessageContaining("'orders' rejected the request")
                .hasMessageContaining("400");
    }

    @Test
    void reportsTheServicesReasonForARejectedChange() throws IOException {
        int port = fakeService(400, "{\"error\":\"'abc' is not a valid int.\"}");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThatThrownBy(() -> client.update("orders", "limits.max", "abc", null, "alice", null))
                .isInstanceOf(ServiceCallException.class)
                .hasMessage("'orders' rejected the request: 'abc' is not a valid int.");
    }

    @Test
    void aMissingPropertyIsNotMistakenForMissingEndpoints() throws IOException {
        int port = fakeService(404, "{\"error\":\"No property 'brand.new'. Properties are created only from the "
                + "application manifest (configstream.yml).\"}");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThatThrownBy(() -> client.update("orders", "brand.new", "1", null, "alice", null))
                .hasMessage("'orders' rejected the request: No property 'brand.new'. Properties are created only from "
                        + "the application manifest (configstream.yml).");
    }

    @Test
    void reportsMissingSecret() {
        registry.register(new InstanceRegistration("billing", "b-1", "localhost", 8080, null, null));

        assertThatThrownBy(() -> client.currentConfig("billing"))
                .isInstanceOf(ServiceCallException.class)
                .hasMessageContaining("configstream.admin-server.service-secrets.billing");
    }

    @Test
    void withTokensNoSecretIsNeededOrSent() throws IOException {
        properties.setOauth2Client("configstream-admin");
        // Stands in for the OAuth2 interceptor the auto-configuration adds to the builder
        client = new ServiceClient(RestClient.builder().requestInterceptor((request, body, execution) -> {
            request.getHeaders().set("Authorization", ADMIN_TOKEN);
            return execution.execute(request, body);
        }), registry, properties);
        int port = fakeService(200, "{}");
        registry.register(new InstanceRegistration("billing", "b-1", "localhost", port, null, null));

        assertThat(client.currentConfig("billing")).isEmpty();
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.secret()).isNull();
            assertThat(r.authorization()).isEqualTo(ADMIN_TOKEN);
        });
    }

    @Test
    void reportsRejectedToken() throws IOException {
        properties.setOauth2Client("configstream-admin");
        int port = fakeService(401, "");
        registry.register(new InstanceRegistration("billing", "b-1", "localhost", port, null, null));

        assertThatThrownBy(() -> client.currentConfig("billing"))
                .isInstanceOf(ServiceCallException.class)
                .hasMessage("'billing' rejected the admin server's token.");
    }

    @Test
    void explainsARefusalWithoutAReason() throws IOException {
        int port = fakeService(403, "");
        registry.register(new InstanceRegistration("orders", "o-1", "localhost", port, null, null));

        assertThatThrownBy(() -> client.currentConfig("orders"))
                .isInstanceOf(ServiceCallException.class)
                .hasMessageContaining("'orders' refused the admin server (403)")
                .hasMessageContaining("configstream.internal.admin-principal");
    }

    @Test
    void defaultSecretCoversUnlistedServices() throws IOException {
        properties.setDefaultServiceSecret(SECRET);
        int port = fakeService(200, "{}");
        registry.register(new InstanceRegistration("billing", "b-1", "localhost", port, null, null));

        assertThat(client.currentConfig("billing")).isEmpty();
    }

    @Test
    void reportsUnknownServiceAndNoAddress() {
        assertThatThrownBy(() -> client.currentConfig("nope")).hasMessageContaining("No service named 'nope'");

        registry.register(new InstanceRegistration("orders", "o-1", "localhost", null, null, null));
        assertThatThrownBy(() -> client.currentConfig("orders"))
                .hasMessageContaining("No active instance of 'orders' with a reachable address");
    }

    /**
     * Answers every request with the given status and body, but only if the secret header or the admin's token
     * matches. Records every request it receives in {@link #requests}.
     */
    private int fakeService(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/config", exchange -> {
            String secret = exchange.getRequestHeaders().getFirst(ServiceClient.SECRET_HEADER);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(new Request(exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), secret, authorization));
            boolean authorized = SECRET.equals(secret) || ADMIN_TOKEN.equals(authorization);
            int code = authorized ? status : 401;
            byte[] bytes = (authorized ? body : "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        servers.add(server);
        return server.getAddress().getPort();
    }

    private record Request(String path, String body, String secret, String authorization) {
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

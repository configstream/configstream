package io.github.configstream.admin.client;

import io.github.configstream.admin.ConfigStreamAdminProperties;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.admin.registry.RegisteredInstance;
import io.github.configstream.admin.registry.ServiceSummary;
import io.github.configstream.api.ConfigHistoryEntry;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls a service's configstream internal endpoints ({@code /internal/config/**}). Every instance
 * of a service reads and writes the same store, so any active instance can answer: instances are
 * tried in turn until one responds.
 */
public class ServiceClient {

    static final String SECRET_HEADER = "X-ConfigStream-Secret";

    private static final Logger log = LoggerFactory.getLogger(ServiceClient.class);

    private final RestClient http;
    private final InstanceRegistry registry;
    private final ConfigStreamAdminProperties properties;

    public ServiceClient(RestClient.Builder builder, InstanceRegistry registry, ConfigStreamAdminProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getRequestTimeout());
        requestFactory.setReadTimeout(properties.getRequestTimeout());
        this.http = builder.requestFactory(requestFactory).build();
        this.registry = registry;
        this.properties = properties;
    }

    /** Every property with its type, as one active instance sees them, sorted by key. */
    public Map<String, ConfigEntry> currentConfig(String serviceName) {
        Map<String, ConfigEntry> properties = call(serviceName, (baseUrl, secret) -> http.get()
                .uri(baseUrl + "/internal/config")
                .header(SECRET_HEADER, secret)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, ConfigEntry>>() {}));
        return new TreeMap<>(properties);
    }

    /** Changes to {@code key}, newest first. */
    public List<ConfigHistoryEntry> history(String serviceName, String key, int limit) {
        return call(serviceName, (baseUrl, secret) -> http.get()
                .uri(baseUrl + "/internal/config/history?key={key}&limit={limit}", key, limit)
                .header(SECRET_HEADER, secret)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ConfigHistoryEntry>>() {}));
    }

    /**
     * Sets the existing property {@code key} to {@code value} through the service, recording who changed it and why.
     * {@code type} is the type the admin believes the property has; the service rejects the change if it differs.
     *
     * @return the recorded history entry, or empty if the key already had this value
     */
    public Optional<ConfigHistoryEntry> update(String serviceName, String key, String value, String type,
            String changedBy, String comment) {
        return write(serviceName, "/internal/config/update", new UpdateRequest(key, value, type, changedBy, comment));
    }

    /**
     * Deletes {@code key} through the service; the service refuses keys it declares.
     *
     * @return the recorded history entry, or empty if the key doesn't exist
     */
    public Optional<ConfigHistoryEntry> delete(String serviceName, String key, String changedBy, String comment) {
        return write(serviceName, "/internal/config/delete", new DeleteRequest(key, changedBy, comment));
    }

    private Optional<ConfigHistoryEntry> write(String serviceName, String path, Object body) {
        return Optional.ofNullable(call(serviceName, true, (baseUrl, secret) -> http.post()
                .uri(baseUrl + path)
                .header(SECRET_HEADER, secret)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ConfigHistoryEntry.class))); // null for 204: nothing changed
    }

    private <T> T call(String serviceName, BiFunction<String, String, T> request) {
        return call(serviceName, false, request);
    }

    /**
     * Tries each active instance in turn. Reads fail over on any error; writes only when the request
     * never reached the instance, because after a timeout or server error the change may already be
     * committed, and retrying elsewhere would report it as "nothing changed".
     */
    private <T> T call(String serviceName, boolean isWrite, BiFunction<String, String, T> request) {
        ServiceSummary service = registry.service(serviceName)
                .orElseThrow(() -> new ServiceCallException("No service named '" + serviceName + "' is registered."));
        String secret = properties.secretFor(serviceName);
        if (secret == null || secret.isBlank()) {
            throw new ServiceCallException("No secret configured for '" + serviceName + "'. Set configstream.admin-server.service-secrets."
                    + serviceName + " (or configstream.admin-server.default-service-secret) to its configstream.internal.secret.");
        }
        List<String> baseUrls = service.activeInstances().stream()
                .map(RegisteredInstance::baseUrl)
                .filter(url -> url != null)
                .toList();
        if (baseUrls.isEmpty()) {
            throw new ServiceCallException("No active instance of '" + serviceName + "' with a reachable address, so its "
                    + "config can't be viewed or changed until one is running.");
        }
        RestClientException lastFailure = null;
        for (String baseUrl : baseUrls) {
            try {
                return request.apply(baseUrl, secret);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    // Every instance shares the secret, so trying the others won't help
                    throw new ServiceCallException("'" + serviceName + "' rejected the configured secret.", e);
                }
                // The service explains a rejected change, e.g. a value that doesn't fit the property's type
                String reason = errorMessage(e);
                if (e.getStatusCode() == HttpStatus.NOT_FOUND && reason == null) {
                    throw new ServiceCallException("'" + serviceName + "' does not expose the configstream internal "
                            + "endpoints. Is configstream.internal.secret set on the service?", e);
                }
                // Any other 4xx: the request itself was rejected, and every instance would reject it too
                throw new ServiceCallException("'" + serviceName + "' rejected the request: " + (reason != null ? reason
                        : e.getStatusText() + " (" + e.getStatusCode().value() + ")."), e);
            } catch (RestClientException e) {
                if (isWrite && !neverReachedInstance(e)) {
                    throw new ServiceCallException("'" + serviceName + "' did not confirm the change ("
                            + e.getMessage() + "). It may or may not have been applied: check the key's history "
                            + "before trying again.", e);
                }
                log.debug("Call to {} at {} failed; trying next instance", serviceName, baseUrl, e);
                lastFailure = e;
            }
        }
        throw new ServiceCallException("Could not reach any instance of '" + serviceName + "' ("
                + baseUrls.size() + " tried): " + lastFailure.getMessage(), lastFailure);
    }

    /** True if the connection itself failed, so the instance cannot have acted on the request. */
    private static boolean neverReachedInstance(RestClientException e) {
        return e instanceof ResourceAccessException
                && (e.getCause() instanceof ConnectException || e.getCause() instanceof UnknownHostException);
    }

    /** The service's {@code {"error": "..."}} message, or {@code null} if the response has none. */
    private static String errorMessage(HttpClientErrorException e) {
        try {
            ErrorResponse body = e.getResponseBodyAs(ErrorResponse.class);
            return body == null || body.error() == null || body.error().isBlank() ? null : body.error();
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private record UpdateRequest(String key, String value, String type, String changedBy, String comment) {
    }
    private record ErrorResponse(String error) {
    }

    private record DeleteRequest(String key, String changedBy, String comment) {
    }
}

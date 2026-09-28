package io.github.configstream.spring;

import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyNotFoundException;
import io.github.configstream.api.PropertyType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the admin app change properties through this service, using this service's own database
 * credentials, so the admin app never holds credentials for every service's database.
 *
 * <p>A property is identified by its key and type, so every request names both. Changes only edit values: properties
 * are created only when a service declaring them starts, and a property this instance declares can't be deleted.
 * Rejected changes return 4xx with {@code {"error": "..."}}, a message meant for the person who made the change.
 *
 * <p>Guarded by a shared secret for now; OIDC replaces it once the admin app exists.
 */
@RestController
@RequestMapping("/internal/config")
class InternalConfigController {

    static final String SECRET_HEADER = "X-ConfigStream-Secret";
    static final int DEFAULT_HISTORY_LIMIT = 50;
    static final int MAX_HISTORY_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(InternalConfigController.class);

    private final ConfigService config;
    private final LiveConfigRegistry registry;
    private final ConfigWriter writer;
    private final ConfigHistory history;
    private final byte[] secret;

    InternalConfigController(ConfigService config, LiveConfigRegistry registry, ConfigWriter writer, ConfigHistory history,
            String secret) {
        this.config = config;
        this.registry = registry;
        this.writer = writer;
        this.history = history;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every stored property as this instance currently sees it, sorted by key and type:
     * {@code [{"key": "limits.max", "type": "int", "value": "20"}]}. The same key can appear with several types while
     * instances running different code share the store.
     */
    @GetMapping
    ResponseEntity<List<PropertyView>> current(
            @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret) {
        if (!secretMatches(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(config.values().entrySet().stream()
                .map(e -> new PropertyView(e.getKey().key(), e.getKey().type().typeName(), e.getValue().text()))
                .sorted(Comparator.comparing(PropertyView::key).thenComparing(PropertyView::type))
                .toList());
    }

    /**
     * Sets an existing property's value and records it in the history. Returns 200 with the recorded
     * {@link ConfigHistoryEntry}, 204 if the property already had this value, 404 if no property has this key and type,
     * or 400 if the value doesn't fit the type. Caches, including this instance's, update shortly after through the
     * change stream.
     *
     * <p>To roll back, send the historical value again with a comment such as {@code "Reverted to v3"}.
     */
    @PostMapping("/update")
    ResponseEntity<?> update(
            @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret,
            @RequestBody(required = false) UpdateRequest request) {
        if (!secretMatches(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request == null || isBlank(request.key()) || isBlank(request.type()) || request.value() == null
                || isBlank(request.changedBy())) {
            return error(HttpStatus.BAD_REQUEST, "key, type, value and changedBy are required.");
        }
        try {
            PropertyId id = PropertyId.of(request.key(), PropertyType.fromName(request.type()));
            return writer.write(new ConfigUpdate(id, request.value(), request.changedBy(), request.comment()))
                    .<ResponseEntity<?>>map(entry -> {
                        log.info("Property {} updated to v{} by {} via internal endpoint",
                                entry.id(), entry.version(), entry.changedBy());
                        return ResponseEntity.ok(entry);
                    })
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (PropertyNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) { // includes InvalidConfigValueException, an invalid key or type name
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Deletes a property this instance doesn't declare (an orphan): it leaves the store and every cache, and its
     * history is kept. Returns 200 with the recorded {@link ConfigHistoryEntry}, 204 if it doesn't exist, or 409 if
     * one of this instance's {@link LiveConfig} classes declares it. The same key with another type can be deleted.
     */
    @PostMapping("/delete")
    ResponseEntity<?> delete(
            @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret,
            @RequestBody(required = false) DeleteRequest request) {
        if (!secretMatches(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request == null || isBlank(request.key()) || isBlank(request.type()) || isBlank(request.changedBy())) {
            return error(HttpStatus.BAD_REQUEST, "key, type and changedBy are required.");
        }
        PropertyId id;
        try {
            id = PropertyId.of(request.key(), PropertyType.fromName(request.type()));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (registry.declares(id)) {
            return error(HttpStatus.CONFLICT, "'" + id.key() + "' (" + id.type().typeName() + ") is declared by this "
                    + "service, so it is in use and can't be deleted. Remove it from the service's @LiveConfig class first.");
        }
        return writer.delete(new ConfigDeletion(id, request.changedBy(), request.comment()))
                .<ResponseEntity<?>>map(entry -> {
                    log.info("Property {} deleted (v{}) by {} via internal endpoint",
                            entry.id(), entry.version(), entry.changedBy());
                    return ResponseEntity.ok(entry);
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Changes to one property, newest first. {@code limit} defaults to 50 and is capped at 500. */
    @GetMapping("/history")
    ResponseEntity<List<ConfigHistoryEntry>> history(
            @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer limit) {
        if (!secretMatches(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (isBlank(key) || isBlank(type) || (limit != null && limit <= 0)) {
            return ResponseEntity.badRequest().build();
        }
        PropertyId id;
        try {
            id = PropertyId.of(key, PropertyType.fromName(type));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        int effectiveLimit = limit == null ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        return ResponseEntity.ok(history.history(id, effectiveLimit));
    }

    private boolean secretMatches(String provided) {
        // Constant-time comparison so response timing doesn't reveal how much of the secret matched
        return provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), secret);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** {@code changedBy} is the admin app's authenticated user; {@code type} is e.g. {@code "int"}; {@code comment} is optional. */
    record UpdateRequest(String key, String type, String value, String changedBy, String comment) {
    }

    record DeleteRequest(String key, String type, String changedBy, String comment) {
    }

    /** A property's key, type and value as text. */
    record PropertyView(String key, String type, String value) {
    }

    record ErrorResponse(String error) {
    }
}

package io.github.configstream.spring;

import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.PropertyNotFoundException;
import io.github.configstream.api.PropertyType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * <p>Changes only edit values: properties are created only from the manifest, their types never change, and a
 * property this instance declares can't be deleted. Rejected changes return 4xx with {@code {"error": "..."}}, a
 * message meant for the person who made the change.
 *
 * <p>Only the admin server may call them: either authenticated by the host application (normally with an OAuth2
 * token) as {@code configstream.internal.admin-principal}, or sending {@code configstream.internal.secret} in the
 * {@code X-ConfigStream-Secret} header. Anyone else gets 401, or 403 if authenticated as someone else.
 */
@RestController
@RequestMapping("/internal/config")
class InternalConfigController {

    static final String SECRET_HEADER = "X-ConfigStream-Secret";
    static final int DEFAULT_HISTORY_LIMIT = 50;
    static final int MAX_HISTORY_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(InternalConfigController.class);

    private final ConfigService config;
    private final ConfigWriter writer;
    private final ConfigHistory history;
    private final byte[] secret;
    private final String adminPrincipal;

    /** {@code secret} and {@code adminPrincipal} may each be null (that way of calling is off), but not both. */
    InternalConfigController(ConfigService config, ConfigWriter writer, ConfigHistory history, String secret,
            String adminPrincipal) {
        this.config = config;
        this.writer = writer;
        this.history = history;
        this.secret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.adminPrincipal = adminPrincipal;
    }

    /** Every property as this instance currently sees it, sorted by key: {@code {"limits.max": {"type": "int", "value": "20"}}}. */
    @GetMapping
    ResponseEntity<?> current(
            Principal caller, @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret) {
        ResponseEntity<ErrorResponse> refusal = refusal(caller, providedSecret);
        if (refusal != null) {
            return refusal;
        }
        Map<String, PropertyView> values = new TreeMap<>();
        config.values().forEach((key, value) -> values.put(key, new PropertyView(value.type().typeName(), value.text())));
        return ResponseEntity.ok(values);
    }

    /**
     * Sets an existing property's value and records it in the history. Returns 200 with the recorded
     * {@link ConfigHistoryEntry}, 204 if the property already had this value, 404 if it doesn't exist, or 400 if the
     * value doesn't fit the property's type or the request names a different type. Caches, including this
     * instance's, update shortly after through the change stream.
     *
     * <p>To roll back, send the historical value again with a comment such as {@code "Reverted to v3"}.
     */
    @PostMapping("/update")
    ResponseEntity<?> update(
            Principal caller, @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret,
            @RequestBody(required = false) UpdateRequest request) {
        ResponseEntity<ErrorResponse> refusal = refusal(caller, providedSecret);
        if (refusal != null) {
            return refusal;
        }
        if (request == null || isBlank(request.key()) || request.value() == null || isBlank(request.changedBy())) {
            return error(HttpStatus.BAD_REQUEST, "key, value and changedBy are required.");
        }
        try {
            PropertyType type = request.type() == null ? null : PropertyType.fromName(request.type());
            return writer.write(new ConfigUpdate(request.key(), request.value(), type, request.changedBy(), request.comment()))
                    .<ResponseEntity<?>>map(entry -> {
                        log.info("Property '{}' updated to v{} by {} via internal endpoint",
                                entry.key(), entry.version(), entry.changedBy());
                        return ResponseEntity.ok(entry);
                    })
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (PropertyNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) { // includes InvalidConfigValueException and an unknown type name
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Deletes a property this instance doesn't declare (an orphan): it leaves the store and every cache, and its
     * history is kept. Returns 200 with the recorded {@link ConfigHistoryEntry}, 204 if it doesn't exist, or 409 if
     * this instance's manifest declares it.
     */
    @PostMapping("/delete")
    ResponseEntity<?> delete(
            Principal caller, @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret,
            @RequestBody(required = false) DeleteRequest request) {
        ResponseEntity<ErrorResponse> refusal = refusal(caller, providedSecret);
        if (refusal != null) {
            return refusal;
        }
        if (request == null || isBlank(request.key()) || isBlank(request.changedBy())) {
            return error(HttpStatus.BAD_REQUEST, "key and changedBy are required.");
        }
        if (config.manifest().declares(request.key())) {
            return error(HttpStatus.CONFLICT, "'" + request.key() + "' is declared in this service's manifest, so it is "
                    + "in use and can't be deleted. Remove it from configstream.yml first.");
        }
        return writer.delete(new ConfigDeletion(request.key(), request.changedBy(), request.comment()))
                .<ResponseEntity<?>>map(entry -> {
                    log.info("Property '{}' deleted (v{}) by {} via internal endpoint",
                            entry.key(), entry.version(), entry.changedBy());
                    return ResponseEntity.ok(entry);
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Changes to one property, newest first. {@code limit} defaults to 50 and is capped at 500. */
    @GetMapping("/history")
    ResponseEntity<?> history(
            Principal caller, @RequestHeader(name = SECRET_HEADER, required = false) String providedSecret,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) Integer limit) {
        ResponseEntity<ErrorResponse> refusal = refusal(caller, providedSecret);
        if (refusal != null) {
            return refusal;
        }
        if (isBlank(key) || (limit != null && limit <= 0)) {
            return ResponseEntity.badRequest().build();
        }
        int effectiveLimit = limit == null ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        return ResponseEntity.ok(history.history(key, effectiveLimit));
    }

    /** @return why the caller is refused, or {@code null} if it is the admin server */
    private ResponseEntity<ErrorResponse> refusal(Principal caller, String providedSecret) {
        if (adminPrincipal != null && caller != null && adminPrincipal.equals(caller.getName())) {
            return null;
        }
        if (secretMatches(providedSecret)) {
            return null;
        }
        if (adminPrincipal != null && caller != null) {
            return error(HttpStatus.FORBIDDEN, "The caller is authenticated as '" + caller.getName() + "', which isn't "
                    + "this service's configstream.internal.admin-principal, so it can't use these endpoints.");
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private boolean secretMatches(String provided) {
        // Constant-time comparison so response timing doesn't reveal how much of the secret matched
        return secret != null && provided != null
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), secret);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * {@code changedBy} is the admin app's authenticated user; {@code type} (e.g. {@code "int"}) and {@code comment}
     * are optional. A {@code type} different from the property's is rejected: types come only from the manifest.
     */
    record UpdateRequest(String key, String value, String type, String changedBy, String comment) {
    }

    record DeleteRequest(String key, String changedBy, String comment) {
    }

    /** A property's type and its value as text. */
    record PropertyView(String type, String value) {
    }

    record ErrorResponse(String error) {
    }
}

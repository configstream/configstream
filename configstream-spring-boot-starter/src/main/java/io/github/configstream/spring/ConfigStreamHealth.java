package io.github.configstream.spring;

import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigSourceStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether this instance is receiving config changes, for the {@code configstream} entry of
 * {@code /actuator/health}. While connected it is UP. When cut off, it stays UP while reconnecting (a database failover
 * recovers in seconds) and turns DOWN once the outage lasts longer than {@code configstream.health.down-after}.
 *
 * <p>Free of Actuator types, because Spring Boot 4 moved them to another package: {@link ConfigStreamHealthIndicator}
 * (Boot 3) and {@link ConfigStreamBoot4HealthIndicator} (Boot 4) turn the result into their Boot version's health.
 *
 * <p>Meant for alerts and readiness checks. Don't make it part of a liveness check that restarts the application:
 * if the database is unreachable, restarting every instance at once makes things worse.
 */
final class ConfigStreamHealth {

    /** An Actuator status code ({@code UP}, {@code DOWN} or {@code UNKNOWN}) and the details to show with it. */
    record Result(String status, Map<String, Object> details) {
    }

    private final ConfigChangeSource source;
    private final Duration downAfter;
    private final Clock clock;

    ConfigStreamHealth(ConfigChangeSource source, Duration downAfter, Clock clock) {
        this.source = source;
        this.downAfter = downAfter;
        this.clock = clock;
    }

    Result check() {
        ConfigSourceStatus status = source.status();
        Map<String, Object> details = new LinkedHashMap<>();
        if (status == null) {
            details.put("reason", "The config source doesn't report its status.");
            return new Result("UNKNOWN", details);
        }
        details.put("connected", status.connected());
        details.put(status.connected() ? "connectedSince" : "disconnectedSince", status.since().toString());
        if (status.lastChangeAt() != null) {
            details.put("lastChangeReceived", status.lastChangeAt().toString());
        }
        if (status.connected()) {
            return new Result("UP", details);
        }
        Duration down = Duration.between(status.since(), Instant.now(clock));
        details.put("lastError", status.lastError());
        if (down.compareTo(downAfter) < 0) {
            // Probably a short interruption that recovers by itself
            details.put("reconnecting", true);
            return new Result("UP", details);
        }
        details.put("reason", "No config changes can reach this instance for " + down.toSeconds()
                + "s; it keeps its last known values and keeps trying to reconnect.");
        return new Result("DOWN", details);
    }
}

package io.github.configstream.spring;

import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigSourceStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Reports whether this instance is receiving config changes, as the {@code configstream} entry of
 * {@code /actuator/health}. While connected it is UP. When cut off, it stays UP while reconnecting (a database failover
 * recovers in seconds) and turns DOWN once the outage lasts longer than {@code configstream.health.down-after}.
 *
 * <p>Meant for alerts and readiness checks. Don't make it part of a liveness check that restarts the application:
 * if the database is unreachable, restarting every instance at once makes things worse.
 */
class ConfigStreamHealthIndicator implements HealthIndicator {

    private final ConfigChangeSource source;
    private final Duration downAfter;
    private final Clock clock;

    ConfigStreamHealthIndicator(ConfigChangeSource source, Duration downAfter, Clock clock) {
        this.source = source;
        this.downAfter = downAfter;
        this.clock = clock;
    }

    @Override
    public Health health() {
        ConfigSourceStatus status = source.status();
        if (status == null) {
            return Health.unknown().withDetail("reason", "The config source doesn't report its status.").build();
        }
        Health.Builder health = status.connected() ? Health.up() : Health.down();
        health.withDetail("connected", status.connected())
                .withDetail(status.connected() ? "connectedSince" : "disconnectedSince", status.since().toString());
        if (status.lastChangeAt() != null) {
            health.withDetail("lastChangeReceived", status.lastChangeAt().toString());
        }
        if (!status.connected()) {
            Duration down = Duration.between(status.since(), Instant.now(clock));
            health.withDetail("lastError", status.lastError());
            if (down.compareTo(downAfter) < 0) {
                // Probably a short interruption that recovers by itself
                health.up().withDetail("reconnecting", true);
            } else {
                health.withDetail("reason", "No config changes can reach this instance for " + down.toSeconds()
                        + "s; it keeps its last known values and keeps trying to reconnect.");
            }
        }
        return health.build();
    }
}

package io.github.configstream.admin.registry;

import java.time.Duration;
import java.time.Instant;

/**
 * A point-in-time view of one registered instance.
 *
 * @param up whether a heartbeat arrived within the lease duration
 */
public record RegisteredInstance(
        InstanceRegistration registration, Instant registeredAt, Instant lastHeartbeat, boolean up) {

    public String instanceId() {
        return registration.instanceId();
    }

    /** {@code host:port} for display, or just the host if the instance didn't report a port. */
    public String address() {
        return registration.port() == null ? registration.host() : registration.host() + ":" + registration.port();
    }

    /** {@code http://host:port}, or {@code null} if the instance didn't report a port. */
    public String baseUrl() {
        return registration.port() == null ? null : "http://" + registration.host() + ":" + registration.port();
    }

    public Duration sinceLastHeartbeat(Instant now) {
        return Duration.between(lastHeartbeat, now);
    }
}

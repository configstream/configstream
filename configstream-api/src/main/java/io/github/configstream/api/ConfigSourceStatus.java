package io.github.configstream.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Whether a {@link ConfigChangeSource} is currently receiving changes, for health checks.
 *
 * @param connected      whether the source is watching the store right now
 * @param since          when it last became connected or disconnected
 * @param lastChangeAt   when the last change arrived, or {@code null} if none has since it started
 * @param lastError      why it is disconnected, or {@code null} while connected
 */
public record ConfigSourceStatus(boolean connected, Instant since, Instant lastChangeAt, String lastError) {

    public ConfigSourceStatus {
        Objects.requireNonNull(since, "since");
    }
}

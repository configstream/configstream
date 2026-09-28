package io.github.configstream.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable record of a change made through a {@link ConfigWriter}.
 *
 * @param key       the property key
 * @param version   per-key version this change produced, starting at 1
 * @param oldValue  the value before the change, or {@code null} if the key was created
 * @param newValue  the value after the change, or {@code null} if the key was deleted
 * @param changedBy who made the change
 * @param changedAt when the change was made
 * @param comment   optional reason, e.g. {@code "Reverted to v3"}; may be {@code null}
 */
public record ConfigHistoryEntry(
        String key, long version, String oldValue, String newValue, String changedBy, Instant changedAt,
        String comment) {

    public ConfigHistoryEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(changedBy, "changedBy");
        Objects.requireNonNull(changedAt, "changedAt");
    }

    /** Whether this change deleted the key. */
    public boolean deleted() {
        return newValue == null;
    }
}

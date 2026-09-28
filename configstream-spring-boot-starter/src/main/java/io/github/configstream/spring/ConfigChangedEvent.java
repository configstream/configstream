package io.github.configstream.spring;

import io.github.configstream.api.Property;
import java.util.Objects;

/**
 * Published as a Spring application event whenever a property's value changes after startup.
 * Listen with {@code @EventListener}:
 *
 * <pre>{@code
 * @EventListener
 * void onConfigChanged(ConfigChangedEvent event) {
 *     if (event.isFor(Feature.FUNDS_LIMIT)) { ... config.get(Feature.FUNDS_LIMIT) ... }
 * }
 * }</pre>
 *
 * <p>Listeners run on configstream's change-stream thread, so a slow listener delays later updates.
 * Hand long-running work off to another thread (for example with {@code @Async}).
 *
 * @param key      the property that changed
 * @param oldValue the previous value ({@link Boolean}, {@link Integer}, {@link java.math.BigDecimal} or
 *                 {@link String}), or {@code null} if the property was just created
 * @param newValue the new value, or {@code null} if the property was deleted
 */
public record ConfigChangedEvent(String key, Object oldValue, Object newValue) {

    public ConfigChangedEvent {
        Objects.requireNonNull(key, "key");
        if (Objects.equals(oldValue, newValue)) {
            throw new IllegalArgumentException("oldValue and newValue are equal; nothing changed");
        }
    }

    /** Whether this change is to {@code property}. */
    public boolean isFor(Property<?> property) {
        return key.equals(property.key());
    }

    public boolean isAdded() {
        return oldValue == null;
    }

    public boolean isDeleted() {
        return newValue == null;
    }
}

package io.github.configstream.spring;

import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyType;
import java.util.Objects;

/**
 * Published as a Spring application event whenever a property's value changes after startup.
 * Listen with {@code @EventListener}:
 *
 * <pre>{@code
 * @EventListener
 * void onConfigChanged(ConfigChangedEvent event) {
 *     if (event.key().equals("feature.funds.limit")) { ... fundsProperties.getLimit() ... }
 * }
 * }</pre>
 *
 * <p>By the time listeners run, the {@link LiveConfig} class already returns the new value.
 * Listeners run on configstream's change-stream thread, so a slow listener delays later updates.
 * Hand long-running work off to another thread (for example with {@code @Async}).
 *
 * <p>Events are published for every stored property, including one stored with another type by a different version
 * of the service; check {@link #type()} if that matters.
 *
 * @param key      the property that changed
 * @param type     the property's type; with the key, it identifies the property
 * @param oldValue the previous value ({@link Boolean}, {@link Integer}, {@link java.math.BigDecimal} or
 *                 {@link String}), or {@code null} if the property was just created
 * @param newValue the new value, or {@code null} if the property was deleted
 */
public record ConfigChangedEvent(String key, PropertyType type, Object oldValue, Object newValue) {

    public ConfigChangedEvent {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        if (Objects.equals(oldValue, newValue)) {
            throw new IllegalArgumentException("oldValue and newValue are equal; nothing changed");
        }
    }

    public PropertyId id() {
        return PropertyId.of(key, type);
    }

    public boolean isAdded() {
        return oldValue == null;
    }

    public boolean isDeleted() {
        return newValue == null;
    }
}

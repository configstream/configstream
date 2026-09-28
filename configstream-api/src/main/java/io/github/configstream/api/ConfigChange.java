package io.github.configstream.api;

import java.util.Objects;

/**
 * A single change to one property, as reported by a {@link ConfigChangeSource}.
 *
 * @param type  whether the property was created/updated or removed
 * @param key   the property key, e.g. {@code feature.funds.enabled}
 * @param value the new value for {@link Type#UPSERT}; always {@code null} for {@link Type#DELETE}
 */
public record ConfigChange(Type type, String key, ConfigValue value) {

    public enum Type { UPSERT, DELETE }

    public ConfigChange {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        if (type == Type.UPSERT) {
            Objects.requireNonNull(value, "value is required for UPSERT");
        } else if (value != null) {
            throw new IllegalArgumentException("value must be null for DELETE");
        }
    }

    public static ConfigChange upsert(String key, ConfigValue value) {
        return new ConfigChange(Type.UPSERT, key, value);
    }

    public static ConfigChange delete(String key) {
        return new ConfigChange(Type.DELETE, key, null);
    }
}

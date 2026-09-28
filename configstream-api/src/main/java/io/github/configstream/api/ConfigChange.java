package io.github.configstream.api;

import java.util.Objects;

/**
 * A single change to one property, as reported by a {@link ConfigChangeSource}.
 *
 * @param type  whether the property was created/updated or removed
 * @param id    the property's key and type
 * @param value the new value for {@link Type#UPSERT} (of the property's type); always {@code null} for
 *              {@link Type#DELETE}
 */
public record ConfigChange(Type type, PropertyId id, ConfigValue value) {

    public enum Type { UPSERT, DELETE }

    public ConfigChange {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type == Type.UPSERT) {
            Objects.requireNonNull(value, "value is required for UPSERT");
            if (value.type() != id.type()) {
                throw new IllegalArgumentException("a " + value.type().typeName() + " value can't belong to " + id);
            }
        } else if (value != null) {
            throw new IllegalArgumentException("value must be null for DELETE");
        }
    }

    public static ConfigChange upsert(PropertyId id, ConfigValue value) {
        return new ConfigChange(Type.UPSERT, id, value);
    }

    public static ConfigChange delete(PropertyId id) {
        return new ConfigChange(Type.DELETE, id, null);
    }
}

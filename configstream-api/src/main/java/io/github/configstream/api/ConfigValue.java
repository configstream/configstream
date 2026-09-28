package io.github.configstream.api;

import java.util.Objects;

/**
 * A property's current value together with its type, as held in the store and the cache.
 *
 * @param type  the property's type
 * @param value the value, an instance of {@link PropertyType#javaType() type.javaType()}
 */
public record ConfigValue(PropertyType type, Object value) {

    public ConfigValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        if (!type.javaType().isInstance(value)) {
            throw new IllegalArgumentException("A " + type.typeName() + " value must be a "
                    + type.javaType().getSimpleName() + ", not " + value.getClass().getSimpleName());
        }
    }

    /** The value as text, as shown in the admin app and recorded in the history. */
    public String text() {
        return type.format(value);
    }

    /** Whether {@code other} holds the same type and value (decimals compared numerically). */
    public boolean sameAs(ConfigValue other) {
        return other != null && type == other.type && type.sameValue(value, other.value);
    }
}

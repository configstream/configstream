package io.github.configstream.api;

import java.util.Objects;

/**
 * What identifies a property: its key and its type together. The same key can exist once per type, so changing a
 * property's type in code creates a new property next to the old one instead of changing it. During a rolling or
 * blue-green deploy, old instances keep the old type and new ones the new type; the old one becomes an orphan once no
 * running instance declares it.
 *
 * <p>Every store keeps this identity: MongoDB as {@code _id: {key, type}}, a relational database as a primary key
 * on {@code (key, type)}.
 */
public record PropertyId(String key, PropertyType type) {

    public PropertyId {
        PropertyKeys.requireValid(key);
        Objects.requireNonNull(type, "type");
    }

    public static PropertyId of(String key, PropertyType type) {
        return new PropertyId(key, type);
    }

    @Override
    public String toString() {
        return key + " (" + type.typeName() + ")";
    }
}

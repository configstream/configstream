package io.github.configstream.api;

import java.util.Objects;

/**
 * One property as declared in the manifest.
 *
 * @param key          the property key, e.g. {@code feature.funds.limit}
 * @param type         its type
 * @param initialValue the value it is created with (an instance of the type's Java type)
 * @param description  optional explanation for people; may be {@code null}
 */
public record PropertyDeclaration(String key, PropertyType type, Object initialValue, String description) {

    public PropertyDeclaration {
        PropertyKeys.requireValid(key);
        Objects.requireNonNull(type, "type");
        initialValue = new ConfigValue(type, initialValue).value();
    }

    public ConfigValue initial() {
        return new ConfigValue(type, initialValue);
    }

    PropertyDeclaration withInitialValue(Object value) {
        return new PropertyDeclaration(key, type, value, description);
    }
}

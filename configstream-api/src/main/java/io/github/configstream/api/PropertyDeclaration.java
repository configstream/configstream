package io.github.configstream.api;

import java.util.Objects;

/**
 * A live property as an application declares it: its key, its type, the value it is created with and an optional
 * description. Declarations come from the application's {@code @LiveConfig} classes.
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

    public PropertyId id() {
        return new PropertyId(key, type);
    }

    public ConfigValue initial() {
        return new ConfigValue(type, initialValue);
    }
}

package io.github.configstream.api;

import java.util.Objects;

/**
 * A property declared in the manifest, as used in code: its key, its Java type and its initial value.
 * Applications don't write these by hand; they are generated from {@code configstream.yml}, so a misspelled key
 * or a value read as the wrong type doesn't compile.
 *
 * <pre>{@code
 * public static final Property<Integer> FUNDS_LIMIT = Property.of("feature.funds.limit", Integer.class, 3);
 *
 * int limit = config.get(Feature.FUNDS_LIMIT);
 * }</pre>
 *
 * @param <T> {@link Boolean}, {@link Integer}, {@link java.math.BigDecimal} or {@link String}
 */
public final class Property<T> {

    private final String key;
    private final Class<T> javaType;
    private final PropertyType type;
    private final T initialValue;

    private Property(String key, Class<T> javaType, T initialValue) {
        this.key = PropertyKeys.requireValid(key);
        this.javaType = Objects.requireNonNull(javaType, "javaType");
        this.type = PropertyType.forJavaType(javaType);
        this.initialValue = Objects.requireNonNull(initialValue, "initialValue");
    }

    public static <T> Property<T> of(String key, Class<T> javaType, T initialValue) {
        return new Property<>(key, javaType, initialValue);
    }

    public String key() {
        return key;
    }

    public Class<T> javaType() {
        return javaType;
    }

    public PropertyType type() {
        return type;
    }

    /** The value the manifest declares; the property is created with it, and it is the fallback if the value is missing. */
    public T initialValue() {
        return initialValue;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Property<?> p && key.equals(p.key) && type == p.type && initialValue.equals(p.initialValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, type, initialValue);
    }

    @Override
    public String toString() {
        return key + " (" + type.typeName() + ")";
    }
}

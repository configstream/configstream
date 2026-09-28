package io.github.configstream.api;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * The type of a property, declared in the manifest. A property's type never changes: to change it, declare
 * the property under a new key.
 *
 * <p>Values are held as {@link Boolean}, {@link Integer}, {@link BigDecimal} or {@link String}.
 */
public enum PropertyType {

    BOOLEAN("boolean", Boolean.class),
    INT("int", Integer.class),
    DECIMAL("decimal", BigDecimal.class),
    STRING("string", String.class);

    private final String typeName;
    private final Class<?> javaType;

    PropertyType(String typeName, Class<?> javaType) {
        this.typeName = typeName;
        this.javaType = javaType;
    }

    /** The name used in the manifest and stored in the database, e.g. {@code "int"}. */
    public String typeName() {
        return typeName;
    }

    public Class<?> javaType() {
        return javaType;
    }

    /** The type named {@code name} (as written in the manifest), or an exception listing the valid names. */
    public static PropertyType fromName(String name) {
        for (PropertyType type : values()) {
            if (type.typeName.equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown property type '" + name + "'. Use one of: boolean, int, decimal, string.");
    }

    /** The type whose values are held as {@code javaType}. */
    public static PropertyType forJavaType(Class<?> javaType) {
        for (PropertyType type : values()) {
            if (type.javaType.equals(javaType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported property type " + javaType.getName()
                + ". Use Boolean, Integer, BigDecimal or String.");
    }

    /**
     * Parses a value typed by a person, e.g. in the admin app. Strict: {@code "3.5"} or {@code "abc"} is not an int,
     * and a boolean must be {@code true} or {@code false} (any case).
     *
     * @throws InvalidConfigValueException if {@code text} is not a valid value of this type
     */
    public Object parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim();
        try {
            return switch (this) {
                case BOOLEAN -> switch (trimmed.toLowerCase(Locale.ROOT)) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> throw invalid(text);
                };
                case INT -> Integer.valueOf(trimmed);
                case DECIMAL -> new BigDecimal(trimmed);
                case STRING -> text;
            };
        } catch (NumberFormatException e) {
            throw invalid(text);
        }
    }

    /**
     * Converts a value read from storage, where it may have been written by hand, e.g. a whole number stored as a
     * double by the MongoDB shell, or a number typed as text.
     *
     * @throws InvalidConfigValueException if {@code raw} can't represent a value of this type without losing
     *     information
     */
    public Object coerce(Object raw) {
        Objects.requireNonNull(raw, "raw");
        if (javaType.isInstance(raw)) {
            return raw;
        }
        if (raw instanceof String s) {
            return parse(s);
        }
        switch (this) {
            case INT -> {
                if (raw instanceof Number n) {
                    BigDecimal exact = toBigDecimal(n);
                    try {
                        return exact.intValueExact();
                    } catch (ArithmeticException e) {
                        throw invalid(raw.toString());
                    }
                }
            }
            case DECIMAL -> {
                if (raw instanceof Number n) {
                    return toBigDecimal(n);
                }
            }
            case STRING -> {
                if (raw instanceof Number || raw instanceof Boolean) {
                    return raw.toString();
                }
            }
            case BOOLEAN -> {
            }
        }
        throw invalid(raw.toString());
    }

    /** The value as text, e.g. for the admin app and the history. Decimals are written without exponents. */
    public String format(Object value) {
        return value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value);
    }

    /** Whether two values of this type are the same value. Decimals compare numerically, so 1.0 equals 1.00. */
    public boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private InvalidConfigValueException invalid(String text) {
        return new InvalidConfigValueException("'" + text + "' is not a valid " + typeName + ".");
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal d) {
            return d;
        }
        if (n instanceof Double || n instanceof Float) {
            return new BigDecimal(n.toString());
        }
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return BigDecimal.valueOf(n.longValue());
        }
        return new BigDecimal(n.toString());
    }
}

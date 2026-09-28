package io.github.configstream.mongo;

import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyType;
import java.math.BigDecimal;
import org.bson.Document;
import org.bson.types.Decimal128;

/**
 * Converts between property documents and typed values. One document per property:
 * <pre>{ "_id": "feature.funds.limit", "type": "int", "value": 3, "version": 1 }</pre>
 * Values are stored as native BSON: boolean, int32, decimal128 or string.
 */
final class MongoValues {

    static final String TYPE_FIELD = "type";
    static final String VALUE_FIELD = "value";
    static final String VERSION_FIELD = "version";

    private MongoValues() {
    }

    /** The document's declared type, or {@code null} if it has none or names an unknown type. */
    static PropertyType typeOf(Document doc) {
        Object name = doc.get(TYPE_FIELD);
        if (!(name instanceof String s)) {
            return null;
        }
        try {
            return PropertyType.fromName(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The document's value as its declared type.
     *
     * @throws InvalidConfigValueException if the document has no valid type or value, e.g. {@code "abc"} stored
     *     for an int, typically after a direct edit in the database
     */
    static ConfigValue read(Document doc) {
        PropertyType type = typeOf(doc);
        if (type == null) {
            throw new InvalidConfigValueException("it has no valid 'type' (boolean, int, decimal or string).");
        }
        return new ConfigValue(type, type.coerce(javaValue(doc.get(VALUE_FIELD), type)));
    }

    /** The value converted for storage. */
    static Object toBson(ConfigValue value) {
        if (value.value() instanceof BigDecimal d) {
            try {
                return new Decimal128(d);
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new InvalidConfigValueException("'" + value.text() + "' has too many digits to store as a decimal "
                        + "(at most 34 significant digits).");
            }
        }
        return value.value();
    }

    /** The stored value as text for the history, even if it doesn't fit the type, or {@code null} if there is none. */
    static String rawText(Document doc) {
        Object raw = doc.get(VALUE_FIELD);
        if (raw == null) {
            return null;
        }
        return raw instanceof Decimal128 d ? d.toString() : raw instanceof Document embedded ? embedded.toJson() : raw.toString();
    }

    static long versionOf(Document doc) {
        // Documents created outside configstream have no version yet
        return doc != null && doc.get(VERSION_FIELD) instanceof Number n ? n.longValue() : 0;
    }

    private static Object javaValue(Object raw, PropertyType type) {
        if (raw == null) {
            throw new InvalidConfigValueException("it has no value.");
        }
        if (raw instanceof Decimal128 d) {
            try {
                return d.bigDecimalValue();
            } catch (ArithmeticException e) {
                throw new InvalidConfigValueException("'" + d + "' is not a valid " + type.typeName() + ".");
            }
        }
        return raw;
    }
}

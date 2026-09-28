package io.github.configstream.mongo;

import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyKeys;
import io.github.configstream.api.PropertyType;
import java.math.BigDecimal;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.Decimal128;

/**
 * Converts between property documents and typed values. One document per property, identified by its key and type:
 * <pre>{ "_id": { "key": "feature.funds.limit", "type": "int" }, "value": 3, "version": 1 }</pre>
 * The same key with another type is another document, so a type change in the code creates a new property and leaves
 * the old one for the instances still running the old code. Values are stored as native BSON: boolean, int32,
 * decimal128 or string.
 *
 * <p>Earlier versions stored {@code { "_id": "feature.funds.limit", "type": "int", ... }}. Such legacy documents are
 * still read, and {@link MongoConfigWriter} moves them to the current shape the first time it touches their key.
 */
final class MongoValues {

    static final String ID_FIELD = "_id";
    static final String KEY_FIELD = "key";
    static final String TYPE_FIELD = "type";
    static final String VALUE_FIELD = "value";
    static final String VERSION_FIELD = "version";

    private MongoValues() {
    }

    /** The {@code _id} of the property's document. Field order matters: MongoDB compares embedded documents exactly. */
    static Document idOf(PropertyId id) {
        return new Document(KEY_FIELD, id.key()).append(TYPE_FIELD, id.type().typeName());
    }

    /**
     * The property a document belongs to, or {@code null} if its {@code _id} names no valid key and type. A legacy
     * document counts if it has a valid {@code type} field.
     */
    static PropertyId propertyIdOf(Document doc) {
        Object id = doc.get(ID_FIELD);
        if (id instanceof Document compound) {
            return propertyId(compound.get(KEY_FIELD), compound.get(TYPE_FIELD));
        }
        if (id instanceof String) {
            return propertyId(id, doc.get(TYPE_FIELD));
        }
        return null;
    }

    /** The property a compound {@code _id} names, as a change stream reports it for a delete, or {@code null}. */
    static PropertyId propertyIdOf(BsonDocument id) {
        BsonValue key = id.get(KEY_FIELD);
        BsonValue type = id.get(TYPE_FIELD);
        return key != null && key.isString() && type != null && type.isString()
                ? propertyId(key.asString().getValue(), type.asString().getValue()) : null;
    }

    /** The type named in a legacy document's {@code type} field, or {@code null} if it has none or names an unknown type. */
    static PropertyType legacyTypeOf(Document doc) {
        return type(doc.get(TYPE_FIELD));
    }

    /**
     * The document's value as {@code type}.
     *
     * @throws InvalidConfigValueException if the document has no value that fits, e.g. {@code "abc"} stored for an
     *     int, typically after a direct edit in the database
     */
    static ConfigValue read(Document doc, PropertyType type) {
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

    private static PropertyId propertyId(Object key, Object typeName) {
        PropertyType type = type(typeName);
        if (!(key instanceof String k) || type == null || !PropertyKeys.isValid(k)) {
            return null;
        }
        return PropertyId.of(k, type);
    }

    private static PropertyType type(Object name) {
        if (!(name instanceof String s)) {
            return null;
        }
        try {
            return PropertyType.fromName(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

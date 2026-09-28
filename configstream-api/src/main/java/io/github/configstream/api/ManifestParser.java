package io.github.configstream.api;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads the manifest and environment files. Strict on purpose, because mistakes here end up in production:
 * unknown fields, duplicate keys and values of the wrong type are all errors.
 *
 * <p>Manifest ({@code configstream.yml}):
 * <pre>
 * properties:
 *   - key: feature.funds.limit
 *     type: int                    # boolean, int, decimal or string
 *     initialValue: 3
 *     description: Max funds shown  # optional
 * </pre>
 *
 * <p>Environment file ({@code configstream-prod.yml}), which only changes initial values of declared properties:
 * <pre>
 * properties:
 *   feature.funds.limit: 10
 * </pre>
 */
public final class ManifestParser {

    private static final Set<String> PROPERTY_FIELDS = Set.of("key", "type", "initialValue", "description");

    private ManifestParser() {
    }

    /** Parses a manifest. {@code source} names the file in error messages, e.g. {@code configstream.yml}. */
    public static Manifest parse(InputStream in, String source) {
        Object properties = topLevel(in, source).get("properties");
        if (properties == null) {
            return Manifest.of(source, List.of());
        }
        if (!(properties instanceof List<?> list)) {
            throw new ManifestException(source, "'properties' must be a list of properties, each starting with '- key:'.");
        }
        List<PropertyDeclaration> declarations = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            declarations.add(declaration(list.get(i), i + 1, source));
        }
        return Manifest.of(source, declarations);
    }

    /** Applies an environment file's initial values to {@code base}. */
    public static Manifest applyEnvironment(Manifest base, InputStream in, String source) {
        Object properties = topLevel(in, source).get("properties");
        if (properties == null) {
            return base;
        }
        if (!(properties instanceof Map<?, ?> map)) {
            throw new ManifestException(source, "'properties' must map each key to its initial value, "
                    + "e.g. 'feature.funds.limit: 10'.");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((k, raw) -> {
            String key = String.valueOf(k);
            PropertyDeclaration declaration = base.find(key).orElseThrow(() -> new ManifestException(source,
                    "property '" + key + "' is not declared in the base manifest. Environment files can only change "
                            + "initial values; declare new properties in configstream.yml."));
            values.put(key, value(declaration.type(), raw, key, source));
        });
        return base.withInitialValues(values, source);
    }

    private static Map<?, ?> topLevel(InputStream in, String source) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (YAMLException e) {
            throw new ManifestException(source, "not valid YAML: " + e.getMessage(), e);
        }
        if (root == null) {
            return Map.of();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new ManifestException(source, "expected a 'properties:' section at the top.");
        }
        for (Object field : map.keySet()) {
            if (!"properties".equals(field)) {
                throw new ManifestException(source, "unknown top-level field '" + field + "'; only 'properties' is allowed.");
            }
        }
        return map;
    }

    private static PropertyDeclaration declaration(Object item, int position, String source) {
        if (!(item instanceof Map<?, ?> fields)) {
            throw new ManifestException(source, "property #" + position + " must have key, type and initialValue.");
        }
        for (Object field : fields.keySet()) {
            if (!PROPERTY_FIELDS.contains(field)) {
                throw new ManifestException(source, "property #" + position + " has unknown field '" + field
                        + "'; allowed fields are key, type, initialValue and description.");
            }
        }
        Object key = fields.get("key");
        if (!(key instanceof String k) || !PropertyKeys.isValid(k)) {
            throw new ManifestException(source, "property #" + position + " has an invalid key '" + key + "'. Use at least "
                    + "two dot-separated parts, each starting with a letter, e.g. feature.funds.enabled.");
        }
        Object typeName = fields.get("type");
        if (typeName == null) {
            throw new ManifestException(source, "property '" + k + "' has no type; use boolean, int, decimal or string.");
        }
        PropertyType type;
        try {
            type = PropertyType.fromName(String.valueOf(typeName));
        } catch (IllegalArgumentException e) {
            throw new ManifestException(source, "property '" + k + "': " + e.getMessage());
        }
        if (!fields.containsKey("initialValue")) {
            throw new ManifestException(source, "property '" + k + "' has no initialValue.");
        }
        Object description = fields.get("description");
        return new PropertyDeclaration(k, type, value(type, fields.get("initialValue"), k, source),
                description == null ? null : String.valueOf(description));
    }

    /**
     * Converts a YAML value to the property's type. YAML guesses types from the text, so the checks are strict
     * rather than converting: a string must be quoted if it looks like a number or boolean.
     */
    private static Object value(PropertyType type, Object raw, String key, String source) {
        if (raw == null) {
            throw new ManifestException(source, "property '" + key + "' has no value.");
        }
        Object value = switch (type) {
            case BOOLEAN -> raw instanceof Boolean b ? b : null;
            case INT -> raw instanceof Integer i ? i : null;
            case DECIMAL -> raw instanceof String s ? parseDecimal(s)
                    : raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger || raw instanceof Double
                            ? new BigDecimal(raw.toString()) : null;
            case STRING -> raw instanceof String s ? s : null;
        };
        if (value == null) {
            String hint = type == PropertyType.STRING ? " Put it in quotes, e.g. \"" + raw + "\"."
                    : type == PropertyType.INT && (raw instanceof Long || raw instanceof BigInteger) ? " It is too large for an int."
                    : "";
            throw new ManifestException(source, "property '" + key + "' is declared as " + type.typeName() + ", but its value "
                    + describe(raw) + " is not a valid " + type.typeName() + "." + hint);
        }
        return value;
    }

    private static BigDecimal parseDecimal(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(Object raw) {
        return raw instanceof String s ? "\"" + s + "\"" : String.valueOf(raw);
    }
}

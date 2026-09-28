package io.github.configstream.processor;

import io.github.configstream.api.Manifest;
import io.github.configstream.api.PropertyDeclaration;
import io.github.configstream.api.PropertyType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a manifest into Java source: one class per key's first part, one {@code Property} constant per key.
 * {@code feature.funds.limit} becomes {@code Feature.FUNDS_LIMIT}; {@code checkout-page.maxItems} becomes
 * {@code CheckoutPage.MAX_ITEMS}.
 */
final class ConstantsWriter {

    static final String GENERATOR = ConfigStreamManifestProcessor.class.getName();

    private ConstantsWriter() {
    }

    /** A generated source file: the class's simple name and its full text. */
    record GeneratedClass(String simpleName, String source) {
    }

    /**
     * @throws IllegalArgumentException if two keys would produce the same class or constant name; the message names
     *     both keys
     */
    static List<GeneratedClass> write(Manifest manifest, String packageName, String manifestName) {
        Map<String, List<PropertyDeclaration>> byClass = new LinkedHashMap<>();
        Map<String, String> classPrefixes = new LinkedHashMap<>();
        Map<String, String> constants = new LinkedHashMap<>();
        for (PropertyDeclaration declaration : manifest.properties()) {
            String prefix = declaration.key().substring(0, declaration.key().indexOf('.'));
            String className = className(prefix);
            String existingPrefix = classPrefixes.putIfAbsent(className, prefix);
            if (existingPrefix != null && !existingPrefix.equals(prefix)) {
                throw new IllegalArgumentException("keys starting with '" + existingPrefix + ".' and '" + prefix
                        + ".' would both generate class " + className + ". Rename one of them.");
            }
            String qualified = className + "." + constantName(declaration.key());
            String clash = constants.putIfAbsent(qualified, declaration.key());
            if (clash != null) {
                throw new IllegalArgumentException("'" + clash + "' and '" + declaration.key() + "' would both generate "
                        + qualified + ". Rename one of them.");
            }
            byClass.computeIfAbsent(className, c -> new ArrayList<>()).add(declaration);
        }
        List<GeneratedClass> classes = new ArrayList<>();
        byClass.forEach((className, declarations) -> classes.add(new GeneratedClass(className,
                source(packageName, className, classPrefixes.get(className), declarations, manifestName))));
        return classes;
    }

    /** {@code feature} gives {@code Feature}; {@code checkout-page} and {@code checkout_page} give {@code CheckoutPage}. */
    static String className(String prefix) {
        StringBuilder name = new StringBuilder();
        for (String word : words(prefix)) {
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    /** Everything after the first part, upper case: {@code feature.funds.maxItems} gives {@code FUNDS_MAX_ITEMS}. */
    static String constantName(String key) {
        String rest = key.substring(key.indexOf('.') + 1);
        List<String> words = new ArrayList<>();
        for (String segment : rest.split("\\.")) {
            words.addAll(words(segment));
        }
        return String.join("_", words).toUpperCase(Locale.ROOT);
    }

    /** Splits on '-', '_' and lower-to-upper case changes: {@code maxItems-v2} gives {@code max, Items, v2}. */
    private static List<String> words(String segment) {
        List<String> words = new ArrayList<>();
        for (String part : segment.split("[-_]")) {
            if (!part.isEmpty()) {
                for (String word : part.split("(?<=[a-z0-9])(?=[A-Z])")) {
                    words.add(word);
                }
            }
        }
        return words;
    }

    private static String source(String packageName, String className, String prefix,
            List<PropertyDeclaration> declarations, String manifestName) {
        StringBuilder out = new StringBuilder();
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("import io.github.configstream.api.Property;\n");
        if (declarations.stream().anyMatch(d -> d.type() == PropertyType.DECIMAL)) {
            out.append("import java.math.BigDecimal;\n");
        }
        out.append("import javax.annotation.processing.Generated;\n\n");
        out.append("/**\n * Properties declared in {@code ").append(javadoc(manifestName)).append("} under {@code ")
                .append(javadoc(prefix)).append(".*}. Generated from the manifest: edit the manifest, not this class.\n */\n");
        out.append("@Generated(\"").append(GENERATOR).append("\")\n");
        out.append("public final class ").append(className).append(" {\n");
        for (PropertyDeclaration declaration : declarations) {
            String javaType = declaration.type().javaType().getSimpleName();
            out.append("\n    /**\n");
            if (declaration.description() != null && !declaration.description().isBlank()) {
                out.append("     * ").append(javadoc(declaration.description().strip())).append("\n     *\n");
            }
            out.append("     * <p>{@code ").append(javadoc(declaration.key())).append("}, ")
                    .append(declaration.type().typeName()).append(", initially <code>")
                    .append(javadoc(declaration.type().format(declaration.initialValue()))).append("</code>.\n     */\n");
            out.append("    public static final Property<").append(javaType).append("> ")
                    .append(constantName(declaration.key())).append(" =\n            Property.of(")
                    .append(stringLiteral(declaration.key())).append(", ").append(javaType).append(".class, ")
                    .append(literal(declaration.type(), declaration.initialValue())).append(");\n");
        }
        out.append("\n    private ").append(className).append("() {\n    }\n}\n");
        return out.toString();
    }

    private static String literal(PropertyType type, Object value) {
        return switch (type) {
            case BOOLEAN, INT -> value.toString();
            case DECIMAL -> "new BigDecimal(\"" + ((BigDecimal) value).toPlainString() + "\")";
            case STRING -> stringLiteral((String) value);
        };
    }

    static String stringLiteral(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Text that can't end the comment or be read as HTML or a Javadoc tag. */
    private static String javadoc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("*/", "*&#47;").replace("@", "&#64;").replace("{", "&#123;").replace("}", "&#125;")
                .replace("\\u", "\\&#117;").replace("\n", " ").replace("\r", " ");
    }
}

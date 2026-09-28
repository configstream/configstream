package io.github.configstream.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The properties an application declares, read from {@code configstream.yml}, optionally with the initial values
 * of an environment file such as {@code configstream-prod.yml} applied. It is the only way properties are created:
 * on startup each declared property that is missing from the store is inserted with its initial value.
 */
public final class Manifest {

    private static final Manifest EMPTY = new Manifest(Map.of(), Map.of());

    private final Map<String, PropertyDeclaration> properties;
    private final Map<String, String> initialValueSources;

    private Manifest(Map<String, PropertyDeclaration> properties, Map<String, String> initialValueSources) {
        this.properties = Collections.unmodifiableMap(properties);
        this.initialValueSources = Collections.unmodifiableMap(initialValueSources);
    }

    /** A manifest that declares nothing, for applications without {@code configstream.yml}. */
    public static Manifest empty() {
        return EMPTY;
    }

    /**
     * @param source the file the declarations came from, e.g. {@code configstream.yml}
     * @throws ManifestException if two declarations share a key
     */
    public static Manifest of(String source, List<PropertyDeclaration> declarations) {
        Map<String, PropertyDeclaration> byKey = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (PropertyDeclaration declaration : declarations) {
            if (byKey.putIfAbsent(declaration.key(), declaration) != null) {
                throw new ManifestException(source, "property '" + declaration.key() + "' is declared more than once.");
            }
            sources.put(declaration.key(), source);
        }
        return new Manifest(byKey, sources);
    }

    /** Every declared property, in the order the manifest lists them. */
    public List<PropertyDeclaration> properties() {
        return List.copyOf(properties.values());
    }

    public Optional<PropertyDeclaration> find(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public boolean declares(String key) {
        return properties.containsKey(key);
    }

    /** The file this property's initial value came from: the base manifest, or an environment file overriding it. */
    public String initialValueSource(String key) {
        return initialValueSources.get(key);
    }

    /**
     * This manifest with some initial values replaced, as an environment file does. Keys and types stay as declared.
     *
     * @param values initial values by key; each key must be declared, and each value must already be of its type
     * @param source the environment file, e.g. {@code configstream-prod.yml}
     */
    public Manifest withInitialValues(Map<String, Object> values, String source) {
        Map<String, PropertyDeclaration> updated = new LinkedHashMap<>(properties);
        Map<String, String> sources = new LinkedHashMap<>(initialValueSources);
        values.forEach((key, value) -> {
            PropertyDeclaration declaration = properties.get(key);
            if (declaration == null) {
                throw new ManifestException(source, "property '" + key + "' is not declared in the base manifest. "
                        + "Environment files can only change initial values; declare new properties in configstream.yml.");
            }
            updated.put(key, declaration.withInitialValue(value));
            sources.put(key, source);
        });
        return new Manifest(updated, sources);
    }
}

package io.github.configstream.spring;

import io.github.configstream.api.ConfigCache;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.Manifest;
import io.github.configstream.api.Property;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read access to live property values. Inject it anywhere in the application; values are served from memory and
 * reflect changes in the store within about a second, with no restart.
 *
 * <p>Properties are read through the constants generated from the manifest, so a misspelled key or a value read as
 * the wrong type doesn't compile:
 * <pre>{@code
 * int limit = config.get(Feature.FUNDS_LIMIT);
 * }</pre>
 *
 * <p>The stored value always wins. The manifest's initial value is used only if the property is missing from the
 * store (for example, deleted by hand) or its stored value doesn't fit its type.
 */
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigCache cache;
    private final Manifest manifest;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ConfigService(ConfigCache cache, Manifest manifest) {
        this.cache = cache;
        this.manifest = manifest;
    }

    public <T> T get(Property<T> property) {
        ConfigValue value = cache.get(property.key()).orElse(null);
        if (value != null && value.type() == property.type()) {
            return property.javaType().cast(value.value());
        }
        if (value != null) {
            warnOnce(property.key(), "Property '{}' is stored as {} but read as {}; using its initial value. "
                    + "Regenerate the property constants from configstream.yml.", value.type().typeName(), property.type().typeName());
        } else if (!manifest.declares(property.key())) {
            warnOnce(property.key(), "Property '{}' is not declared in this application's manifest; using its initial "
                    + "value. Add it to configstream.yml.");
        }
        return initialValue(property);
    }

    /** Every property as this instance currently sees it. */
    Map<String, ConfigValue> values() {
        return cache.getAll();
    }

    Manifest manifest() {
        return manifest;
    }

    /** The initial value for this environment (an environment file may override the generated constant's). */
    private <T> T initialValue(Property<T> property) {
        return manifest.find(property.key())
                .filter(declaration -> declaration.type() == property.type())
                .map(declaration -> property.javaType().cast(declaration.initialValue()))
                .orElse(property.initialValue());
    }

    private void warnOnce(String key, String message, Object... args) {
        if (warned.add(key)) {
            Object[] all = new Object[args.length + 1];
            all[0] = key;
            System.arraycopy(args, 0, all, 1, args.length);
            log.warn(message, all);
        }
    }
}

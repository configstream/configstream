package io.github.configstream.spring;

import io.github.configstream.api.ConfigCache;
import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigValue;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * The live values of this application's properties, held in memory and kept current by the change stream.
 * Applications read them through their {@link LiveConfig} classes rather than through this service.
 *
 * <p>The stored values are loaded as soon as this service is created, so every bean sees them from the start. Watching
 * for changes begins once all beans exist, after the {@code @LiveConfig} classes have created their missing
 * properties, so creating them doesn't count as a change.
 */
public class ConfigService implements SmartInitializingSingleton {

    private final ConfigCache cache;
    private final ConfigChangeSource source;
    private final EventPublishingListener listener;

    ConfigService(ConfigCache cache, ConfigChangeSource source, EventPublishingListener listener) {
        this.cache = cache;
        this.source = source;
        this.listener = listener;
        listener.onSnapshot(source.loadInitial());
    }

    /** Starts watching for changes; anything changed since the first load is reported as a change. */
    @Override
    public void afterSingletonsInstantiated() {
        source.start(listener);
    }

    /** Every property as this instance currently sees it. */
    public Map<String, ConfigValue> values() {
        return cache.getAll();
    }

    /** The property's current value, or {@code null} if it is missing or its stored value doesn't fit its type. */
    public ConfigValue value(String key) {
        return cache.get(key).orElse(null);
    }

    /** Records a property just created in the store, unless the cache already has a value for it. */
    void seedIfAbsent(String key, ConfigValue value) {
        cache.putIfAbsent(key, value);
    }
}

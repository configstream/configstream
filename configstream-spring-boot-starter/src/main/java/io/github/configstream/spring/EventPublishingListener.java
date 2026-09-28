package io.github.configstream.spring;

import io.github.configstream.api.ConfigCache;
import io.github.configstream.api.ConfigChange;
import io.github.configstream.api.ConfigChangeListener;
import io.github.configstream.api.ConfigValue;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Keeps the {@link ConfigCache} current and publishes a {@link ConfigChangedEvent} for every value
 * that actually changes. The first snapshot (the startup load) publishes nothing; later snapshots
 * (resyncs) publish one event per property that differs from what the cache held.
 *
 * <p>Relies on the {@link ConfigChangeListener} guarantee that callbacks never run concurrently,
 * so reading the old value and then writing the new one can't race.
 */
class EventPublishingListener implements ConfigChangeListener {

    private final ConfigCache cache;
    private final ApplicationEventPublisher publisher;
    private boolean initialized;

    EventPublishingListener(ConfigCache cache, ApplicationEventPublisher publisher) {
        this.cache = cache;
        this.publisher = publisher;
    }

    @Override
    public void onSnapshot(Map<String, ConfigValue> snapshot) {
        Map<String, ConfigValue> before = cache.getAll();
        cache.onSnapshot(snapshot);
        if (!initialized) {
            initialized = true;
            return;
        }
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(snapshot.keySet());
        for (String key : keys) {
            publishIfChanged(key, before.get(key), snapshot.get(key));
        }
    }

    @Override
    public void onChange(ConfigChange change) {
        ConfigValue before = cache.get(change.key()).orElse(null);
        cache.onChange(change);
        // Duplicates are normal (e.g. an event replayed right after a snapshot), so compare values.
        publishIfChanged(change.key(), before, change.value());
    }

    private void publishIfChanged(String key, ConfigValue oldValue, ConfigValue newValue) {
        boolean same = oldValue == null ? newValue == null : oldValue.sameAs(newValue);
        if (!same) {
            publisher.publishEvent(new ConfigChangedEvent(key,
                    oldValue == null ? null : oldValue.value(), newValue == null ? null : newValue.value()));
        }
    }
}

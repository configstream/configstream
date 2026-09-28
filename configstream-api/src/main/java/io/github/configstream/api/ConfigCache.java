package io.github.configstream.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory view of the config store, kept current by a {@link ConfigChangeSource}.
 * Reads never touch the database.
 */
public class ConfigCache implements ConfigChangeListener {

    private final Map<PropertyId, ConfigValue> entries = new ConcurrentHashMap<>();

    public Optional<ConfigValue> get(PropertyId id) {
        return Optional.ofNullable(entries.get(id));
    }

    /** Immutable point-in-time copy of all entries. */
    public Map<PropertyId, ConfigValue> getAll() {
        return Map.copyOf(entries);
    }

    /** Adds the entry unless there already is one, e.g. for a property just created, before the source reports it. */
    public void putIfAbsent(PropertyId id, ConfigValue value) {
        entries.putIfAbsent(id, value);
    }

    @Override
    public void onSnapshot(Map<PropertyId, ConfigValue> snapshot) {
        // Update in place rather than swapping maps, so readers never see an empty cache mid-reload.
        entries.putAll(snapshot);
        entries.keySet().retainAll(snapshot.keySet());
    }

    @Override
    public void onChange(ConfigChange change) {
        switch (change.type()) {
            case UPSERT -> entries.put(change.id(), change.value());
            case DELETE -> entries.remove(change.id());
        }
    }
}

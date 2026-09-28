package io.github.configstream.spring;

import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyNotFoundException;
import io.github.configstream.api.PropertyType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory writer and history following the same rules as the Mongo ones. */
class FakeConfigStore implements ConfigWriter, ConfigHistory {

    static final Instant CHANGED_AT = Instant.parse("2026-09-25T10:00:00Z");

    final Map<String, ConfigValue> values = new ConcurrentHashMap<>();
    final List<ConfigHistoryEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public synchronized PropertyType createIfAbsent(String key, ConfigValue initial, String changedBy, String comment) {
        ConfigValue existing = values.get(key);
        if (existing != null) {
            return existing.type();
        }
        values.put(key, initial);
        record(key, null, initial.text(), changedBy, comment);
        return initial.type();
    }

    @Override
    public synchronized Optional<ConfigHistoryEntry> write(ConfigUpdate update) {
        ConfigValue old = values.get(update.key());
        if (old == null) {
            throw new PropertyNotFoundException(update.key());
        }
        if (update.type() != null && update.type() != old.type()) {
            throw new InvalidConfigValueException(InvalidConfigValueException.TYPE_CHANGE_NOT_ALLOWED);
        }
        ConfigValue value = new ConfigValue(old.type(), old.type().parse(update.value()));
        if (value.sameAs(old)) {
            return Optional.empty();
        }
        values.put(update.key(), value);
        return Optional.of(record(update.key(), old.text(), value.text(), update.changedBy(), update.comment()));
    }

    @Override
    public synchronized Optional<ConfigHistoryEntry> delete(ConfigDeletion deletion) {
        ConfigValue old = values.remove(deletion.key());
        if (old == null) {
            return Optional.empty();
        }
        return Optional.of(record(deletion.key(), old.text(), null, deletion.changedBy(), deletion.comment()));
    }

    @Override
    public List<ConfigHistoryEntry> history(String key, int limit) {
        return entries.stream().filter(e -> e.key().equals(key)).limit(limit).toList();
    }

    private ConfigHistoryEntry record(String key, String oldValue, String newValue, String changedBy, String comment) {
        long version = entries.stream().filter(e -> e.key().equals(key)).count() + 1;
        ConfigHistoryEntry entry = new ConfigHistoryEntry(key, version, oldValue, newValue, changedBy, CHANGED_AT, comment);
        entries.add(0, entry);
        return entry;
    }
}

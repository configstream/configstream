package io.github.configstream.spring;

import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory writer and history following the same rules as the Mongo ones. */
class FakeConfigStore implements ConfigWriter, ConfigHistory {

    static final Instant CHANGED_AT = Instant.parse("2026-09-25T10:00:00Z");

    final Map<PropertyId, ConfigValue> values = new ConcurrentHashMap<>();
    final List<ConfigHistoryEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public synchronized boolean createIfAbsent(PropertyId id, ConfigValue initial, String changedBy, String comment) {
        if (values.containsKey(id)) {
            return false;
        }
        values.put(id, initial);
        record(id, null, initial.text(), changedBy, comment);
        return true;
    }

    @Override
    public synchronized Optional<ConfigHistoryEntry> write(ConfigUpdate update) {
        ConfigValue old = values.get(update.id());
        if (old == null) {
            throw new PropertyNotFoundException(update.id());
        }
        ConfigValue value = new ConfigValue(old.type(), old.type().parse(update.value()));
        if (value.sameAs(old)) {
            return Optional.empty();
        }
        values.put(update.id(), value);
        return Optional.of(record(update.id(), old.text(), value.text(), update.changedBy(), update.comment()));
    }

    @Override
    public synchronized Optional<ConfigHistoryEntry> delete(ConfigDeletion deletion) {
        ConfigValue old = values.remove(deletion.id());
        if (old == null) {
            return Optional.empty();
        }
        return Optional.of(record(deletion.id(), old.text(), null, deletion.changedBy(), deletion.comment()));
    }

    @Override
    public List<ConfigHistoryEntry> history(PropertyId id, int limit) {
        return entries.stream().filter(e -> e.id().equals(id)).limit(limit).toList();
    }

    private ConfigHistoryEntry record(PropertyId id, String oldValue, String newValue, String changedBy, String comment) {
        long version = entries.stream().filter(e -> e.id().equals(id)).count() + 1;
        ConfigHistoryEntry entry = new ConfigHistoryEntry(id.key(), id.type(), version, oldValue, newValue, changedBy,
                CHANGED_AT, comment);
        entries.add(0, entry);
        return entry;
    }
}

package io.github.configstream.mongo;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyNotFoundException;
import io.github.configstream.api.PropertyType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ConfigWriter} for the document shape read by {@link MongoChangeStreamSource} (see {@link MongoValues}).
 *
 * <p>Each change runs in a transaction that reads the current document, writes the new one and appends a
 * {@link MongoConfigHistory} entry, so a property and its history can never disagree. Concurrent changes to the same
 * property conflict inside MongoDB and are retried, which keeps versions gap-free and in order.
 *
 * <p>Versions continue across a delete: a property created again later (for example after rolling back to a version
 * of the application that declares it) carries on from its last recorded version.
 *
 * <p>Legacy documents (a string {@code _id}) are moved to the current shape, with their value, version and history,
 * the first time a change touches their key.
 *
 * <p>Changes made directly in the database update caches as usual but are not recorded in the history.
 */
public class MongoConfigWriter implements ConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(MongoConfigWriter.class);

    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final MongoConfigHistory history;

    /** {@code collection} and {@code history}'s collection must both belong to {@code client}. */
    public MongoConfigWriter(MongoClient client, MongoCollection<Document> collection, MongoConfigHistory history) {
        this.client = client;
        this.collection = collection;
        this.history = history;
    }

    @Override
    public boolean createIfAbsent(PropertyId id, ConfigValue initial, String changedBy, String comment) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(changedBy, "changedBy");
        if (initial.type() != id.type()) {
            throw new IllegalArgumentException("a " + initial.type().typeName() + " value can't belong to " + id);
        }
        moveLegacy(id.key(), id.type());
        if (collection.find(byId(id)).first() != null) {
            return false;
        }
        Object stored = MongoValues.toBson(initial);
        try (ClientSession session = client.startSession()) {
            // withTransaction retries the whole body on transient errors such as write conflicts, so when several
            // instances start at once, all but one find the property already there on the retry
            return session.withTransaction(() -> {
                if (collection.find(session, byId(id)).first() != null) {
                    return false;
                }
                long version = history.latestVersion(session, id) + 1;
                collection.insertOne(session, new Document(MongoValues.ID_FIELD, MongoValues.idOf(id))
                        .append(MongoValues.VALUE_FIELD, stored)
                        .append(MongoValues.VERSION_FIELD, version));
                history.insert(session, new ConfigHistoryEntry(id.key(), id.type(), version, null, initial.text(),
                        changedBy, now(), comment));
                return true;
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            return false; // another instance created it between our read and our insert
        }
    }

    @Override
    public Optional<ConfigHistoryEntry> write(ConfigUpdate update) {
        Objects.requireNonNull(update, "update");
        moveLegacy(update.id().key(), null);
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> writeInTransaction(session, update));
        }
    }

    private Optional<ConfigHistoryEntry> writeInTransaction(ClientSession session, ConfigUpdate update) {
        PropertyId id = update.id();
        Document current = collection.find(session, byId(id)).first();
        if (current == null) {
            throw new PropertyNotFoundException(id);
        }
        ConfigValue newValue = new ConfigValue(id.type(), id.type().parse(update.value()));
        Object stored = MongoValues.toBson(newValue);
        ConfigValue oldValue = readOrNull(current, id.type());
        if (newValue.sameAs(oldValue)) {
            return Optional.empty();
        }
        long version = MongoValues.versionOf(current) + 1;
        collection.updateOne(session, byId(id),
                combine(set(MongoValues.VALUE_FIELD, stored), set(MongoValues.VERSION_FIELD, version)));
        return Optional.of(record(session, new ConfigHistoryEntry(id.key(), id.type(), version,
                oldValue != null ? oldValue.text() : MongoValues.rawText(current), newValue.text(),
                update.changedBy(), now(), update.comment())));
    }

    @Override
    public Optional<ConfigHistoryEntry> delete(ConfigDeletion deletion) {
        Objects.requireNonNull(deletion, "deletion");
        moveLegacy(deletion.id().key(), null);
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> deleteInTransaction(session, deletion));
        }
    }

    private Optional<ConfigHistoryEntry> deleteInTransaction(ClientSession session, ConfigDeletion deletion) {
        PropertyId id = deletion.id();
        Document current = collection.find(session, byId(id)).first();
        if (current == null) {
            return Optional.empty();
        }
        ConfigValue oldValue = readOrNull(current, id.type());
        long version = MongoValues.versionOf(current) + 1;
        collection.deleteOne(session, byId(id));
        return Optional.of(record(session, new ConfigHistoryEntry(id.key(), id.type(), version,
                oldValue != null ? oldValue.text() : MongoValues.rawText(current), null,
                deletion.changedBy(), now(), deletion.comment())));
    }

    /**
     * Moves the key's legacy document, if any, to the current shape, keeping its value and version and giving its
     * history entries its type. A legacy document without a type (created by hand, or what earlier versions left
     * behind when deleting a property) takes {@code declared}, the type the application declares, if its value fits;
     * with {@code declared} null it is left alone.
     *
     * @throws IllegalStateException if a legacy document without a type has a value that doesn't fit {@code declared}
     */
    private void moveLegacy(String key, PropertyType declared) {
        if (collection.find(eq(MongoValues.ID_FIELD, key)).first() == null) {
            return;
        }
        try (ClientSession session = client.startSession()) {
            session.withTransaction(() -> {
                Document legacy = collection.find(session, eq(MongoValues.ID_FIELD, key)).first();
                if (legacy == null) {
                    return null; // another instance moved it
                }
                PropertyType type = MongoValues.legacyTypeOf(legacy);
                boolean leftoverOfDeletion = type == null && legacy.get(MongoValues.VALUE_FIELD) == null;
                if (type == null && declared == null) {
                    return null;
                }
                PropertyId id = PropertyId.of(key, type != null ? type : declared);
                // Delete before inserting: the change stream ignores a legacy delete, then reports the insert
                collection.deleteOne(session, eq(MongoValues.ID_FIELD, key));
                if (!leftoverOfDeletion && collection.find(session, byId(id)).first() == null) {
                    Object value = type != null ? legacy.get(MongoValues.VALUE_FIELD) : adopt(legacy, id);
                    collection.insertOne(session, new Document(MongoValues.ID_FIELD, MongoValues.idOf(id))
                            .append(MongoValues.VALUE_FIELD, value)
                            .append(MongoValues.VERSION_FIELD, MongoValues.versionOf(legacy)));
                }
                history.assignLegacyEntries(session, id);
                return null;
            });
        }
        log.info("Moved property '{}' to the key and type document shape", key);
    }

    /** A typeless legacy document's value as the declared type, for storage. */
    private static Object adopt(Document legacy, PropertyId id) {
        try {
            return MongoValues.toBson(MongoValues.read(legacy, id.type()));
        } catch (InvalidConfigValueException e) {
            throw new IllegalStateException("Property '" + id.key() + "' exists in the database without a type, and its "
                    + "value can't be used as " + id.type().typeName() + ": " + e.getMessage()
                    + " Fix or delete the document, then restart.", e);
        }
    }

    private static Bson byId(PropertyId id) {
        return eq(MongoValues.ID_FIELD, MongoValues.idOf(id));
    }

    private static ConfigValue readOrNull(Document doc, PropertyType type) {
        try {
            return MongoValues.read(doc, type);
        } catch (InvalidConfigValueException e) {
            return null; // e.g. edited by hand to a value that doesn't fit its type; the change repairs it
        }
    }

    private ConfigHistoryEntry record(ClientSession session, ConfigHistoryEntry entry) {
        history.insert(session, entry);
        return entry;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}

package io.github.configstream.mongo;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyNotFoundException;
import io.github.configstream.api.PropertyType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
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
    public PropertyType createIfAbsent(String key, ConfigValue initial, String changedBy, String comment) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(changedBy, "changedBy");
        Document existing = collection.find(eq("_id", key)).first();
        if (existing != null && !isLeftoverOfDeletion(existing)) {
            return storedType(existing, initial.type());
        }
        Object stored = MongoValues.toBson(initial);
        try (ClientSession session = client.startSession()) {
            // withTransaction retries the whole body on transient errors such as write conflicts, so when several
            // instances start at once, all but one find the property already there on the retry
            return session.withTransaction(() -> {
                Document current = collection.find(session, eq("_id", key)).first();
                if (current != null && !isLeftoverOfDeletion(current)) {
                    return storedType(current, initial.type());
                }
                long version = Math.max(history.latestVersion(session, key), MongoValues.versionOf(current)) + 1;
                collection.replaceOne(session, eq("_id", key), new Document("_id", key)
                        .append(MongoValues.TYPE_FIELD, initial.type().typeName())
                        .append(MongoValues.VALUE_FIELD, stored)
                        .append(MongoValues.VERSION_FIELD, version), new ReplaceOptions().upsert(true));
                history.insert(session, new ConfigHistoryEntry(key, version, null, initial.text(), changedBy, now(), comment));
                return initial.type();
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            // Another instance created it between our read and our insert
            return storedType(collection.find(eq("_id", key)).first(), initial.type());
        }
    }

    @Override
    public Optional<ConfigHistoryEntry> write(ConfigUpdate update) {
        Objects.requireNonNull(update, "update");
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> writeInTransaction(session, update));
        }
    }

    private Optional<ConfigHistoryEntry> writeInTransaction(ClientSession session, ConfigUpdate update) {
        Document current = collection.find(session, eq("_id", update.key())).first();
        if (current == null) {
            throw new PropertyNotFoundException(update.key());
        }
        PropertyType type = MongoValues.typeOf(current);
        if (type == null) {
            throw new InvalidConfigValueException("Property '" + update.key() + "' has no valid type in the database. "
                    + "Restart a service that declares it to repair it.");
        }
        if (update.type() != null && update.type() != type) {
            throw new InvalidConfigValueException(InvalidConfigValueException.TYPE_CHANGE_NOT_ALLOWED);
        }
        ConfigValue newValue = new ConfigValue(type, type.parse(update.value()));
        Object stored = MongoValues.toBson(newValue);
        ConfigValue oldValue = readOrNull(current);
        if (newValue.sameAs(oldValue)) {
            return Optional.empty();
        }
        long version = MongoValues.versionOf(current) + 1;
        collection.updateOne(session, eq("_id", update.key()),
                combine(set(MongoValues.VALUE_FIELD, stored), set(MongoValues.VERSION_FIELD, version)));
        return Optional.of(record(session, new ConfigHistoryEntry(update.key(), version,
                oldValue != null ? oldValue.text() : MongoValues.rawText(current), newValue.text(),
                update.changedBy(), now(), update.comment())));
    }

    @Override
    public Optional<ConfigHistoryEntry> delete(ConfigDeletion deletion) {
        Objects.requireNonNull(deletion, "deletion");
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> deleteInTransaction(session, deletion));
        }
    }

    private Optional<ConfigHistoryEntry> deleteInTransaction(ClientSession session, ConfigDeletion deletion) {
        Document current = collection.find(session, eq("_id", deletion.key())).first();
        if (current == null) {
            return Optional.empty();
        }
        ConfigValue oldValue = readOrNull(current);
        long version = MongoValues.versionOf(current) + 1;
        collection.deleteOne(session, eq("_id", deletion.key()));
        return Optional.of(record(session, new ConfigHistoryEntry(deletion.key(), version,
                oldValue != null ? oldValue.text() : MongoValues.rawText(current), null,
                deletion.changedBy(), now(), deletion.comment())));
    }

    /**
     * The type stored for an existing property. A document without a type (for example created by hand) is adopted
     * as {@code declared} if its value fits, so existing data can be brought under a manifest.
     */
    private PropertyType storedType(Document doc, PropertyType declared) {
        PropertyType type = MongoValues.typeOf(doc);
        if (type != null) {
            return type;
        }
        Object id = doc.get("_id");
        ConfigValue adopted;
        try {
            adopted = MongoValues.read(new Document(doc).append(MongoValues.TYPE_FIELD, declared.typeName()));
        } catch (InvalidConfigValueException e) {
            throw new IllegalStateException("Property '" + id + "' exists in the database without a type, and its value "
                    + "can't be used as " + declared.typeName() + ": " + e.getMessage()
                    + " Fix or delete the document, then restart.", e);
        }
        collection.updateOne(eq("_id", id), combine(
                set(MongoValues.TYPE_FIELD, declared.typeName()),
                set(MongoValues.VALUE_FIELD, MongoValues.toBson(adopted))));
        log.info("Property '{}' had no type in the database; adopted it as {} as declared in the manifest",
                id, declared.typeName());
        return declared;
    }

    /**
     * A document with neither a type nor a value: what earlier versions of configstream left behind when deleting a
     * property (they kept the document and its version). It counts as absent, and re-creating the property
     * continues its versions.
     */
    private static boolean isLeftoverOfDeletion(Document doc) {
        return doc.get(MongoValues.TYPE_FIELD) == null && doc.get(MongoValues.VALUE_FIELD) == null;
    }

    private static ConfigValue readOrNull(Document doc) {
        try {
            return MongoValues.read(doc);
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

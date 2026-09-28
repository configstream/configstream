package io.github.configstream.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Updates.set;

import com.mongodb.MongoCommandException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyType;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Append-only change history, one document per change:
 * <pre>{ "key": "limits.max", "type": "int", "version": 3, "oldValue": "10", "newValue": "20",
 *   "changedBy": "alice", "changedAt": ISODate(...), "comment": "Reverted to v1" }</pre>
 * Each property (key and type) has its own versions. Entries are only ever inserted, never updated or deleted,
 * except that entries written by earlier versions without a {@code type} get one when their property is moved to the
 * current shape.
 */
public class MongoConfigHistory implements ConfigHistory {

    /** The index earlier versions created, on key and version only. */
    private static final String LEGACY_INDEX = "key_1_version_-1";
    private static final int INDEX_NOT_FOUND = 27;

    private final MongoCollection<Document> collection;

    public MongoConfigHistory(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /**
     * Creates the index history queries rely on. Its uniqueness also guarantees no two changes to a
     * property can ever share a version. Safe to call on every startup.
     */
    public void ensureIndexes() {
        try {
            // It would stop a key's properties of different types from each having a version 1
            collection.dropIndex(LEGACY_INDEX);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_NOT_FOUND) {
                throw e;
            }
        }
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("key"), Indexes.ascending("type"),
                Indexes.descending("version")), new IndexOptions().unique(true));
    }

    @Override
    public List<ConfigHistoryEntry> history(PropertyId id, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<ConfigHistoryEntry> entries = new ArrayList<>();
        for (Document doc : collection.find(filter(id)).sort(descending("version")).limit(limit)) {
            entries.add(fromDocument(doc));
        }
        return entries;
    }

    void insert(ClientSession session, ConfigHistoryEntry entry) {
        collection.insertOne(session, new Document("key", entry.key())
                .append("type", entry.type().typeName())
                .append("version", entry.version())
                .append("oldValue", entry.oldValue())
                .append("newValue", entry.newValue())
                .append("changedBy", entry.changedBy())
                .append("changedAt", Date.from(entry.changedAt()))
                .append("comment", entry.comment()));
    }

    /** The highest version recorded for the property, or 0 if it has no history. */
    long latestVersion(ClientSession session, PropertyId id) {
        Document latest = collection.find(session, filter(id)).sort(descending("version")).limit(1).first();
        return latest == null ? 0 : latest.get("version", Number.class).longValue();
    }

    /** Gives the key's entries written by earlier versions, which have no type, the type of the property they belong to. */
    void assignLegacyEntries(ClientSession session, PropertyId id) {
        collection.updateMany(session, and(eq("key", id.key()), exists("type", false)),
                set("type", id.type().typeName()));
    }

    private static Bson filter(PropertyId id) {
        return and(eq("key", id.key()), eq("type", id.type().typeName()));
    }

    private static ConfigHistoryEntry fromDocument(Document doc) {
        return new ConfigHistoryEntry(
                doc.getString("key"),
                PropertyType.fromName(doc.getString("type")),
                doc.get("version", Number.class).longValue(),
                doc.getString("oldValue"),
                doc.getString("newValue"),
                doc.getString("changedBy"),
                doc.getDate("changedAt").toInstant(),
                doc.getString("comment"));
    }
}

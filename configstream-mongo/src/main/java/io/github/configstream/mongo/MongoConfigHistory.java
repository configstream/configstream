package io.github.configstream.mongo;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigHistoryEntry;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;

/**
 * Append-only change history, one document per change:
 * <pre>{ "key": "limits.max", "version": 3, "oldValue": "10", "newValue": "20",
 *   "changedBy": "alice", "changedAt": ISODate(...), "comment": "Reverted to v1" }</pre>
 * Entries are only ever inserted, never updated or deleted.
 */
public class MongoConfigHistory implements ConfigHistory {

    private final MongoCollection<Document> collection;

    public MongoConfigHistory(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /**
     * Creates the index history queries rely on. Its uniqueness also guarantees no two changes to a
     * key can ever share a version. Safe to call on every startup.
     */
    public void ensureIndexes() {
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("key"), Indexes.descending("version")),
                new IndexOptions().unique(true));
    }

    @Override
    public List<ConfigHistoryEntry> history(String key, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<ConfigHistoryEntry> entries = new ArrayList<>();
        for (Document doc : collection.find(eq("key", key)).sort(descending("version")).limit(limit)) {
            entries.add(fromDocument(doc));
        }
        return entries;
    }

    void insert(ClientSession session, ConfigHistoryEntry entry) {
        collection.insertOne(session, new Document("key", entry.key())
                .append("version", entry.version())
                .append("oldValue", entry.oldValue())
                .append("newValue", entry.newValue())
                .append("changedBy", entry.changedBy())
                .append("changedAt", Date.from(entry.changedAt()))
                .append("comment", entry.comment()));
    }

    /** The highest version recorded for {@code key}, or 0 if it has no history. */
    long latestVersion(ClientSession session, String key) {
        Document latest = collection.find(session, eq("key", key)).sort(descending("version")).limit(1).first();
        return latest == null ? 0 : latest.get("version", Number.class).longValue();
    }

    private static ConfigHistoryEntry fromDocument(Document doc) {
        return new ConfigHistoryEntry(
                doc.getString("key"),
                doc.get("version", Number.class).longValue(),
                doc.getString("oldValue"),
                doc.getString("newValue"),
                doc.getString("changedBy"),
                doc.getDate("changedAt").toInstant(),
                doc.getString("comment"));
    }
}

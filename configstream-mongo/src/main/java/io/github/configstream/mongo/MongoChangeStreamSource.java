package io.github.configstream.mongo;

import com.mongodb.MongoException;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.OperationType;
import io.github.configstream.api.ConfigChange;
import io.github.configstream.api.ConfigChangeListener;
import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyId;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ConfigChangeSource} backed by a MongoDB change stream. Requires a replica set
 * (a single-node one is fine) because change streams read the oplog.
 *
 * <p>Expected document shape, one document per property, identified by its key and type (see {@link MongoValues}):
 * <pre>{ "_id": { "key": "feature.funds.limit", "type": "int" }, "value": 3, "version": 1 }</pre>
 * A document whose value doesn't fit its type (for example after a direct edit in the database) is left out and
 * reported as a delete, so applications fall back to the property's initial value until it is fixed. Documents
 * whose {@code _id} names no valid key and type are ignored. Legacy documents (a string {@code _id} and a
 * {@code type} field) are read too; deleting one reports nothing, because {@link MongoConfigWriter} deletes them only
 * while moving them to the current shape.
 *
 * <p><b>Startup race:</b> the change stream is opened <em>before</em> the initial snapshot is read.
 * Anything written while the snapshot loads is held by the open stream (MongoDB keeps it in the
 * oplog, which acts as the buffer) and delivered right after the snapshot. Replaying a change the
 * snapshot already contains is harmless because every event carries the full document.
 *
 * <p><b>Failures:</b> the driver retries one transient error by itself. Beyond that, this class
 * reconnects with exponential backoff and resumes from the last seen resume token. If the token
 * has aged out of the oplog, or the collection is dropped/renamed, it reloads a fresh snapshot.
 */
public class MongoChangeStreamSource implements ConfigChangeSource {

    private static final Logger log = LoggerFactory.getLogger(MongoChangeStreamSource.class);

    private static final int CHANGE_STREAM_HISTORY_LOST = 286;
    private static final Duration MAX_AWAIT = Duration.ofMillis(500);
    private static final Duration MIN_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final MongoCollection<Document> collection;

    private volatile boolean running;
    private Thread worker;

    // Owned by whichever thread is currently driving the stream: the caller inside start(), then the worker.
    private ConfigChangeListener listener;
    private MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;
    private BsonDocument resumeToken;

    public MongoChangeStreamSource(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    @Override
    public Map<PropertyId, ConfigValue> loadInitial() {
        Map<PropertyId, ConfigValue> entries = new HashMap<>();
        for (Document doc : collection.find()) {
            PropertyId id = idOf(doc);
            ConfigValue value = id == null ? null : valueOf(id, doc);
            if (value != null) {
                entries.put(id, value);
            }
        }
        return entries;
    }

    @Override
    public synchronized void start(ConfigChangeListener listener) {
        if (worker != null) {
            throw new IllegalStateException("already started");
        }
        this.listener = listener;
        try {
            openFreshStreamAndSnapshot();
        } catch (RuntimeException e) {
            closeCursor();
            throw e;
        }
        running = true;
        worker = new Thread(this::run, "configstream-change-stream");
        worker.setDaemon(true);
        worker.start();
        log.info("Watching {} for config changes", collection.getNamespace());
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread w = worker;
        if (w == null) {
            return;
        }
        w.interrupt();
        try {
            w.join(STOP_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker = null;
    }

    private void run() {
        Duration backoff = MIN_BACKOFF;
        while (running) {
            try {
                if (cursor == null) {
                    reconnect();
                }
                ChangeStreamDocument<Document> event = cursor.tryNext();
                if (event != null && event.getOperationType() == OperationType.INVALIDATE) {
                    // Collection dropped or renamed; this stream can't continue.
                    log.warn("Change stream on {} invalidated; reloading", collection.getNamespace());
                    closeCursor();
                    resumeToken = null;
                    continue;
                }
                if (event != null) {
                    apply(event);
                }
                resumeToken = cursor.getResumeToken();
                backoff = MIN_BACKOFF;
            } catch (RuntimeException e) {
                // Includes a failing listener during a snapshot reload: keep the thread alive and retry.
                if (!running) {
                    break;
                }
                if (e instanceof MongoException me && me.getCode() == CHANGE_STREAM_HISTORY_LOST) {
                    resumeToken = null; // too far behind to resume; next reconnect reloads a snapshot
                }
                log.warn("Change stream on {} failed; reconnecting in {} ms",
                        collection.getNamespace(), backoff.toMillis(), e);
                closeCursor();
                if (!sleep(backoff)) {
                    break;
                }
                backoff = min(backoff.multipliedBy(2), MAX_BACKOFF);
            }
        }
        closeCursor();
    }

    private void reconnect() {
        if (resumeToken != null) {
            cursor = openStream(resumeToken);
            log.info("Resumed change stream on {}", collection.getNamespace());
        } else {
            openFreshStreamAndSnapshot();
            log.info("Reloaded config snapshot from {}", collection.getNamespace());
        }
    }

    /** Opens the stream first, then reads the snapshot, so no write can slip between the two. */
    private void openFreshStreamAndSnapshot() {
        cursor = openStream(null);
        resumeToken = cursor.getResumeToken();
        listener.onSnapshot(loadInitial());
    }

    private MongoChangeStreamCursor<ChangeStreamDocument<Document>> openStream(BsonDocument resumeAfter) {
        var stream = collection.watch()
                .fullDocument(FullDocument.UPDATE_LOOKUP)
                .maxAwaitTime(MAX_AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        if (resumeAfter != null) {
            stream = stream.resumeAfter(resumeAfter);
        }
        return stream.cursor();
    }

    private void apply(ChangeStreamDocument<Document> event) {
        ConfigChange change = toChange(event);
        if (change == null) {
            return;
        }
        try {
            listener.onChange(change);
        } catch (RuntimeException e) {
            log.error("Config listener failed on change to {}", change.id(), e);
        }
    }

    private static ConfigChange toChange(ChangeStreamDocument<Document> event) {
        OperationType type = event.getOperationType();
        if (type == null) {
            return null;
        }
        switch (type) {
            case INSERT, UPDATE, REPLACE -> {
                Document doc = event.getFullDocument();
                if (doc == null) {
                    return null; // deleted before the lookup ran; its DELETE event follows
                }
                PropertyId id = idOf(doc);
                if (id == null) {
                    return null;
                }
                ConfigValue value = valueOf(id, doc);
                return value != null ? ConfigChange.upsert(id, value) : ConfigChange.delete(id);
            }
            case DELETE -> {
                BsonDocument docKey = event.getDocumentKey();
                BsonValue id = docKey == null ? null : docKey.get("_id");
                if (id == null || !id.isDocument()) {
                    return null; // a legacy document, deleted after being moved to the current shape
                }
                PropertyId deleted = MongoValues.propertyIdOf(id.asDocument());
                return deleted == null ? null : ConfigChange.delete(deleted);
            }
            default -> {
                return null; // DROP/RENAME are followed by INVALIDATE, handled in run()
            }
        }
    }

    private static PropertyId idOf(Document doc) {
        PropertyId id = MongoValues.propertyIdOf(doc);
        if (id == null) {
            log.warn("Ignoring config document without a valid key and type: {}", doc.get(MongoValues.ID_FIELD));
        }
        return id;
    }

    /** The document's typed value, or null (with a warning) if it has none that fits its type. */
    private static ConfigValue valueOf(PropertyId id, Document doc) {
        try {
            return MongoValues.read(doc, id.type());
        } catch (InvalidConfigValueException e) {
            log.warn("Ignoring the stored value of property {}: {} Applications use its initial value until "
                    + "it is fixed.", id, e.getMessage());
            return null;
        }
    }

    private void closeCursor() {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (RuntimeException e) {
                log.debug("Ignoring error while closing change stream cursor", e);
            }
            cursor = null;
        }
    }

    private static boolean sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}

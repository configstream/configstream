package io.github.configstream.mongo;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.github.configstream.api.ConfigCache;
import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyType;
import io.github.configstream.testsupport.TestMongo;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.awaitility.core.ConditionFactory;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MongoChangeStreamSourceIT {

    /** The phase goal: a change made directly in Mongo shows up in the cache within ~1 second. */
    private static final Duration PROPAGATION = Duration.ofSeconds(1);

    private static final PropertyId FEATURE_X = PropertyId.of("feature.x.enabled", PropertyType.BOOLEAN);
    private static final PropertyId LIMITS = PropertyId.of("limits.max", PropertyType.INT);

    static MongoClient client;

    MongoCollection<Document> collection;
    MongoChangeStreamSource source;
    ConfigCache cache;

    @BeforeAll
    static void connect() {
        client = TestMongo.client();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void setUp() {
        // Fresh collection per test so tests can't see each other's data
        collection = client.getDatabase(TestMongo.database()).getCollection("config_" + UUID.randomUUID());
        source = new MongoChangeStreamSource(collection);
        cache = new ConfigCache();
    }

    @AfterEach
    void tearDown() {
        source.stop();
        collection.drop();
    }

    @Test
    void loadsTypedPropertiesBeforeStartReturns() {
        collection.insertMany(List.of(
                property("feature.x.enabled", "boolean", true),
                property("limits.max", "int", 10),
                property("fees.rate", "decimal", new Decimal128(new BigDecimal("0.25"))),
                property("banner.text", "string", "Hello"),
                new Document("_id", new Document("key", "no.type")).append("value", "x"),
                new Document("_id", new Document("key", "odd.type").append("type", "list")).append("value", "x"),
                new Document("_id", 42).append("value", "x"),
                property("bad.number", "int", "abc")));

        source.start(cache);

        assertThat(cache.getAll()).isEqualTo(Map.of(
                FEATURE_X, new ConfigValue(PropertyType.BOOLEAN, true),
                LIMITS, new ConfigValue(PropertyType.INT, 10),
                PropertyId.of("fees.rate", PropertyType.DECIMAL), new ConfigValue(PropertyType.DECIMAL, new BigDecimal("0.25")),
                PropertyId.of("banner.text", PropertyType.STRING), new ConfigValue(PropertyType.STRING, "Hello")));
    }

    @Test
    void theSameKeyWithTwoTypesIsTwoProperties() {
        collection.insertMany(List.of(property("limits.max", "int", 10), property("limits.max", "string", "ten")));

        source.start(cache);

        assertThat(cache.getAll()).isEqualTo(Map.of(
                LIMITS, new ConfigValue(PropertyType.INT, 10),
                PropertyId.of("limits.max", PropertyType.STRING), new ConfigValue(PropertyType.STRING, "ten")));
    }

    @Test
    void readsLegacyDocuments() {
        // Earlier versions used the key alone as _id, with the type in a field
        collection.insertMany(List.of(
                legacy("limits.max", "int", 10),
                new Document("_id", "no.type").append("value", "x")));

        source.start(cache);

        assertThat(cache.getAll()).isEqualTo(Map.of(LIMITS, new ConfigValue(PropertyType.INT, 10)));
    }

    @Test
    void acceptsValuesEnteredByHandThatFitTheirType() {
        // The MongoDB shell stores whole numbers as doubles; people also type numbers as text
        collection.insertMany(List.of(
                property("a.int", "int", 5.0),
                property("b.int", "int", "7"),
                property("c.decimal", "decimal", 1.5)));

        source.start(cache);

        assertThat(cache.getAll()).isEqualTo(Map.of(
                PropertyId.of("a.int", PropertyType.INT), new ConfigValue(PropertyType.INT, 5),
                PropertyId.of("b.int", PropertyType.INT), new ConfigValue(PropertyType.INT, 7),
                PropertyId.of("c.decimal", PropertyType.DECIMAL), new ConfigValue(PropertyType.DECIMAL, new BigDecimal("1.5"))));
    }

    @Test
    void reflectsInsertUpdateAndDeleteWithoutRestart() {
        source.start(cache);

        collection.insertOne(property("feature.x.enabled", "boolean", false));
        awaitValue(FEATURE_X, false);

        collection.updateOne(byId("feature.x.enabled", "boolean"), set("value", true));
        awaitValue(FEATURE_X, true);

        collection.replaceOne(byId("feature.x.enabled", "boolean"), property("feature.x.enabled", "boolean", false));
        awaitValue(FEATURE_X, false);

        collection.deleteOne(byId("feature.x.enabled", "boolean"));
        awaitValue(FEATURE_X, null);
    }

    @Test
    void aValueThatNoLongerFitsItsTypeLeavesTheCacheUntilFixed() {
        collection.insertOne(property("limits.max", "int", 10));
        source.start(cache);

        collection.updateOne(byId("limits.max", "int"), set("value", "abc"));
        awaitValue(LIMITS, null);

        collection.updateOne(byId("limits.max", "int"), set("value", 20));
        awaitValue(LIMITS, 20);
    }

    @Test
    void doesNotLoseWritesMadeDuringStartup() {
        int writes = 300;
        collection.insertOne(property("counter.value", "int", 0));

        // Keep writing while start() opens the stream and loads the snapshot
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            for (int i = 1; i <= writes; i++) {
                collection.updateOne(byId("counter.value", "int"), set("value", i));
                collection.insertOne(property("key.k" + i, "int", i));
            }
        });
        source.start(cache);
        writer.join();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(cache.get(PropertyId.of("counter.value", PropertyType.INT)))
                    .contains(new ConfigValue(PropertyType.INT, writes));
            assertThat(cache.getAll()).hasSize(writes + 1);
        });
    }

    @Test
    void reloadsAfterCollectionIsDropped() {
        collection.insertOne(property("old.key", "int", 1));
        source.start(cache);

        collection.drop();
        collection.insertOne(property("new.key", "int", 2));

        await().atMost(Duration.ofSeconds(10)).until(cache::getAll,
                Map.of(PropertyId.of("new.key", PropertyType.INT), new ConfigValue(PropertyType.INT, 2))::equals);
    }

    @Test
    void stopHaltsUpdatesAndIsIdempotent() {
        source.start(cache);
        source.stop();
        source.stop();

        collection.insertOne(property("after.stop", "string", "x"));

        await().during(PROPAGATION).atMost(PROPAGATION.multipliedBy(2))
                .until(() -> cache.get(PropertyId.of("after.stop", PropertyType.STRING)).isEmpty());
    }

    @Test
    void writerChangesAndDeletesReachTheCache() {
        collection.insertMany(List.of(property("feature.x.enabled", "boolean", false), property("limits.max", "int", 1)));
        source.start(cache);
        MongoConfigWriter writer = writer();

        writer.write(new ConfigUpdate(FEATURE_X, "true", "tester", null));
        writer.delete(new ConfigDeletion(LIMITS, "tester", null));

        awaitCache().until(cache::getAll, Map.of(FEATURE_X, new ConfigValue(PropertyType.BOOLEAN, true))::equals);
        assertThat(source.loadInitial()).isEqualTo(cache.getAll());
    }

    @Test
    void movingALegacyDocumentKeepsItInTheCache() {
        collection.insertOne(legacy("limits.max", "int", 10).append("version", 1L));
        source.start(cache);

        writer().write(new ConfigUpdate(LIMITS, "20", "tester", null));

        awaitValue(LIMITS, 20);
        assertThat(collection.countDocuments(eq("_id", "limits.max"))).isZero();
    }

    @Test
    void cannotStartTwice() {
        source.start(cache);

        assertThatThrownBy(() -> source.start(cache)).isInstanceOf(IllegalStateException.class);
    }

    private MongoConfigWriter writer() {
        return new MongoConfigWriter(client, collection, new MongoConfigHistory(
                client.getDatabase(TestMongo.database()).getCollection("history_" + UUID.randomUUID())));
    }

    private void awaitValue(PropertyId id, Object expected) {
        // untilAsserted rather than until(supplier, predicate): the latter never matches a null value
        awaitCache().untilAsserted(() -> assertThat(cache.get(id).map(ConfigValue::value).orElse(null)).isEqualTo(expected));
    }

    /** A property document in the current shape. */
    static Document property(String key, String type, Object value) {
        return new Document("_id", new Document("key", key).append("type", type)).append("value", value);
    }

    /** A property document in the shape earlier versions wrote. */
    static Document legacy(String key, String type, Object value) {
        return new Document("_id", key).append("type", type).append("value", value);
    }

    static Bson byId(String key, String type) {
        return eq("_id", new Document("key", key).append("type", type));
    }

    private static ConditionFactory awaitCache() {
        return await().atMost(PROPAGATION.multipliedBy(3)).pollInterval(Duration.ofMillis(20));
    }
}

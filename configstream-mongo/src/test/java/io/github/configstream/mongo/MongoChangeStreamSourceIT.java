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
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MongoChangeStreamSourceIT {

    /** The phase goal: a change made directly in Mongo shows up in the cache within ~1 second. */
    private static final Duration PROPAGATION = Duration.ofSeconds(1);

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
                new Document("_id", "no.type").append("value", "x"),
                property("bad.number", "int", "abc")));

        source.start(cache);

        assertThat(cache.getAll()).isEqualTo(Map.of(
                "feature.x.enabled", new ConfigValue(PropertyType.BOOLEAN, true),
                "limits.max", new ConfigValue(PropertyType.INT, 10),
                "fees.rate", new ConfigValue(PropertyType.DECIMAL, new BigDecimal("0.25")),
                "banner.text", new ConfigValue(PropertyType.STRING, "Hello")));
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
                "a.int", new ConfigValue(PropertyType.INT, 5),
                "b.int", new ConfigValue(PropertyType.INT, 7),
                "c.decimal", new ConfigValue(PropertyType.DECIMAL, new BigDecimal("1.5"))));
    }

    @Test
    void reflectsInsertUpdateAndDeleteWithoutRestart() {
        source.start(cache);

        collection.insertOne(property("feature.x.enabled", "boolean", false));
        awaitValue("feature.x.enabled", false);

        collection.updateOne(eq("_id", "feature.x.enabled"), set("value", true));
        awaitValue("feature.x.enabled", true);

        collection.replaceOne(eq("_id", "feature.x.enabled"), property("feature.x.enabled", "boolean", false));
        awaitValue("feature.x.enabled", false);

        collection.deleteOne(eq("_id", "feature.x.enabled"));
        awaitValue("feature.x.enabled", null);
    }

    @Test
    void aValueThatNoLongerFitsItsTypeLeavesTheCacheUntilFixed() {
        collection.insertOne(property("limits.max", "int", 10));
        source.start(cache);

        collection.updateOne(eq("_id", "limits.max"), set("value", "abc"));
        awaitValue("limits.max", null);

        collection.updateOne(eq("_id", "limits.max"), set("value", 20));
        awaitValue("limits.max", 20);
    }

    @Test
    void doesNotLoseWritesMadeDuringStartup() {
        int writes = 300;
        collection.insertOne(property("counter.value", "int", 0));

        // Keep writing while start() opens the stream and loads the snapshot
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            for (int i = 1; i <= writes; i++) {
                collection.updateOne(eq("_id", "counter.value"), set("value", i));
                collection.insertOne(property("key.k" + i, "int", i));
            }
        });
        source.start(cache);
        writer.join();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(cache.get("counter.value")).contains(new ConfigValue(PropertyType.INT, writes));
            assertThat(cache.getAll()).hasSize(writes + 1);
        });
    }

    @Test
    void reloadsAfterCollectionIsDropped() {
        collection.insertOne(property("old.key", "int", 1));
        source.start(cache);

        collection.drop();
        collection.insertOne(property("new.key", "int", 2));

        await().atMost(Duration.ofSeconds(10))
                .until(cache::getAll, Map.of("new.key", new ConfigValue(PropertyType.INT, 2))::equals);
    }

    @Test
    void stopHaltsUpdatesAndIsIdempotent() {
        source.start(cache);
        source.stop();
        source.stop();

        collection.insertOne(property("after.stop", "string", "x"));

        await().during(PROPAGATION).atMost(PROPAGATION.multipliedBy(2))
                .until(() -> cache.get("after.stop").isEmpty());
    }

    @Test
    void writerChangesAndDeletesReachTheCache() {
        collection.insertMany(List.of(property("feature.x.enabled", "boolean", false), property("limits.max", "int", 1)));
        source.start(cache);
        MongoConfigWriter writer = new MongoConfigWriter(client, collection, new MongoConfigHistory(
                client.getDatabase(TestMongo.database()).getCollection("history_" + UUID.randomUUID())));

        writer.write(new ConfigUpdate("feature.x.enabled", "true", null, "tester", null));
        writer.delete(new ConfigDeletion("limits.max", "tester", null));

        awaitCache().until(cache::getAll, Map.of("feature.x.enabled", new ConfigValue(PropertyType.BOOLEAN, true))::equals);
        assertThat(source.loadInitial()).isEqualTo(cache.getAll());
    }

    @Test
    void cannotStartTwice() {
        source.start(cache);

        assertThatThrownBy(() -> source.start(cache)).isInstanceOf(IllegalStateException.class);
    }

    private void awaitValue(String key, Object expected) {
        // untilAsserted rather than until(supplier, predicate): the latter never matches a null value
        awaitCache().untilAsserted(() -> assertThat(cache.get(key).map(ConfigValue::value).orElse(null)).isEqualTo(expected));
    }

    static Document property(String key, String type, Object value) {
        return new Document("_id", key).append("type", type).append("value", value);
    }

    private static ConditionFactory awaitCache() {
        return await().atMost(PROPAGATION.multipliedBy(3)).pollInterval(Duration.ofMillis(20));
    }
}

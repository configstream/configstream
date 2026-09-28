package io.github.configstream.mongo;

import static com.mongodb.client.model.Filters.eq;
import static io.github.configstream.mongo.MongoChangeStreamSourceIT.byId;
import static io.github.configstream.mongo.MongoChangeStreamSourceIT.legacy;
import static io.github.configstream.mongo.MongoChangeStreamSourceIT.property;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyNotFoundException;
import io.github.configstream.api.PropertyType;
import io.github.configstream.testsupport.TestMongo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MongoConfigWriterIT {

    private static final PropertyId LIMITS = PropertyId.of("limits.max", PropertyType.INT);
    private static final PropertyId LIMITS_TEXT = PropertyId.of("limits.max", PropertyType.STRING);
    private static final PropertyId FUNDS = PropertyId.of("feature.funds.enabled", PropertyType.BOOLEAN);

    static MongoClient client;

    MongoCollection<Document> config;
    MongoCollection<Document> historyCollection;
    MongoConfigHistory history;
    MongoConfigWriter writer;

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
        String suffix = UUID.randomUUID().toString();
        config = client.getDatabase(TestMongo.database()).getCollection("config_" + suffix);
        historyCollection = client.getDatabase(TestMongo.database()).getCollection("history_" + suffix);
        history = new MongoConfigHistory(historyCollection);
        history.ensureIndexes();
        writer = new MongoConfigWriter(client, config, history);
    }

    @AfterEach
    void tearDown() {
        config.drop();
        historyCollection.drop();
    }

    @Test
    void createIfAbsentInsertsThePropertyAndRecordsVersion1() {
        assertThat(writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", "Created from OrdersProperties")).isTrue();

        assertThat(stored(LIMITS))
                .containsEntry("_id", new Document("key", "limits.max").append("type", "int"))
                .containsEntry("value", 3)
                .containsEntry("version", 1L)
                .doesNotContainKey("type");
        assertThat(history.history(LIMITS, 10)).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(PropertyType.INT);
            assertThat(e.version()).isEqualTo(1);
            assertThat(e.oldValue()).isNull();
            assertThat(e.newValue()).isEqualTo("3");
            assertThat(e.changedBy()).isEqualTo("orders (startup)");
            assertThat(e.comment()).isEqualTo("Created from OrdersProperties");
        });
    }

    @Test
    void createIfAbsentNeverChangesAnExistingProperty() {
        writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", null);
        writer.write(new ConfigUpdate(LIMITS, "50", "alice", null));

        assertThat(writer.createIfAbsent(LIMITS, intValue(10), "orders (startup)", null)).isFalse();

        assertThat(stored(LIMITS)).containsEntry("value", 50);
        assertThat(history.history(LIMITS, 10)).hasSize(2);
    }

    @Test
    void theSameKeyWithAnotherTypeIsAnotherProperty() {
        // A new version of the application changed the field's type while the old version still runs
        writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", null);
        writer.write(new ConfigUpdate(LIMITS, "50", "alice", null));

        assertThat(writer.createIfAbsent(LIMITS_TEXT, new ConfigValue(PropertyType.STRING, "lots"), "orders (startup)", null))
                .isTrue();

        assertThat(stored(LIMITS)).containsEntry("value", 50);
        assertThat(stored(LIMITS_TEXT)).containsEntry("value", "lots").containsEntry("version", 1L);
        assertThat(history.history(LIMITS_TEXT, 10)).extracting(ConfigHistoryEntry::version).containsExactly(1L);
        assertThat(history.history(LIMITS, 10)).extracting(ConfigHistoryEntry::version).containsExactly(2L, 1L);

        // Deleting the old one leaves the new one alone
        writer.delete(new ConfigDeletion(LIMITS, "alice", "old version retired"));
        assertThat(stored(LIMITS)).isNull();
        assertThat(stored(LIMITS_TEXT)).isNotNull();
    }

    @Test
    void instancesStartingTogetherCreateThePropertyOnce() {
        CompletableFuture<?>[] starts = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", null)))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(starts).join();

        assertThat(config.countDocuments()).isEqualTo(1);
        assertThat(history.history(LIMITS, 10)).hasSize(1);
    }

    @Test
    void createIfAbsentMovesALegacyDocumentWithItsValueVersionAndHistory() {
        config.insertOne(legacy("limits.max", "int", 25).append("version", 2L));
        insertLegacyHistory("limits.max", 1, null, "10");
        insertLegacyHistory("limits.max", 2, "10", "25");

        assertThat(writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", null)).isFalse();

        assertThat(config.find(eq("_id", "limits.max")).first()).isNull();
        assertThat(stored(LIMITS)).containsEntry("value", 25).containsEntry("version", 2L);
        assertThat(history.history(LIMITS, 10))
                .extracting(ConfigHistoryEntry::version, ConfigHistoryEntry::newValue)
                .containsExactly(tuple(2L, "25"), tuple(1L, "10"));

        writer.write(new ConfigUpdate(LIMITS, "30", "alice", null));
        assertThat(history.history(LIMITS, 1).get(0).version()).isEqualTo(3);
    }

    @Test
    void aLegacyDocumentOfAnotherTypeIsMovedAndANewPropertyCreated() {
        config.insertOne(legacy("limits.max", "string", "lots").append("version", 1L));
        insertLegacyHistory("limits.max", 1, null, "lots");

        assertThat(writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", null)).isTrue();

        assertThat(stored(LIMITS_TEXT)).containsEntry("value", "lots");
        assertThat(stored(LIMITS)).containsEntry("value", 3).containsEntry("version", 1L);
        assertThat(history.history(LIMITS_TEXT, 10)).hasSize(1);
        assertThat(history.history(LIMITS, 10)).hasSize(1);
    }

    @Test
    void updatesAndDeletesMoveALegacyDocumentFirst() {
        config.insertOne(legacy("limits.max", "int", 25).append("version", 1L));
        config.insertOne(legacy("feature.funds.enabled", "boolean", true).append("version", 1L));

        assertThat(writer.write(new ConfigUpdate(LIMITS, "30", "alice", null))).get()
                .extracting(ConfigHistoryEntry::version, ConfigHistoryEntry::oldValue).containsExactly(2L, "25");
        assertThat(writer.delete(new ConfigDeletion(FUNDS, "alice", null))).isPresent();

        assertThat(config.countDocuments()).isEqualTo(1);
        assertThat(stored(LIMITS)).containsEntry("value", 30);
    }

    @Test
    void createIfAbsentAdoptsAnUntypedLegacyDocumentWhoseValueFits() {
        config.insertOne(new Document("_id", "limits.max").append("value", "25"));

        assertThat(writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", null)).isFalse();
        assertThat(stored(LIMITS)).containsEntry("value", 25);
    }

    @Test
    void createIfAbsentRefusesAnUntypedLegacyDocumentWhoseValueDoesNotFit() {
        config.insertOne(new Document("_id", "limits.max").append("value", "lots"));

        assertThatThrownBy(() -> writer.createIfAbsent(LIMITS, intValue(3), "orders (startup)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'lots' is not a valid int");
        assertThat(config.find(eq("_id", "limits.max")).first()).isNotNull();
    }

    @Test
    void createIfAbsentReplacesWhatAnOldSoftDeleteLeftBehind() {
        // Earlier versions deleted by removing the value but keeping the document and its version
        config.insertOne(new Document("_id", "feature.x.enabled").append("version", 4L));
        for (int v = 1; v <= 4; v++) {
            insertLegacyHistory("feature.x.enabled", v, v == 1 ? null : "true", v == 4 ? null : "true");
        }
        PropertyId featureX = PropertyId.of("feature.x.enabled", PropertyType.BOOLEAN);

        assertThat(writer.createIfAbsent(featureX, new ConfigValue(PropertyType.BOOLEAN, true), "orders (startup)", null))
                .isTrue();

        assertThat(config.find(eq("_id", "feature.x.enabled")).first()).isNull();
        assertThat(stored(featureX)).containsEntry("value", true).containsEntry("version", 5L);
        assertThat(history.history(featureX, 1).get(0).version()).isEqualTo(5);
    }

    @Test
    void storesNativeBsonTypes() {
        PropertyId rate = PropertyId.of("fees.rate", PropertyType.DECIMAL);
        PropertyId featureX = PropertyId.of("feature.x.enabled", PropertyType.BOOLEAN);
        PropertyId banner = PropertyId.of("banner.text", PropertyType.STRING);
        writer.createIfAbsent(rate, new ConfigValue(PropertyType.DECIMAL, new BigDecimal("0.10")), "t", null);
        writer.createIfAbsent(featureX, new ConfigValue(PropertyType.BOOLEAN, false), "t", null);
        writer.createIfAbsent(banner, new ConfigValue(PropertyType.STRING, "007"), "t", null);

        assertThat(stored(rate).get("value")).isEqualTo(new Decimal128(new BigDecimal("0.10")));
        assertThat(stored(featureX).get("value")).isEqualTo(false);
        assertThat(stored(banner).get("value")).isEqualTo("007");
    }

    @Test
    void createIfAbsentRejectsAValueOfAnotherType() {
        assertThatThrownBy(() -> writer.createIfAbsent(LIMITS, new ConfigValue(PropertyType.STRING, "3"), "t", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsEveryChangeWithIncreasingVersions() {
        writer.createIfAbsent(LIMITS, intValue(10), "orders (startup)", null);
        Instant before = Instant.now().minusSeconds(1);

        writer.write(new ConfigUpdate(LIMITS, "20", "bob", "traffic spike"));

        List<ConfigHistoryEntry> entries = history.history(LIMITS, 10);
        assertThat(entries).extracting(ConfigHistoryEntry::version).containsExactly(2L, 1L);
        assertThat(entries.get(0)).satisfies(e -> {
            assertThat(e.oldValue()).isEqualTo("10");
            assertThat(e.newValue()).isEqualTo("20");
            assertThat(e.changedBy()).isEqualTo("bob");
            assertThat(e.comment()).isEqualTo("traffic spike");
            assertThat(e.changedAt()).isAfter(before);
        });
        assertThat(stored(LIMITS)).containsEntry("value", 20).containsEntry("version", 2L);
    }

    @Test
    void writeReturnsTheRecordedEntry() {
        writer.createIfAbsent(LIMITS, intValue(1), "t", null);

        Optional<ConfigHistoryEntry> entry = writer.write(new ConfigUpdate(LIMITS, "2", "alice", null));

        assertThat(entry).isPresent();
        assertThat(history.history(LIMITS, 1)).containsExactly(entry.get());
    }

    @Test
    void writingTheSameValueRecordsNothing() {
        PropertyId rate = PropertyId.of("fees.rate", PropertyType.DECIMAL);
        writer.createIfAbsent(rate, new ConfigValue(PropertyType.DECIMAL, new BigDecimal("1.0")), "t", null);

        assertThat(writer.write(new ConfigUpdate(rate, "1.00", "bob", null))).isEmpty();
        assertThat(history.history(rate, 10)).hasSize(1);
    }

    @Test
    void updatesNeverCreateProperties() {
        writer.createIfAbsent(FUNDS, new ConfigValue(PropertyType.BOOLEAN, false), "t", null);

        assertThatThrownBy(() -> writer.write(new ConfigUpdate(PropertyId.of("brand.new", PropertyType.INT), "1", "alice", null)))
                .isInstanceOf(PropertyNotFoundException.class)
                .hasMessageContaining("created only when a service that declares them starts");
        // The same key with another type is another property, which doesn't exist either
        assertThatThrownBy(() -> writer.write(new ConfigUpdate(
                PropertyId.of("feature.funds.enabled", PropertyType.INT), "3", "alice", null)))
                .isInstanceOf(PropertyNotFoundException.class)
                .hasMessageStartingWith("No property 'feature.funds.enabled' of type int.");
        assertThat(config.countDocuments()).isEqualTo(1);
    }

    @Test
    void rejectsValuesThatDoNotFitTheType() {
        writer.createIfAbsent(LIMITS, intValue(1), "t", null);

        for (String bad : new String[] {"3.5", "abc", ""}) {
            assertThatThrownBy(() -> writer.write(new ConfigUpdate(LIMITS, bad, "alice", null)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessage("'" + bad + "' is not a valid int.");
        }
        assertThat(history.history(LIMITS, 10)).hasSize(1);
    }

    @Test
    void aWriteRepairsAValueEditedByHandToSomethingInvalid() {
        config.insertOne(property("limits.max", "int", "abc").append("version", 1L));

        Optional<ConfigHistoryEntry> entry = writer.write(new ConfigUpdate(LIMITS, "5", "alice", null));

        assertThat(entry).get().extracting(ConfigHistoryEntry::oldValue, ConfigHistoryEntry::newValue)
                .containsExactly("abc", "5");
    }

    @Test
    void rollbackIsJustAnotherWrite() {
        PropertyId banner = PropertyId.of("banner.text", PropertyType.STRING);
        writer.createIfAbsent(banner, new ConfigValue(PropertyType.STRING, "good"), "t", null);
        writer.write(new ConfigUpdate(banner, "bad", "bob", null));
        ConfigHistoryEntry v1 = history.history(banner, 10).get(1);

        writer.write(new ConfigUpdate(banner, v1.newValue(), "alice", "Reverted to v" + v1.version()));

        assertThat(history.history(banner, 10))
                .extracting(ConfigHistoryEntry::version, ConfigHistoryEntry::oldValue, ConfigHistoryEntry::newValue,
                        ConfigHistoryEntry::comment)
                .first().isEqualTo(tuple(3L, "bad", "good", "Reverted to v1"));
    }

    @Test
    void concurrentWritesGetDistinctSequentialVersions() {
        PropertyId hot = PropertyId.of("hot.value", PropertyType.INT);
        writer.createIfAbsent(hot, intValue(0), "t", null);
        int writes = 20;
        CompletableFuture<?>[] futures = IntStream.rangeClosed(1, writes)
                .mapToObj(i -> CompletableFuture.runAsync(
                        () -> writer.write(new ConfigUpdate(hot, String.valueOf(i), "user" + i, null))))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();

        List<ConfigHistoryEntry> entries = history.history(hot, 100);
        assertThat(entries).extracting(ConfigHistoryEntry::version)
                .containsExactlyElementsOf(IntStream.iterate(writes + 1, v -> v - 1).limit(writes + 1)
                        .mapToObj(Long::valueOf).toList());
        // Each entry's old value is the previous entry's new value: no lost updates
        for (int i = 0; i < entries.size() - 1; i++) {
            assertThat(entries.get(i).oldValue()).isEqualTo(entries.get(i + 1).newValue());
        }
        assertThat(String.valueOf(stored(hot).get("value"))).isEqualTo(entries.get(0).newValue());
    }

    @Test
    void historyIsLimitedAndPerProperty() {
        PropertyId a = PropertyId.of("a.key", PropertyType.STRING);
        writer.createIfAbsent(a, new ConfigValue(PropertyType.STRING, "v1"), "t", null);
        writer.createIfAbsent(PropertyId.of("b.key", PropertyType.STRING), new ConfigValue(PropertyType.STRING, "x"), "t", null);
        for (int i = 2; i <= 5; i++) {
            writer.write(new ConfigUpdate(a, "v" + i, "alice", null));
        }

        assertThat(history.history(a, 2)).extracting(ConfigHistoryEntry::newValue).containsExactly("v5", "v4");
        assertThat(history.history(PropertyId.of("a.key", PropertyType.INT), 10)).isEmpty();
        assertThat(history.history(PropertyId.of("missing.key", PropertyType.STRING), 10)).isEmpty();
    }

    @Test
    void deleteRemovesTheDocumentAndRecordsIt() {
        writer.createIfAbsent(LIMITS, intValue(1), "t", null);

        Optional<ConfigHistoryEntry> entry = writer.delete(new ConfigDeletion(LIMITS, "bob", "retired"));

        assertThat(entry).get().satisfies(e -> {
            assertThat(e.version()).isEqualTo(2);
            assertThat(e.oldValue()).isEqualTo("1");
            assertThat(e.newValue()).isNull();
            assertThat(e.deleted()).isTrue();
            assertThat(e.changedBy()).isEqualTo("bob");
            assertThat(e.comment()).isEqualTo("retired");
        });
        assertThat(history.history(LIMITS, 10)).first().isEqualTo(entry.get());
        assertThat(stored(LIMITS)).isNull();
    }

    @Test
    void deletingAMissingPropertyRecordsNothing() {
        PropertyId missing = PropertyId.of("missing.key", PropertyType.INT);
        assertThat(writer.delete(new ConfigDeletion(missing, "alice", null))).isEmpty();
        assertThat(history.history(missing, 10)).isEmpty();
    }

    @Test
    void aPropertyCreatedAgainAfterDeletionContinuesItsVersions() {
        writer.createIfAbsent(LIMITS, intValue(1), "t", null);
        writer.delete(new ConfigDeletion(LIMITS, "alice", null));

        writer.createIfAbsent(LIMITS, intValue(1), "orders (startup)", "Created from OrdersProperties");

        assertThat(history.history(LIMITS, 10)).extracting(ConfigHistoryEntry::version).containsExactly(3L, 2L, 1L);
        assertThat(stored(LIMITS)).containsEntry("version", 3L);
    }

    @Test
    void indexRejectsDuplicateVersionsOfAProperty() {
        writer.createIfAbsent(LIMITS, intValue(1), "t", null);

        assertThatThrownBy(() -> historyCollection.insertOne(
                new Document("key", "limits.max").append("type", "int").append("version", 1L)))
                .isInstanceOf(MongoWriteException.class);
    }

    @Test
    void ensureIndexesReplacesTheLegacyIndex() {
        historyCollection.drop();
        historyCollection.createIndex(Indexes.compoundIndex(Indexes.ascending("key"), Indexes.descending("version")));

        history.ensureIndexes();
        history.ensureIndexes();

        List<String> names = new ArrayList<>();
        historyCollection.listIndexes().forEach(index -> names.add(index.getString("name")));
        assertThat(names).containsExactlyInAnyOrder("_id_", "key_1_type_1_version_-1");
    }

    private Document stored(PropertyId id) {
        return config.find(byId(id.key(), id.type().typeName())).first();
    }

    /** A history entry as earlier versions wrote it, without a type. */
    private void insertLegacyHistory(String key, long version, String oldValue, String newValue) {
        historyCollection.insertOne(new Document("key", key).append("version", version).append("oldValue", oldValue)
                .append("newValue", newValue).append("changedBy", "t").append("changedAt", new Date()));
    }

    private static ConfigValue intValue(int value) {
        return new ConfigValue(PropertyType.INT, value);
    }
}

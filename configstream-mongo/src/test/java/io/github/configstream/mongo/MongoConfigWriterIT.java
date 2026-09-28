package io.github.configstream.mongo;

import static com.mongodb.client.model.Filters.eq;
import static io.github.configstream.mongo.MongoChangeStreamSourceIT.property;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.github.configstream.api.ConfigDeletion;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigUpdate;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyNotFoundException;
import io.github.configstream.api.PropertyType;
import io.github.configstream.testsupport.TestMongo;
import java.math.BigDecimal;
import java.time.Instant;
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
        PropertyType stored = writer.createIfAbsent("limits.max", intValue(3), "orders (startup)", "Created from OrdersProperties");

        assertThat(stored).isEqualTo(PropertyType.INT);
        assertThat(config.find(eq("_id", "limits.max")).first())
                .containsEntry("type", "int")
                .containsEntry("value", 3)
                .containsEntry("version", 1L);
        assertThat(history.history("limits.max", 10)).singleElement().satisfies(e -> {
            assertThat(e.version()).isEqualTo(1);
            assertThat(e.oldValue()).isNull();
            assertThat(e.newValue()).isEqualTo("3");
            assertThat(e.changedBy()).isEqualTo("orders (startup)");
            assertThat(e.comment()).isEqualTo("Created from OrdersProperties");
        });
    }

    @Test
    void createIfAbsentNeverChangesAnExistingProperty() {
        writer.createIfAbsent("limits.max", intValue(3), "orders (startup)", null);
        writer.write(new ConfigUpdate("limits.max", "50", null, "alice", null));

        PropertyType stored = writer.createIfAbsent("limits.max", intValue(10), "orders (startup)", null);

        assertThat(stored).isEqualTo(PropertyType.INT);
        assertThat(config.find(eq("_id", "limits.max")).first()).containsEntry("value", 50);
        assertThat(history.history("limits.max", 10)).hasSize(2);
    }

    @Test
    void createIfAbsentReportsTheStoredTypeWhenTheManifestDeclaresAnother() {
        writer.createIfAbsent("feature.funds.enabled", new ConfigValue(PropertyType.BOOLEAN, true), "orders (startup)", null);

        PropertyType stored = writer.createIfAbsent("feature.funds.enabled", intValue(3), "orders (startup)", null);

        assertThat(stored).isEqualTo(PropertyType.BOOLEAN);
        assertThat(config.find(eq("_id", "feature.funds.enabled")).first()).containsEntry("value", true);
    }

    @Test
    void instancesStartingTogetherCreateThePropertyOnce() {
        CompletableFuture<?>[] starts = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> writer.createIfAbsent("limits.max", intValue(3), "orders (startup)", null)))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(starts).join();

        assertThat(config.countDocuments()).isEqualTo(1);
        assertThat(history.history("limits.max", 10)).hasSize(1);
    }

    @Test
    void createIfAbsentAdoptsAnUntypedDocumentWhoseValueFits() {
        config.insertOne(new Document("_id", "limits.max").append("value", "25"));

        assertThat(writer.createIfAbsent("limits.max", intValue(3), "orders (startup)", null)).isEqualTo(PropertyType.INT);
        assertThat(config.find(eq("_id", "limits.max")).first())
                .containsEntry("type", "int")
                .containsEntry("value", 25);
    }

    @Test
    void createIfAbsentRefusesAnUntypedDocumentWhoseValueDoesNotFit() {
        config.insertOne(new Document("_id", "limits.max").append("value", "lots"));

        assertThatThrownBy(() -> writer.createIfAbsent("limits.max", intValue(3), "orders (startup)", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'lots' is not a valid int");
    }

    @Test
    void createIfAbsentReplacesWhatAnOldSoftDeleteLeftBehind() {
        // Earlier versions deleted by removing the value but keeping the document and its version
        config.insertOne(new Document("_id", "feature.x.enabled").append("version", 4L));

        assertThat(writer.createIfAbsent("feature.x.enabled", new ConfigValue(PropertyType.BOOLEAN, true), "orders (startup)", null))
                .isEqualTo(PropertyType.BOOLEAN);
        assertThat(config.find(eq("_id", "feature.x.enabled")).first())
                .containsEntry("type", "boolean")
                .containsEntry("value", true)
                .containsEntry("version", 5L);
        assertThat(history.history("feature.x.enabled", 1).get(0).version()).isEqualTo(5);
    }

    @Test
    void storesNativeBsonTypes() {
        writer.createIfAbsent("fees.rate", new ConfigValue(PropertyType.DECIMAL, new BigDecimal("0.10")), "t", null);
        writer.createIfAbsent("feature.x.enabled", new ConfigValue(PropertyType.BOOLEAN, false), "t", null);
        writer.createIfAbsent("banner.text", new ConfigValue(PropertyType.STRING, "007"), "t", null);

        assertThat(config.find(eq("_id", "fees.rate")).first().get("value")).isEqualTo(new Decimal128(new BigDecimal("0.10")));
        assertThat(config.find(eq("_id", "feature.x.enabled")).first().get("value")).isEqualTo(false);
        assertThat(config.find(eq("_id", "banner.text")).first().get("value")).isEqualTo("007");
    }

    @Test
    void recordsEveryChangeWithIncreasingVersions() {
        writer.createIfAbsent("limits.max", intValue(10), "orders (startup)", null);
        Instant before = Instant.now().minusSeconds(1);

        writer.write(new ConfigUpdate("limits.max", "20", null, "bob", "traffic spike"));

        List<ConfigHistoryEntry> entries = history.history("limits.max", 10);
        assertThat(entries).extracting(ConfigHistoryEntry::version).containsExactly(2L, 1L);
        assertThat(entries.get(0)).satisfies(e -> {
            assertThat(e.oldValue()).isEqualTo("10");
            assertThat(e.newValue()).isEqualTo("20");
            assertThat(e.changedBy()).isEqualTo("bob");
            assertThat(e.comment()).isEqualTo("traffic spike");
            assertThat(e.changedAt()).isAfter(before);
        });
        assertThat(config.find(eq("_id", "limits.max")).first())
                .containsEntry("value", 20)
                .containsEntry("version", 2L);
    }

    @Test
    void writeReturnsTheRecordedEntry() {
        writer.createIfAbsent("limits.max", intValue(1), "t", null);

        Optional<ConfigHistoryEntry> entry = writer.write(new ConfigUpdate("limits.max", "2", PropertyType.INT, "alice", null));

        assertThat(entry).isPresent();
        assertThat(history.history("limits.max", 1)).containsExactly(entry.get());
    }

    @Test
    void writingTheSameValueRecordsNothing() {
        writer.createIfAbsent("fees.rate", new ConfigValue(PropertyType.DECIMAL, new BigDecimal("1.0")), "t", null);

        assertThat(writer.write(new ConfigUpdate("fees.rate", "1.00", null, "bob", null))).isEmpty();
        assertThat(history.history("fees.rate", 10)).hasSize(1);
    }

    @Test
    void updatesNeverCreateProperties() {
        assertThatThrownBy(() -> writer.write(new ConfigUpdate("brand.new", "1", null, "alice", null)))
                .isInstanceOf(PropertyNotFoundException.class)
                .hasMessageContaining("created only when a service that declares them starts");
        assertThat(config.countDocuments()).isZero();
    }

    @Test
    void rejectsValuesThatDoNotFitTheType() {
        writer.createIfAbsent("limits.max", intValue(1), "t", null);

        for (String bad : new String[] {"3.5", "abc", ""}) {
            assertThatThrownBy(() -> writer.write(new ConfigUpdate("limits.max", bad, null, "alice", null)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessage("'" + bad + "' is not a valid int.");
        }
        assertThat(history.history("limits.max", 10)).hasSize(1);
    }

    @Test
    void rejectsATypeChange() {
        writer.createIfAbsent("feature.funds.enabled", new ConfigValue(PropertyType.BOOLEAN, false), "t", null);

        assertThatThrownBy(() -> writer.write(new ConfigUpdate("feature.funds.enabled", "3", PropertyType.INT, "alice", null)))
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessage("Type change not allowed. Types are defined in the application's code.");
    }

    @Test
    void aWriteRepairsAValueEditedByHandToSomethingInvalid() {
        config.insertOne(property("limits.max", "int", "abc").append("version", 1L));

        Optional<ConfigHistoryEntry> entry = writer.write(new ConfigUpdate("limits.max", "5", null, "alice", null));

        assertThat(entry).get().extracting(ConfigHistoryEntry::oldValue, ConfigHistoryEntry::newValue)
                .containsExactly("abc", "5");
    }

    @Test
    void rollbackIsJustAnotherWrite() {
        writer.createIfAbsent("banner.text", new ConfigValue(PropertyType.STRING, "good"), "t", null);
        writer.write(new ConfigUpdate("banner.text", "bad", null, "bob", null));
        ConfigHistoryEntry v1 = history.history("banner.text", 10).get(1);

        writer.write(new ConfigUpdate("banner.text", v1.newValue(), null, "alice", "Reverted to v" + v1.version()));

        assertThat(history.history("banner.text", 10))
                .extracting(ConfigHistoryEntry::version, ConfigHistoryEntry::oldValue, ConfigHistoryEntry::newValue,
                        ConfigHistoryEntry::comment)
                .first().isEqualTo(tuple(3L, "bad", "good", "Reverted to v1"));
    }

    @Test
    void concurrentWritesGetDistinctSequentialVersions() {
        writer.createIfAbsent("hot.value", intValue(0), "t", null);
        int writes = 20;
        CompletableFuture<?>[] futures = IntStream.rangeClosed(1, writes)
                .mapToObj(i -> CompletableFuture.runAsync(
                        () -> writer.write(new ConfigUpdate("hot.value", String.valueOf(i), null, "user" + i, null))))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();

        List<ConfigHistoryEntry> entries = history.history("hot.value", 100);
        assertThat(entries).extracting(ConfigHistoryEntry::version)
                .containsExactlyElementsOf(IntStream.iterate(writes + 1, v -> v - 1).limit(writes + 1)
                        .mapToObj(Long::valueOf).toList());
        // Each entry's old value is the previous entry's new value: no lost updates
        for (int i = 0; i < entries.size() - 1; i++) {
            assertThat(entries.get(i).oldValue()).isEqualTo(entries.get(i + 1).newValue());
        }
        assertThat(String.valueOf(config.find(eq("_id", "hot.value")).first().get("value")))
                .isEqualTo(entries.get(0).newValue());
    }

    @Test
    void historyIsLimitedAndPerKey() {
        writer.createIfAbsent("a.key", new ConfigValue(PropertyType.STRING, "v1"), "t", null);
        writer.createIfAbsent("b.key", new ConfigValue(PropertyType.STRING, "x"), "t", null);
        for (int i = 2; i <= 5; i++) {
            writer.write(new ConfigUpdate("a.key", "v" + i, null, "alice", null));
        }

        assertThat(history.history("a.key", 2)).extracting(ConfigHistoryEntry::newValue).containsExactly("v5", "v4");
        assertThat(history.history("missing.key", 10)).isEmpty();
    }

    @Test
    void deleteRemovesTheDocumentAndRecordsIt() {
        writer.createIfAbsent("limits.max", intValue(1), "t", null);

        Optional<ConfigHistoryEntry> entry = writer.delete(new ConfigDeletion("limits.max", "bob", "retired"));

        assertThat(entry).get().satisfies(e -> {
            assertThat(e.version()).isEqualTo(2);
            assertThat(e.oldValue()).isEqualTo("1");
            assertThat(e.newValue()).isNull();
            assertThat(e.deleted()).isTrue();
            assertThat(e.changedBy()).isEqualTo("bob");
            assertThat(e.comment()).isEqualTo("retired");
        });
        assertThat(history.history("limits.max", 10)).first().isEqualTo(entry.get());
        assertThat(config.find(eq("_id", "limits.max")).first()).isNull();
    }

    @Test
    void deletingAMissingPropertyRecordsNothing() {
        assertThat(writer.delete(new ConfigDeletion("missing.key", "alice", null))).isEmpty();
        assertThat(history.history("missing.key", 10)).isEmpty();
    }

    @Test
    void aPropertyCreatedAgainAfterDeletionContinuesItsVersions() {
        writer.createIfAbsent("limits.max", intValue(1), "t", null);
        writer.delete(new ConfigDeletion("limits.max", "alice", null));

        writer.createIfAbsent("limits.max", intValue(1), "orders (startup)", "Created from OrdersProperties");

        assertThat(history.history("limits.max", 10)).extracting(ConfigHistoryEntry::version).containsExactly(3L, 2L, 1L);
        assertThat(config.find(eq("_id", "limits.max")).first()).containsEntry("version", 3L);
    }

    @Test
    void indexRejectsDuplicateVersions() {
        writer.createIfAbsent("limits.max", intValue(1), "t", null);

        assertThatThrownBy(() -> historyCollection.insertOne(new Document("key", "limits.max").append("version", 1L)))
                .isInstanceOf(MongoWriteException.class);
    }

    private static ConfigValue intValue(int value) {
        return new ConfigValue(PropertyType.INT, value);
    }
}

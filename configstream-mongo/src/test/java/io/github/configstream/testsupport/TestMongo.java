package io.github.configstream.testsupport;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.UUID;
import org.testcontainers.containers.MongoDBContainer;

/**
 * The MongoDB replica set integration tests run against. By default a Testcontainers MongoDB (needs Docker, as in
 * CI). Set {@code CONFIGSTREAM_TEST_MONGO_URI} to use an existing replica set instead, such as a free Atlas cluster
 * on a machine without Docker: each test JVM then works in its own {@code cstest_*} database, dropped when it exits.
 * Shared by the other modules through this module's test jar.
 */
public final class TestMongo {

    public static final String URI_ENV = "CONFIGSTREAM_TEST_MONGO_URI";

    private static String uri;
    private static String database;

    private TestMongo() {
    }

    /** The connection string, without a database. */
    public static synchronized String uri() {
        if (uri == null) {
            String external = System.getenv(URI_ENV);
            if (external != null && !external.isBlank()) {
                uri = external;
                database = "cstest_" + UUID.randomUUID().toString().substring(0, 8);
                Runtime.getRuntime().addShutdownHook(new Thread(TestMongo::dropDatabase));
            } else {
                // Stopped by Testcontainers' reaper when the JVM exits
                MongoDBContainer container = new MongoDBContainer("mongo:7.0");
                container.start();
                uri = container.getReplicaSetUrl();
                database = "configstream";
            }
        }
        return uri;
    }

    /** The database tests should use; unique per test JVM when running against an external replica set. */
    public static synchronized String database() {
        uri();
        return database;
    }

    public static MongoClient client() {
        return MongoClients.create(uri());
    }

    private static void dropDatabase() {
        try (MongoClient client = MongoClients.create(uri)) {
            client.getDatabase(database).drop();
        } catch (RuntimeException e) {
            System.err.println("Could not drop test database " + database + ": " + e);
        }
    }
}

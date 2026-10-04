package io.github.configstream.spring;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.github.configstream.api.ConfigCache;
import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.Manifest;
import io.github.configstream.mongo.MongoChangeStreamSource;
import io.github.configstream.mongo.MongoConfigHistory;
import io.github.configstream.mongo.MongoConfigWriter;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * Wires configstream into a Spring Boot application: reads the manifest ({@code configstream.yml}), creates the
 * declared properties missing from the store, loads the store into memory and exposes it as a {@link ConfigService}
 * bean.
 *
 * <p>Applications can replace the backing store by defining their own {@link ConfigChangeSource}
 * bean (plus a {@link ConfigWriter} if they want the internal update endpoint); the Mongo
 * connection is then not created at all.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "configstream", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConfigStreamProperties.class)
public class ConfigStreamAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigStreamAutoConfiguration.class);
    private static final Duration MIN_SOCKET_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnMissingBean
    Manifest configStreamManifest(ConfigStreamProperties properties, ResourceLoader resources) {
        return ManifestLoader.load(resources, properties.getManifest(), properties.getEnvironment());
    }

    /**
     * Creates missing properties, then starts watching the store before returning, so every declared property
     * exists and is loaded by the time any other bean gets the service injected.
     */
    @Bean
    public ConfigService configService(ConfigChangeSource configStreamChangeSource, ObjectProvider<ConfigWriter> writer,
            Manifest manifest, ApplicationEventPublisher publisher, Environment environment) {
        ConfigWriter configWriter = writer.getIfAvailable();
        if (configWriter != null) {
            ManifestSync.createMissing(manifest, configWriter,
                    environment.getProperty("spring.application.name", "application"));
        } else if (!manifest.properties().isEmpty()) {
            log.warn("No ConfigWriter bean, so properties missing from the store are not created; "
                    + "they use their initial values until they exist.");
        }
        ConfigCache cache = new ConfigCache();
        configStreamChangeSource.start(new EventPublishingListener(cache, publisher));
        return new ConfigService(cache, manifest);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(ConfigChangeSource.class)
    static class MongoSourceConfiguration {

        // Deliberately not a MongoClient bean: that would stop Spring Boot from creating the
        // application's own MongoClient, silently pointing its data access at the config store.
        @Bean(destroyMethod = "close")
        ConfigStreamMongoClient configStreamMongoClient(ConfigStreamProperties properties) {
            ConfigStreamProperties.Mongo mongo = properties.getMongo();
            if (mongo.getUri() == null || mongo.getUri().isBlank()) {
                throw new IllegalStateException(
                        "configstream.mongo.uri is not set. Point it at your MongoDB replica set, e.g. "
                                + "mongodb://localhost:27017/mydb?replicaSet=rs0, or set configstream.enabled=false.");
            }
            ConnectionString uri = new ConnectionString(mongo.getUri());
            String database = mongo.getDatabase() != null ? mongo.getDatabase() : uri.getDatabase();
            if (database == null || database.isBlank()) {
                throw new IllegalStateException(
                        "No config database: add it to configstream.mongo.uri (mongodb://host/mydb?...) "
                                + "or set configstream.mongo.database.");
            }
            return new ConfigStreamMongoClient(MongoClients.create(clientSettings(uri, mongo.getSocketTimeout())), database);
        }

        /**
         * The connection settings, with a socket timeout unless the URI sets its own {@code socketTimeoutMS}. The
         * driver's default is no timeout, so a connection dropped silently by a firewall would leave the change stream
         * waiting, possibly for hours, instead of failing and reconnecting. The stream hears from the server about
         * every half second, so the timeout only fires on a dead connection.
         */
        static MongoClientSettings clientSettings(ConnectionString uri, Duration socketTimeout) {
            MongoClientSettings.Builder settings = MongoClientSettings.builder().applyConnectionString(uri);
            if (uri.getSocketTimeout() == null) {
                if (socketTimeout.compareTo(MIN_SOCKET_TIMEOUT) < 0) {
                    throw new IllegalStateException("configstream.mongo.socket-timeout must be at least "
                            + MIN_SOCKET_TIMEOUT.toSeconds() + "s, since the change stream waits up to half a second "
                            + "for each answer; it is " + socketTimeout.toMillis() + "ms.");
                }
                settings.applyToSocketSettings(s -> s.readTimeout(socketTimeout.toMillis(), TimeUnit.MILLISECONDS));
            }
            return settings.build();
        }

        @Bean
        ConfigStreamCollections configStreamCollections(ConfigStreamMongoClient client, ConfigStreamProperties properties,
                Environment environment) {
            ConfigStreamCollections collections = ConfigStreamCollections.resolve(properties.getMongo(),
                    environment.getProperty("spring.application.name"));
            log.info("configstream uses collections '{}' and '{}' in database '{}'", collections.config(),
                    collections.history(), client.database());
            return collections;
        }

        @Bean(destroyMethod = "stop")
        ConfigChangeSource configStreamChangeSource(ConfigStreamMongoClient client, ConfigStreamCollections collections) {
            return new MongoChangeStreamSource(collection(client, collections.config()));
        }

        @Bean
        @ConditionalOnMissingBean(ConfigHistory.class)
        MongoConfigHistory configStreamHistory(ConfigStreamMongoClient client, ConfigStreamCollections collections) {
            MongoConfigHistory history = new MongoConfigHistory(collection(client, collections.history()));
            history.ensureIndexes();
            return history;
        }

        @Bean
        @ConditionalOnMissingBean
        ConfigWriter configStreamWriter(ConfigStreamMongoClient client, ConfigStreamCollections collections,
                MongoConfigHistory history) {
            return new MongoConfigWriter(client.client(), collection(client, collections.config()), history);
        }

        private static MongoCollection<Document> collection(ConfigStreamMongoClient client, String name) {
            return client.client().getDatabase(client.database()).getCollection(name);
        }
    }

    /**
     * The names of a service's two collections. By default they come from the service's name, so services sharing a
     * database never share a collection: {@code orders} uses {@code orders_config} and {@code orders_config_history}.
     */
    record ConfigStreamCollections(String config, String history) {

        static ConfigStreamCollections resolve(ConfigStreamProperties.Mongo mongo, String applicationName) {
            String config = mongo.getConfigCollection();
            if (config == null || config.isBlank()) {
                if (applicationName == null || applicationName.isBlank()) {
                    throw new IllegalStateException("configstream names this service's collections after "
                            + "spring.application.name, which is not set. Set it (e.g. spring.application.name: orders "
                            + "gives orders_config), or set configstream.mongo.config-collection.");
                }
                config = applicationName + "_config";
            }
            String history = mongo.getHistoryCollection();
            if (history == null || history.isBlank()) {
                history = config + "_history";
            }
            if (config.equals(history)) {
                throw new IllegalStateException("configstream.mongo.config-collection and history-collection are both '"
                        + config + "'; they must differ.");
            }
            return new ConfigStreamCollections(config, history);
        }
    }

    /** configstream's own connection, kept out of the application's {@code MongoClient} bean slot. */
    record ConfigStreamMongoClient(MongoClient client, String database) implements AutoCloseable {
        @Override
        public void close() {
            client.close();
        }
    }
}

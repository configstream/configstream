package io.github.configstream.spring;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
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
import org.springframework.beans.factory.ListableBeanFactory;
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
 * <p>The store is the application's own MongoDB, through its {@code MongoClient} bean (normally from
 * {@code spring-boot-starter-data-mongodb}); configstream opens no connection of its own.
 *
 * <p>Applications can replace the backing store by defining their own {@link ConfigChangeSource}
 * bean (plus a {@link ConfigWriter} if they want the internal update endpoint); MongoDB is then not used at all.
 */
// After Spring Boot's MongoDB auto-configuration, which creates the MongoClient: one class name per Boot version
@AutoConfiguration(afterName = {"org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration"})
@ConditionalOnProperty(prefix = "configstream", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConfigStreamProperties.class)
public class ConfigStreamAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigStreamAutoConfiguration.class);

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

        /**
         * The application's own MongoDB connection, shared rather than duplicated: configstream adds no connection pool
         * or server monitoring of its own, only its change stream and occasional writes on the application's pool.
         */
        @Bean
        ConfigStreamDatabase configStreamDatabase(ObjectProvider<MongoClient> clients, ConfigStreamProperties properties,
                Environment environment, ListableBeanFactory beans, ResourceLoader resources) {
            SpringMongoSettings spring = SpringMongoSettings.from(environment, beans, resources.getClassLoader());
            MongoClient client = clients.getIfUnique();
            if (client == null) {
                throw new IllegalStateException("configstream uses your application's MongoClient, but there is no "
                        + "single MongoClient bean. Add spring-boot-starter-data-mongodb and set "
                        + spring.uriProperty() + " (a replica set, e.g. "
                        + "mongodb://localhost:27017/orders?replicaSet=rs0), define a MongoClient bean, or set "
                        + "configstream.enabled=false.");
            }
            String database = properties.getMongo().getDatabase();
            if (database == null || database.isBlank()) {
                database = spring.clientDatabase();
            }
            if (database == null || database.isBlank()) {
                throw new IllegalStateException("No config database: set configstream.mongo.database, or "
                        + spring.databaseProperty() + ".");
            }
            warnIfNoSocketTimeout(spring);
            return new ConfigStreamDatabase(client, database);
        }

        /**
         * The driver waits forever on a connection by default, so a connection a firewall drops silently can leave the
         * change stream stuck until the operating system notices. The application's client is shared, so configstream
         * can't change that itself; it can only point it out.
         */
        private static void warnIfNoSocketTimeout(SpringMongoSettings spring) {
            if (spring.uri() != null && new ConnectionString(spring.uri()).getSocketTimeout() == null) {
                log.warn("{} sets no socketTimeoutMS, so if a firewall silently drops the connection, this instance "
                        + "may miss config changes for a long time before it reconnects. Consider adding "
                        + "socketTimeoutMS=30000 to the URI.", spring.uriProperty());
            }
        }

        @Bean
        ConfigStreamCollections configStreamCollections(ConfigStreamDatabase database, ConfigStreamProperties properties,
                Environment environment) {
            ConfigStreamCollections collections = ConfigStreamCollections.resolve(properties.getMongo(),
                    environment.getProperty("spring.application.name"));
            log.info("configstream uses collections '{}' and '{}' in database '{}'", collections.config(),
                    collections.history(), database.name());
            return collections;
        }

        @Bean(destroyMethod = "stop")
        ConfigChangeSource configStreamChangeSource(ConfigStreamDatabase database, ConfigStreamCollections collections) {
            return new MongoChangeStreamSource(database.collection(collections.config()));
        }

        @Bean
        @ConditionalOnMissingBean(ConfigHistory.class)
        MongoConfigHistory configStreamHistory(ConfigStreamDatabase database, ConfigStreamCollections collections) {
            MongoConfigHistory history = new MongoConfigHistory(database.collection(collections.history()));
            history.ensureIndexes();
            return history;
        }

        @Bean
        @ConditionalOnMissingBean
        ConfigWriter configStreamWriter(ConfigStreamDatabase database, ConfigStreamCollections collections,
                MongoConfigHistory history) {
            return new MongoConfigWriter(database.client(), database.collection(collections.config()), history);
        }
    }

    /**
     * Where configstream keeps its collections: a database on the application's own {@code MongoClient}. Not closed by
     * configstream; the client belongs to the application.
     */
    record ConfigStreamDatabase(MongoClient client, String name) {

        MongoCollection<Document> collection(String collection) {
            return client.getDatabase(name).getCollection(collection);
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
}

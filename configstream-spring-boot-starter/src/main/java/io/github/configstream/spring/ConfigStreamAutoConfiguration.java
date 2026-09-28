package io.github.configstream.spring;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.github.configstream.api.ConfigCache;
import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigWriter;
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
 * Wires configstream into a Spring Boot application: loads the config store into memory, makes every
 * {@link LiveConfig} class live (creating its properties missing from the store), and keeps the values current.
 *
 * <p>Applications can replace the backing store by defining their own {@link ConfigChangeSource}
 * bean (plus a {@link ConfigWriter} if they want properties created and the internal update endpoint); the Mongo
 * connection is then not created at all.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "configstream", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConfigStreamProperties.class)
public class ConfigStreamAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigStreamAutoConfiguration.class);

    /** Loads the stored values right away; watching for changes starts once every bean exists. */
    @Bean
    public ConfigService configService(ConfigChangeSource configStreamChangeSource, ApplicationEventPublisher publisher) {
        ConfigCache cache = new ConfigCache();
        return new ConfigService(cache, configStreamChangeSource, new EventPublishingListener(cache, publisher));
    }

    @Bean
    LiveConfigRegistry configStreamLiveConfigRegistry(ConfigService config, ObjectProvider<ConfigWriter> writer,
            Environment environment, ResourceLoader resources) {
        ConfigWriter configWriter = writer.getIfAvailable();
        if (configWriter == null) {
            log.warn("No ConfigWriter bean, so live properties missing from the store are not created; "
                    + "they use their bound values until they exist.");
        }
        return new LiveConfigRegistry(config, configWriter, environment.getProperty("spring.application.name", "application"),
                PropertyDescriptions.load(resources.getClassLoader()));
    }

    /** Makes each {@link LiveConfig} bean live once Spring has bound it. Static, as bean post-processors should be. */
    @Bean
    static LiveConfigPostProcessor configStreamLiveConfigPostProcessor(ObjectProvider<LiveConfigRegistry> registry) {
        return new LiveConfigPostProcessor(registry);
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
            return new ConfigStreamMongoClient(MongoClients.create(uri), database);
        }

        @Bean(destroyMethod = "stop")
        ConfigChangeSource configStreamChangeSource(ConfigStreamMongoClient client, ConfigStreamProperties properties) {
            return new MongoChangeStreamSource(configCollection(client, properties));
        }

        @Bean
        @ConditionalOnMissingBean(ConfigHistory.class)
        MongoConfigHistory configStreamHistory(ConfigStreamMongoClient client, ConfigStreamProperties properties) {
            ConfigStreamProperties.Mongo mongo = properties.getMongo();
            String name = mongo.getHistoryCollection() != null
                    ? mongo.getHistoryCollection()
                    : mongo.getCollection() + "_history";
            MongoConfigHistory history = new MongoConfigHistory(collection(client, name));
            history.ensureIndexes();
            return history;
        }

        @Bean
        @ConditionalOnMissingBean
        ConfigWriter configStreamWriter(ConfigStreamMongoClient client, ConfigStreamProperties properties,
                MongoConfigHistory history) {
            return new MongoConfigWriter(client.client(), configCollection(client, properties), history);
        }

        private static MongoCollection<Document> configCollection(
                ConfigStreamMongoClient client, ConfigStreamProperties properties) {
            return collection(client, properties.getMongo().getCollection());
        }

        private static MongoCollection<Document> collection(ConfigStreamMongoClient client, String name) {
            return client.client().getDatabase(client.database()).getCollection(name);
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

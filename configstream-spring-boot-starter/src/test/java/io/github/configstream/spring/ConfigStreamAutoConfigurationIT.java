package io.github.configstream.spring;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static io.github.configstream.spring.ConfigServiceTest.FUNDS_ENABLED;
import static io.github.configstream.spring.ConfigServiceTest.FUNDS_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.github.configstream.testsupport.TestMongo;
import java.time.Duration;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ConfigStreamAutoConfigurationIT {

    private static final Duration PROPAGATION = Duration.ofSeconds(3);

    static MongoClient client;

    String collectionName;
    MongoCollection<Document> collection;

    @BeforeAll
    static void connect() {
        client = TestMongo.client();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void freshCollection() {
        collectionName = "flags_" + UUID.randomUUID();
        collection = client.getDatabase(TestMongo.database()).getCollection(collectionName);
    }

    @Test
    void createsDeclaredPropertiesOnStartupAndReflectsLiveUpdates() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ConfigService config = context.getBean(ConfigService.class);
            var events = context.getBean(ConfigStreamAutoConfigurationTest.EventCollector.class).events;

            assertThat(collection.find(eq("_id", "feature.funds.limit")).first())
                    .containsEntry("type", "int").containsEntry("value", 3).containsEntry("version", 1L);
            assertThat(config.get(FUNDS_LIMIT)).isEqualTo(3);
            assertThat(config.get(FUNDS_ENABLED)).isFalse();
            assertThat(events).as("creating properties on startup publishes nothing").isEmpty();

            collection.updateOne(eq("_id", "feature.funds.limit"), set("value", 7));

            await().atMost(PROPAGATION).untilAsserted(() -> {
                assertThat(config.get(FUNDS_LIMIT)).isEqualTo(7);
                assertThat(events).containsExactly(new ConfigChangedEvent("feature.funds.limit", 3, 7));
            });
        });
    }

    @Test
    void laterStartsNeverOverwriteValues() {
        runner().run(context -> assertThat(context).hasNotFailed());
        collection.updateOne(eq("_id", "feature.funds.limit"), set("value", 50));

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ConfigService.class).get(FUNDS_LIMIT)).isEqualTo(50);
        });
        assertThat(history().countDocuments(eq("key", "feature.funds.limit"))).isEqualTo(1);
    }

    @Test
    void anEnvironmentFileSetsTheInitialValue() {
        runner().withPropertyValues("configstream.environment=prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(collection.find(eq("_id", "feature.funds.limit")).first()).containsEntry("value", 10);
            assertThat(history().find(eq("key", "feature.funds.limit")).first())
                    .containsEntry("comment", "Created from configstream-prod.yml");
        });
    }

    @Test
    void usesTheApplicationsClientAndDatabaseAndNamesCollectionsAfterTheService() {
        String service = "svc_" + UUID.randomUUID().toString().substring(0, 8);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class, ConfigStreamAutoConfiguration.class))
                .withPropertyValues(
                        "spring.application.name=" + service,
                        "spring.data.mongodb.uri=" + TestMongo.uri(),
                        "spring.data.mongodb.database=" + TestMongo.database(),
                        "configstream.manifest=classpath:manifests/configstream.yml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // The application's client is the only one: configstream opens no connection of its own
                    assertThat(context).hasSingleBean(com.mongodb.client.MongoClient.class);
                    var database = client.getDatabase(TestMongo.database());
                    assertThat(database.getCollection(service + "_config")
                            .find(eq("_id", "feature.funds.limit")).first()).isNotNull();
                    assertThat(database.getCollection(service + "_config_history")
                            .countDocuments(eq("key", "feature.funds.limit"))).isEqualTo(1);
                });
    }

    @Test
    void aTypeChangeStopsStartup() {
        collection.insertOne(new Document("_id", "feature.funds.limit").append("type", "string").append("value", "3"));

        runner().run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("'feature.funds.limit' is stored as string but declared as int"));
    }

    @Test
    void internalEndpointWritesThroughToMongoAndBackIntoTheCache() {
        String secret = "0123456789abcdef-it-secret";
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class,
                        ConfigStreamAutoConfiguration.class, ConfigStreamEndpointAutoConfiguration.class,
                        WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withPropertyValues(properties())
                .withPropertyValues("configstream.internal.secret=" + secret)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders
                            .webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext())
                            .build();

                    for (String value : new String[] {"50", "80"}) {
                        mvc.perform(post("/internal/config/update")
                                        .header(InternalConfigController.SECRET_HEADER, secret)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"key\":\"feature.funds.limit\",\"value\":\"" + value
                                                + "\",\"type\":\"int\",\"changedBy\":\"alice\"}"))
                                .andExpect(status().isOk());
                    }

                    assertThat(collection.find(eq("_id", "feature.funds.limit")).first()).containsEntry("value", 80);
                    ConfigService config = context.getBean(ConfigService.class);
                    await().atMost(PROPAGATION).until(() -> config.get(FUNDS_LIMIT) == 80);

                    // History lands in <collection>_history and is served newest first, after the manifest's v1
                    mvc.perform(get("/internal/config/history")
                                    .header(InternalConfigController.SECRET_HEADER, secret)
                                    .param("key", "feature.funds.limit"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.length()").value(3))
                            .andExpect(jsonPath("$[0].version").value(3))
                            .andExpect(jsonPath("$[0].oldValue").value("50"))
                            .andExpect(jsonPath("$[0].newValue").value("80"))
                            .andExpect(jsonPath("$[0].changedBy").value("alice"))
                            .andExpect(jsonPath("$[2].changedBy").value("application (manifest)"));
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class, ConfigStreamAutoConfiguration.class))
                .withBean(ConfigStreamAutoConfigurationTest.EventCollector.class)
                .withPropertyValues(properties());
    }

    private String[] properties() {
        return new String[] {
                "spring.data.mongodb.uri=" + TestMongo.uri(),
                "configstream.mongo.database=" + TestMongo.database(),
                "configstream.mongo.config-collection=" + collectionName,
                "configstream.manifest=classpath:manifests/configstream.yml"};
    }

    private MongoCollection<Document> history() {
        return client.getDatabase(TestMongo.database()).getCollection(collectionName + "_history");
    }
}

package io.github.configstream.spring;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.github.configstream.api.PropertyType;
import io.github.configstream.testsupport.TestMongo;
import java.time.Duration;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ConfigStreamAutoConfigurationIT {

    private static final Duration PROPAGATION = Duration.ofSeconds(3);
    private static final Bson LIMIT_ID = eq("_id", new Document("key", "feature.funds.limit").append("type", "int"));

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
            FundsProperties funds = context.getBean(FundsProperties.class);
            var events = context.getBean(ConfigStreamAutoConfigurationTest.EventCollector.class).events;

            assertThat(collection.find(LIMIT_ID).first())
                    .containsEntry("value", 3).containsEntry("version", 1L);
            assertThat(funds.getLimit()).isEqualTo(3);
            assertThat(funds.isEnabled()).isFalse();
            assertThat(events).as("creating properties on startup publishes nothing").isEmpty();

            collection.updateOne(LIMIT_ID, set("value", 7));

            await().atMost(PROPAGATION).untilAsserted(() -> {
                assertThat(funds.getLimit()).isEqualTo(7);
                assertThat(events).containsExactly(new ConfigChangedEvent("feature.funds.limit", PropertyType.INT, 3, 7));
            });
        });
    }

    @Test
    void laterStartsNeverOverwriteValues() {
        runner().run(context -> assertThat(context).hasNotFailed());
        collection.updateOne(LIMIT_ID, set("value", 50));

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FundsProperties.class).getLimit()).isEqualTo(50);
        });
        assertThat(history().countDocuments(eq("key", "feature.funds.limit"))).isEqualTo(1);
    }

    @Test
    void theStartingValueIsWhatSpringBound() {
        // As application-prod.yml would set it when the prod profile is active
        runner().withPropertyValues("feature.funds.limit=10").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(collection.find(LIMIT_ID).first()).containsEntry("value", 10);
            assertThat(history().find(eq("key", "feature.funds.limit")).first())
                    .containsEntry("comment", "Created from FundsProperties");
        });
    }

    @Test
    void aTypeChangeCreatesANewPropertyWhileTheOldVersionKeepsItsOwn() {
        // Blue-green: the old version declares the limit as text, the new one as int, both running at once
        Document oldId = new Document("key", "feature.funds.limit").append("type", "string");
        bare().withUserConfiguration(OldFundsConfig.class).run(oldVersion -> {
            assertThat(oldVersion).hasNotFailed();
            OldFundsProperties oldFunds = oldVersion.getBean(OldFundsProperties.class);

            runner().run(newVersion -> {
                assertThat(newVersion).hasNotFailed();
                FundsProperties funds = newVersion.getBean(FundsProperties.class);
                assertThat(collection.find(eq("_id", oldId)).first()).containsEntry("value", "lots");
                assertThat(collection.find(LIMIT_ID).first()).containsEntry("value", 3);

                // A change to either reaches only the version declaring it
                collection.updateOne(eq("_id", oldId), set("value", "plenty"));
                collection.updateOne(LIMIT_ID, set("value", 9));
                await().atMost(PROPAGATION).untilAsserted(() -> {
                    assertThat(oldFunds.getLimit()).isEqualTo("plenty");
                    assertThat(funds.getLimit()).isEqualTo(9);
                });
            });
        });
    }

    @Test
    void aLegacyDocumentIsMovedToTheKeyAndTypeShapeOnStartup() {
        collection.insertOne(new Document("_id", "feature.funds.limit").append("type", "int").append("value", 12)
                .append("version", 4L));

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FundsProperties.class).getLimit()).isEqualTo(12);
            assertThat(collection.find(eq("_id", "feature.funds.limit")).first()).isNull();
            assertThat(collection.find(LIMIT_ID).first()).containsEntry("value", 12).containsEntry("version", 4L);
        });
    }

    @Test
    void internalEndpointWritesThroughToMongoAndBackIntoTheCache() {
        String secret = "0123456789abcdef-it-secret";
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigStreamAutoConfiguration.class, ConfigStreamEndpointAutoConfiguration.class,
                        WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withUserConfiguration(ConfigStreamAutoConfigurationTest.FundsConfig.class)
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
                                        .content("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"value\":\"" + value
                                                + "\",\"changedBy\":\"alice\"}"))
                                .andExpect(status().isOk());
                    }

                    assertThat(collection.find(LIMIT_ID).first()).containsEntry("value", 80);
                    FundsProperties funds = context.getBean(FundsProperties.class);
                    await().atMost(PROPAGATION).until(() -> funds.getLimit() == 80);

                    // History lands in <collection>_history and is served newest first, after the v1 created on startup
                    mvc.perform(get("/internal/config/history")
                                    .header(InternalConfigController.SECRET_HEADER, secret)
                                    .param("key", "feature.funds.limit").param("type", "int"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.length()").value(3))
                            .andExpect(jsonPath("$[0].version").value(3))
                            .andExpect(jsonPath("$[0].oldValue").value("50"))
                            .andExpect(jsonPath("$[0].newValue").value("80"))
                            .andExpect(jsonPath("$[0].changedBy").value("alice"))
                            .andExpect(jsonPath("$[2].changedBy").value("application (startup)"));
                });
    }

    /** The application with FundsProperties. */
    private ApplicationContextRunner runner() {
        return bare().withUserConfiguration(ConfigStreamAutoConfigurationTest.FundsConfig.class);
    }

    /** The application without live classes. */
    private ApplicationContextRunner bare() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigStreamAutoConfiguration.class))
                .withBean(ConfigStreamAutoConfigurationTest.EventCollector.class)
                .withPropertyValues(properties());
    }

    /** An older version of FundsProperties, which declared the limit as text. */
    @ConfigurationProperties("feature.funds")
    @LiveConfig
    public static class OldFundsProperties {
        private String limit = "lots";

        public String getLimit() {
            return limit;
        }

        public void setLimit(String limit) {
            this.limit = limit;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OldFundsProperties.class)
    static class OldFundsConfig {
    }

    private String[] properties() {
        return new String[] {
                "configstream.mongo.uri=" + TestMongo.uri(),
                "configstream.mongo.database=" + TestMongo.database(),
                "configstream.mongo.collection=" + collectionName};
    }

    private MongoCollection<Document> history() {
        return client.getDatabase(TestMongo.database()).getCollection(collectionName + "_history");
    }
}

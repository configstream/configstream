package io.github.configstream.spring;

import static io.github.configstream.spring.ConfigStreamAutoConfigurationTest.LIMIT;
import static io.github.configstream.spring.ConfigStreamAutoConfigurationTest.LIMIT_AS_TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.PropertyId;
import io.github.configstream.api.PropertyType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ConfigStreamEndpointAutoConfigurationTest {

    private static final String SECRET = "0123456789abcdef-test-secret";
    private static final PropertyId OLD_FLAG = PropertyId.of("feature.old.flag", PropertyType.BOOLEAN);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigStreamAutoConfiguration.class, ConfigStreamEndpointAutoConfiguration.class,
                    WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            // FundsProperties declares feature.funds.enabled (boolean), feature.funds.limit (int, initially 3) and
            // feature.funds.discount-rate (decimal)
            .withUserConfiguration(ConfigStreamAutoConfigurationTest.FakeSourceConfig.class,
                    ConfigStreamAutoConfigurationTest.FakeStoreConfig.class, ConfigStreamAutoConfigurationTest.FundsConfig.class);

    @Test
    void offUnlessSecretIsSet() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(InternalConfigController.class));
    }

    @Test
    void offInNonWebApps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigStreamAutoConfiguration.class, ConfigStreamEndpointAutoConfiguration.class))
                .withUserConfiguration(ConfigStreamAutoConfigurationTest.FakeSourceConfig.class,
                        ConfigStreamAutoConfigurationTest.FakeStoreConfig.class)
                .withPropertyValues("configstream.internal.secret=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(InternalConfigController.class));
    }

    @Test
    void offWithoutAWriter() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigStreamAutoConfiguration.class, ConfigStreamEndpointAutoConfiguration.class))
                .withUserConfiguration(ConfigStreamAutoConfigurationTest.FakeSourceConfig.class)
                .withPropertyValues("configstream.internal.secret=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(InternalConfigController.class));
    }

    @Test
    void rejectsShortSecret() {
        runner.withPropertyValues("configstream.internal.secret=short")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("at least 16 characters"));
    }

    @Test
    void requiresTheSecretHeader() {
        runWithEndpoint((mvc, store) -> {
            String body = "{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"value\":\"5\",\"changedBy\":\"alice\"}";
            expect(mvc, update(body), status().isUnauthorized());
            expect(mvc, update(body).header(InternalConfigController.SECRET_HEADER, SECRET + "x"),
                    status().isUnauthorized());
            expect(mvc, get("/internal/config/history").param("key", "feature.funds.limit").param("type", "int"),
                    status().isUnauthorized());
            assertThat(store.values.get(LIMIT)).isEqualTo(new ConfigValue(PropertyType.INT, 3));
        });
    }

    @Test
    void updatesAValueAndReturnsTheHistoryEntry() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"value\":\"20\","
                            + "\"changedBy\":\"alice\",\"comment\":\"launch\"}")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("feature.funds.limit"))
                    .andExpect(jsonPath("$.type").value("INT"))
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.oldValue").value("3"))
                    .andExpect(jsonPath("$.newValue").value("20"))
                    .andExpect(jsonPath("$.changedBy").value("alice"))
                    .andExpect(jsonPath("$.changedAt").value("2026-09-25T10:00:00Z"))
                    .andExpect(jsonPath("$.comment").value("launch"));
            assertThat(store.values.get(LIMIT)).isEqualTo(new ConfigValue(PropertyType.INT, 20));
        });
    }

    @Test
    void updatesTheSameKeyWithAnotherTypeSeparately() {
        runWithEndpoint((mvc, store) -> {
            // Created by an older version of the service that declared the limit as text
            store.createIfAbsent(LIMIT_AS_TEXT, new ConfigValue(PropertyType.STRING, "lots"), "orders (startup)", null);

            expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"string\",\"value\":\"many\","
                    + "\"changedBy\":\"alice\"}")), status().isOk());

            assertThat(store.values.get(LIMIT_AS_TEXT)).isEqualTo(new ConfigValue(PropertyType.STRING, "many"));
            assertThat(store.values.get(LIMIT)).isEqualTo(new ConfigValue(PropertyType.INT, 3));
        });
    }

    @Test
    void unchangedValueReturnsNoContent() {
        runWithEndpoint((mvc, store) -> expect(mvc,
                authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"value\":\"3\",\"changedBy\":\"alice\"}")),
                status().isNoContent()));
    }

    @Test
    void neverCreatesAProperty() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(update("{\"key\":\"brand.new\",\"type\":\"int\",\"value\":\"1\",\"changedBy\":\"alice\"}")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("No property 'brand.new' of type int. Properties are created only "
                            + "when a service that declares them starts."));
            // Nor a property of another type for an existing key
            mvc.perform(authorized(update("{\"key\":\"feature.funds.enabled\",\"type\":\"int\",\"value\":\"3\","
                            + "\"changedBy\":\"alice\"}")))
                    .andExpect(status().isNotFound());
            assertThat(store.values.keySet()).noneMatch(id -> id.key().equals("brand.new") || id.type() == PropertyType.INT
                    && id.key().equals("feature.funds.enabled"));
        });
    }

    @Test
    void rejectsValuesThatDoNotFitTheTypeWithAMessage() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"value\":\"3.5\","
                            + "\"changedBy\":\"alice\"}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("'3.5' is not a valid int."));
            mvc.perform(authorized(update("{\"key\":\"feature.funds.enabled\",\"type\":\"boolean\",\"value\":\"yes\","
                            + "\"changedBy\":\"alice\"}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("'yes' is not a valid boolean."));
        });
    }

    @Test
    void rejectsAnUnknownType() {
        runWithEndpoint((mvc, store) -> mvc.perform(authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"long\","
                        + "\"value\":\"3\",\"changedBy\":\"alice\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("Unknown property type 'long'"))));
    }

    @Test
    void rejectsIncompleteRequests() {
        runWithEndpoint((mvc, store) -> {
            expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"changedBy\":\"alice\"}")),
                    status().isBadRequest());
            expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"value\":\"1\",\"changedBy\":\"alice\"}")),
                    status().isBadRequest());
            expect(mvc, authorized(update("{\"key\":\" \",\"type\":\"int\",\"value\":\"1\",\"changedBy\":\"alice\"}")),
                    status().isBadRequest());
            expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"value\":\"1\"}")),
                    status().isBadRequest());
            expect(mvc, authorized(post("/internal/config/update")), status().isBadRequest());
            assertThat(store.history(LIMIT, 10)).hasSize(1);
        });
    }

    @Test
    void deletesAnOrphanAndReturnsTheHistoryEntry() {
        runWithEndpoint((mvc, store) -> {
            store.createIfAbsent(OLD_FLAG, new ConfigValue(PropertyType.BOOLEAN, true), "orders (startup)", null);
            String body = "{\"key\":\"feature.old.flag\",\"type\":\"boolean\",\"changedBy\":\"bob\",\"comment\":\"retired\"}";

            expect(mvc, delete(body), status().isUnauthorized());
            mvc.perform(authorized(delete(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("feature.old.flag"))
                    .andExpect(jsonPath("$.type").value("BOOLEAN"))
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.oldValue").value("true"))
                    .andExpect(jsonPath("$.newValue").doesNotExist())
                    .andExpect(jsonPath("$.changedBy").value("bob"))
                    .andExpect(jsonPath("$.comment").value("retired"));
            assertThat(store.values).doesNotContainKey(OLD_FLAG);

            expect(mvc, authorized(delete(body)), status().isNoContent()); // already gone
        });
    }

    @Test
    void refusesToDeleteAPropertyThisInstanceDeclares() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(delete("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"changedBy\":\"bob\"}")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("'feature.funds.limit' (int) is declared by this service, so it "
                            + "is in use and can't be deleted. Remove it from the service's @LiveConfig class first."));
            assertThat(store.values).containsKey(LIMIT);
        });
    }

    @Test
    void deletesTheSameKeyWithTheTypeThisInstanceNoLongerDeclares() {
        runWithEndpoint((mvc, store) -> {
            store.createIfAbsent(LIMIT_AS_TEXT, new ConfigValue(PropertyType.STRING, "lots"), "orders (startup)", null);

            expect(mvc, authorized(delete("{\"key\":\"feature.funds.limit\",\"type\":\"string\",\"changedBy\":\"bob\"}")),
                    status().isOk());

            assertThat(store.values).doesNotContainKey(LIMIT_AS_TEXT).containsKey(LIMIT);
        });
    }

    @Test
    void rejectsIncompleteDeletes() {
        runWithEndpoint((mvc, store) -> {
            expect(mvc, authorized(delete("{\"key\":\"feature.old.flag\",\"type\":\"boolean\"}")), status().isBadRequest());
            expect(mvc, authorized(delete("{\"key\":\"feature.old.flag\",\"changedBy\":\"alice\"}")), status().isBadRequest());
            expect(mvc, authorized(delete("{\"key\":\" \",\"type\":\"boolean\",\"changedBy\":\"alice\"}")),
                    status().isBadRequest());
            expect(mvc, authorized(delete("{\"key\":\"feature.old.flag\",\"type\":\"list\",\"changedBy\":\"alice\"}")),
                    status().isBadRequest());
            expect(mvc, authorized(post("/internal/config/delete")), status().isBadRequest());
        });
    }

    @Test
    void historyReturnsNewestFirstWithLimit() {
        runWithEndpoint((mvc, store) -> {
            for (int i = 1; i <= 2; i++) {
                expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"type\":\"int\",\"value\":\"" + (10 * i)
                        + "\",\"changedBy\":\"alice\"}")), status().isOk());
            }

            mvc.perform(authorized(history("feature.funds.limit", "int")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].version").value(3))
                    .andExpect(jsonPath("$[0].oldValue").value("10"))
                    .andExpect(jsonPath("$[2].comment").value("Created from FundsProperties"));
            mvc.perform(authorized(history("feature.funds.limit", "int").param("limit", "1")))
                    .andExpect(jsonPath("$.length()").value(1));
            mvc.perform(authorized(history("feature.funds.limit", "string")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            mvc.perform(authorized(history("missing.key", "int")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        });
    }

    @Test
    void currentReturnsThisInstancesValuesWithTheirTypes() {
        runWithEndpoint((mvc, store) -> {
            expect(mvc, get("/internal/config"), status().isUnauthorized());
            mvc.perform(authorized(get("/internal/config")))
                    .andExpect(status().isOk())
                    // FakeSource's snapshot
                    .andExpect(jsonPath("$[0].key").value("a.key"))
                    .andExpect(jsonPath("$[0].type").value("int"))
                    .andExpect(jsonPath("$[0].value").value("1"));
        });
    }

    @Test
    void historyValidatesParameters() {
        runWithEndpoint((mvc, store) -> {
            expect(mvc, authorized(get("/internal/config/history")), status().isBadRequest());
            expect(mvc, authorized(get("/internal/config/history").param("key", "a.key")), status().isBadRequest());
            expect(mvc, authorized(history("a.key", "list")), status().isBadRequest());
            expect(mvc, authorized(history("a.key", "int").param("limit", "0")), status().isBadRequest());
        });
    }

    private void runWithEndpoint(EndpointTest test) {
        runner.withPropertyValues("configstream.internal.secret=" + SECRET).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(InternalConfigController.class);
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext()).build();
            test.run(mvc, context.getBean(FakeConfigStore.class));
        });
    }

    private static MockHttpServletRequestBuilder update(String json) {
        return post("/internal/config/update").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static MockHttpServletRequestBuilder delete(String json) {
        return post("/internal/config/delete").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static MockHttpServletRequestBuilder history(String key, String type) {
        return get("/internal/config/history").param("key", key).param("type", type);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header(InternalConfigController.SECRET_HEADER, SECRET);
    }

    private static void expect(MockMvc mvc, MockHttpServletRequestBuilder request, ResultMatcher matcher) throws Exception {
        mvc.perform(request).andExpect(matcher);
    }

    interface EndpointTest {
        void run(MockMvc mvc, FakeConfigStore store) throws Exception;
    }
}

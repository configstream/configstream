package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.configstream.api.ConfigValue;
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
    private static final String ADMIN = "configstream-admin";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigStreamAutoConfiguration.class, ConfigStreamEndpointAutoConfiguration.class,
                    WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            .withUserConfiguration(ConfigStreamAutoConfigurationTest.FakeSourceConfig.class,
                    ConfigStreamAutoConfigurationTest.FakeStoreConfig.class)
            // Declares feature.funds.enabled (boolean) and feature.funds.limit (int, initially 3)
            .withPropertyValues("configstream.manifest=classpath:manifests/configstream.yml");

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
            String body = "{\"key\":\"feature.funds.limit\",\"value\":\"5\",\"changedBy\":\"alice\"}";
            expect(mvc, update(body), status().isUnauthorized());
            expect(mvc, update(body).header(InternalConfigController.SECRET_HEADER, SECRET + "x"),
                    status().isUnauthorized());
            expect(mvc, get("/internal/config/history").param("key", "feature.funds.limit"), status().isUnauthorized());
            assertThat(store.values.get("feature.funds.limit")).isEqualTo(new ConfigValue(PropertyType.INT, 3));
        });
    }

    @Test
    void acceptsTheAdminServersTokenWithoutASecret() {
        runWithEndpoint("configstream.internal.admin-principal=" + ADMIN, (mvc, store) -> {
            expect(mvc, get("/internal/config").principal(() -> ADMIN), status().isOk());
            expect(mvc, update("{\"key\":\"feature.funds.limit\",\"value\":\"5\",\"changedBy\":\"alice\"}")
                    .principal(() -> ADMIN), status().isOk());
            assertThat(store.values.get("feature.funds.limit")).isEqualTo(new ConfigValue(PropertyType.INT, 5));
            // No secret is configured, so the header opens nothing
            expect(mvc, get("/internal/config").header(InternalConfigController.SECRET_HEADER, SECRET),
                    status().isUnauthorized());
            expect(mvc, get("/internal/config"), status().isUnauthorized());
        });
    }

    @Test
    void refusesCallersAuthenticatedAsSomeoneElse() {
        runWithEndpoint("configstream.internal.admin-principal=" + ADMIN, (mvc, store) -> {
            mvc.perform(update("{\"key\":\"feature.funds.limit\",\"value\":\"5\",\"changedBy\":\"alice\"}")
                            .principal(() -> "payments"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("The caller is authenticated as 'payments', which isn't this "
                            + "service's configstream.internal.admin-principal, so it can't use these endpoints."));
            expect(mvc, get("/internal/config/history").param("key", "feature.funds.limit").principal(() -> "payments"),
                    status().isForbidden());
            assertThat(store.values.get("feature.funds.limit")).isEqualTo(new ConfigValue(PropertyType.INT, 3));
        });
    }

    @Test
    void theSecretStillWorksAlongsideTokens() {
        runner.withPropertyValues("configstream.internal.secret=" + SECRET, "configstream.internal.admin-principal=" + ADMIN)
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                            (WebApplicationContext) context.getSourceApplicationContext()).build();
                    expect(mvc, authorized(get("/internal/config")), status().isOk());
                    expect(mvc, get("/internal/config").principal(() -> ADMIN), status().isOk());
                    expect(mvc, get("/internal/config"), status().isUnauthorized());
                });
    }

    @Test
    void rejectsBlankSettings() {
        runner.withPropertyValues("configstream.internal.admin-principal= ")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("must have a value"));
    }

    @Test
    void updatesAValueAndReturnsTheHistoryEntry() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(update("{\"key\":\"feature.funds.limit\",\"value\":\"20\",\"type\":\"int\","
                            + "\"changedBy\":\"alice\",\"comment\":\"launch\"}")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("feature.funds.limit"))
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.oldValue").value("3"))
                    .andExpect(jsonPath("$.newValue").value("20"))
                    .andExpect(jsonPath("$.changedBy").value("alice"))
                    .andExpect(jsonPath("$.changedAt").value("2026-09-25T10:00:00Z"))
                    .andExpect(jsonPath("$.comment").value("launch"));
            assertThat(store.values.get("feature.funds.limit")).isEqualTo(new ConfigValue(PropertyType.INT, 20));
        });
    }

    @Test
    void unchangedValueReturnsNoContent() {
        runWithEndpoint((mvc, store) -> expect(mvc,
                authorized(update("{\"key\":\"feature.funds.limit\",\"value\":\"3\",\"changedBy\":\"alice\"}")),
                status().isNoContent()));
    }

    @Test
    void neverCreatesAProperty() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(update("{\"key\":\"brand.new\",\"value\":\"1\",\"changedBy\":\"alice\"}")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("No property 'brand.new'. Properties are created only from "
                            + "the application manifest (configstream.yml)."));
            assertThat(store.values).doesNotContainKey("brand.new");
        });
    }

    @Test
    void rejectsValuesThatDoNotFitTheTypeWithAMessage() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(update("{\"key\":\"feature.funds.limit\",\"value\":\"3.5\",\"changedBy\":\"alice\"}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("'3.5' is not a valid int."));
            mvc.perform(authorized(update("{\"key\":\"feature.funds.enabled\",\"value\":\"yes\",\"changedBy\":\"alice\"}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("'yes' is not a valid boolean."));
        });
    }

    @Test
    void rejectsATypeChange() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(update("{\"key\":\"feature.funds.enabled\",\"value\":\"3\",\"type\":\"int\","
                            + "\"changedBy\":\"alice\"}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Type change not allowed. Types are defined in the application manifest."));
            mvc.perform(authorized(update("{\"key\":\"feature.funds.limit\",\"value\":\"3\",\"type\":\"long\","
                            + "\"changedBy\":\"alice\"}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(startsWith("Unknown property type 'long'")));
        });
    }

    @Test
    void rejectsIncompleteRequests() {
        runWithEndpoint((mvc, store) -> {
            expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"changedBy\":\"alice\"}")), status().isBadRequest());
            expect(mvc, authorized(update("{\"key\":\" \",\"value\":\"1\",\"changedBy\":\"alice\"}")), status().isBadRequest());
            expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"value\":\"1\"}")), status().isBadRequest());
            expect(mvc, authorized(post("/internal/config/update")), status().isBadRequest());
            assertThat(store.history("feature.funds.limit", 10)).hasSize(1);
        });
    }

    @Test
    void deletesAnOrphanAndReturnsTheHistoryEntry() {
        runWithEndpoint((mvc, store) -> {
            store.createIfAbsent("feature.old.flag", new ConfigValue(PropertyType.BOOLEAN, true), "orders (manifest)", null);
            String body = "{\"key\":\"feature.old.flag\",\"changedBy\":\"bob\",\"comment\":\"retired\"}";

            expect(mvc, delete(body), status().isUnauthorized());
            mvc.perform(authorized(delete(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("feature.old.flag"))
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.oldValue").value("true"))
                    .andExpect(jsonPath("$.newValue").doesNotExist())
                    .andExpect(jsonPath("$.changedBy").value("bob"))
                    .andExpect(jsonPath("$.comment").value("retired"));
            assertThat(store.values).doesNotContainKey("feature.old.flag");

            expect(mvc, authorized(delete(body)), status().isNoContent()); // already gone
        });
    }

    @Test
    void refusesToDeleteAPropertyThisInstanceDeclares() {
        runWithEndpoint((mvc, store) -> {
            mvc.perform(authorized(delete("{\"key\":\"feature.funds.limit\",\"changedBy\":\"bob\"}")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("'feature.funds.limit' is declared in this service's manifest, "
                            + "so it is in use and can't be deleted. Remove it from configstream.yml first."));
            assertThat(store.values).containsKey("feature.funds.limit");
        });
    }

    @Test
    void rejectsIncompleteDeletes() {
        runWithEndpoint((mvc, store) -> {
            expect(mvc, authorized(delete("{\"key\":\"feature.old.flag\"}")), status().isBadRequest());
            expect(mvc, authorized(delete("{\"key\":\" \",\"changedBy\":\"alice\"}")), status().isBadRequest());
            expect(mvc, authorized(post("/internal/config/delete")), status().isBadRequest());
        });
    }

    @Test
    void historyReturnsNewestFirstWithLimit() {
        runWithEndpoint((mvc, store) -> {
            for (int i = 1; i <= 2; i++) {
                expect(mvc, authorized(update("{\"key\":\"feature.funds.limit\",\"value\":\"" + (10 * i)
                        + "\",\"changedBy\":\"alice\"}")), status().isOk());
            }

            mvc.perform(authorized(get("/internal/config/history").param("key", "feature.funds.limit")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].version").value(3))
                    .andExpect(jsonPath("$[0].oldValue").value("10"))
                    .andExpect(jsonPath("$[2].comment").value("Created from configstream.yml"));
            mvc.perform(authorized(get("/internal/config/history").param("key", "feature.funds.limit").param("limit", "1")))
                    .andExpect(jsonPath("$.length()").value(1));
            mvc.perform(authorized(get("/internal/config/history").param("key", "missing.key")))
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
                    .andExpect(jsonPath("$['a.key'].type").value("int")) // FakeSource's snapshot
                    .andExpect(jsonPath("$['a.key'].value").value("1"));
        });
    }

    @Test
    void historyValidatesParameters() {
        runWithEndpoint((mvc, store) -> {
            expect(mvc, authorized(get("/internal/config/history")), status().isBadRequest());
            expect(mvc, authorized(get("/internal/config/history").param("key", "a.key").param("limit", "0")),
                    status().isBadRequest());
        });
    }

    private void runWithEndpoint(EndpointTest test) {
        runWithEndpoint("configstream.internal.secret=" + SECRET, test);
    }

    private void runWithEndpoint(String setting, EndpointTest test) {
        runner.withPropertyValues(setting).run(context -> {
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

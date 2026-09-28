package io.github.configstream.admin;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.configstream.adminhost.AdminHostApplication;
import io.github.configstream.admin.client.ConfigEntry;
import io.github.configstream.admin.client.ServiceCallException;
import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.PropertyType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The admin server in a host app: registration API and UI, with calls to services mocked out. */
// The configstream starter (and so the Mongo driver) is on the test classpath for the end-to-end test
@SpringBootTest(classes = AdminHostApplication.class, properties = {
        "configstream.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD) // fresh registry per test
class ConfigStreamAdminServerTest {

    private static final String PROPERTIES = ",\"properties\":["
            + "{\"key\":\"feature.x.enabled\",\"type\":\"boolean\",\"description\":null},"
            + "{\"key\":\"limits.max\",\"type\":\"int\",\"description\":\"Maximum items per order\"}]";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ServiceClient serviceClient;

    @Test
    void registrationLifecycle() throws Exception {
        register("orders", "o-1", 8080);
        mvc.perform(put("/api/instances/o-1/heartbeat")).andExpect(status().isNoContent());
        mvc.perform(put("/api/instances/unknown/heartbeat")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/instances/o-1")).andExpect(status().isNoContent());
        mvc.perform(put("/api/instances/o-1/heartbeat")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsIncompleteRegistration() throws Exception {
        mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"orders\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dashboardListsServices() throws Exception {
        mvc.perform(get("/")).andExpect(content().string(containsString("No services registered yet")));

        register("orders", "o-1", 8080);
        register("orders", "o-2", 8081);
        register("billing", "b-1", 8082);

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("href=\"/services/orders\""),
                        containsString("href=\"/services/billing\""),
                        containsString("2 active instances"),
                        containsString("team-a"),
                        not(containsString("No services registered yet")))));
    }

    @Test
    void dashboardSearchesServicesByNameOrTeam() throws Exception {
        register("orders", "o-1", 8080);
        register("billing", "b-1", 8081);

        // Every card is sent; the ones that don't match are hidden, so typing can widen the search again
        mvc.perform(get("/").param("q", " BILL "))
                .andExpect(content().string(allOf(
                        containsString("href=\"/services/billing\" data-cs-search=\"billing team-a\">"),
                        containsString("href=\"/services/orders\" data-cs-search=\"orders team-a\" hidden=\"hidden\">"),
                        containsString("value=\"BILL\""),
                        containsString("1 of 2 services"))));
        mvc.perform(get("/").param("q", "team-a"))
                .andExpect(content().string(allOf(
                        containsString("2 of 2 services"),
                        not(containsString("team-a\" hidden=\"hidden\">")))));
        mvc.perform(get("/").param("q", "nothing-like-this"))
                .andExpect(content().string(allOf(
                        containsString("0 of 2 services"),
                        containsString("No services match &#39;nothing-like-this&#39;."))));
        mvc.perform(get("/"))
                .andExpect(content().string(allOf(
                        containsString("2 services"),
                        containsString("data-cs-service-nomatch hidden=\"hidden\""))));
    }

    @Test
    void servicePageShowsInstancesAndTypedProperties() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(get("/services/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("o-1"),
                        containsString("http://localhost:8080"),
                        containsString("Active instances"),
                        containsString("feature.x.enabled"),
                        containsString("limits.max"),
                        containsString("Maximum items per order"),
                        containsString("<span class=\"cs-type\">int</span>"),
                        containsString("<span class=\"cs-type\">boolean</span>"),
                        containsString("/services/orders/history?key=limits.max&amp;type=int"),
                        not(containsString("Used by")))));
    }

    @Test
    void servicePageEditsValuesOnlyAndDeletesOnlyOrphans() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        // Properties in use: editable, never deletable, and no way to add one
        mvc.perform(get("/services/orders"))
                .andExpect(content().string(allOf(
                        containsString("/services/orders/edit?key=limits.max&amp;type=int"),
                        not(containsString("/services/orders/delete?key=limits.max")),
                        not(containsString("/services/orders/edit?key=feature.old.flag")),
                        containsString("href=\"/services/orders?view=orphans\""),
                        not(containsString("Add entry")))));
        // Orphans are on their own tab, where they can be deleted
        mvc.perform(get("/services/orders").param("view", "orphans"))
                .andExpect(content().string(allOf(
                        containsString("/services/orders/delete?key=feature.old.flag&amp;type=boolean"),
                        containsString("No running instance declares these"),
                        not(containsString("/services/orders/edit?key=limits.max")))));
    }

    @Test
    void aTypeChangeShowsBothPropertiesWhileOldAndNewVersionsRun() throws Exception {
        // Blue declares limits.max as int; green, the new version, as string
        register("orders", "blue", 8080);
        register("orders", "green", 8081, ",\"properties\":["
                + "{\"key\":\"feature.x.enabled\",\"type\":\"boolean\",\"description\":null},"
                + "{\"key\":\"limits.max\",\"type\":\"string\",\"description\":\"Limit, as text\"}]");
        when(serviceClient.currentConfig("orders")).thenReturn(List.of(
                new ConfigEntry("feature.x.enabled", "boolean", "true"),
                new ConfigEntry("limits.max", "int", "50"),
                new ConfigEntry("limits.max", "string", "fifty")));

        mvc.perform(get("/services/orders"))
                .andExpect(content().string(allOf(
                        containsString("/services/orders/edit?key=limits.max&amp;type=int"),
                        containsString("/services/orders/edit?key=limits.max&amp;type=string"),
                        containsString("Maximum items per order"),
                        containsString("Limit, as text"),
                        containsString("Used by 1 of 2 instances"),
                        not(containsString("view=orphans")))));

        // Blue stops: its int property is now an orphan
        mvc.perform(delete("/api/instances/blue")).andExpect(status().isNoContent());
        mvc.perform(get("/services/orders").param("view", "orphans"))
                .andExpect(content().string(allOf(
                        containsString("/services/orders/delete?key=limits.max&amp;type=int"),
                        not(containsString("Used by")))));
    }

    @Test
    void nothingIsAnOrphanWhileAnInstanceHasNotReportedItsProperties() throws Exception {
        register("orders", "o-1", 8080);
        register("orders", "o-old", 8081, ""); // an instance of an older configstream version
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(get("/services/orders"))
                .andExpect(content().string(allOf(
                        not(containsString("/services/orders/delete?key=feature.old.flag")),
                        not(containsString("Orphan")))));
    }

    @Test
    void servicePageShowsCallFailuresInsteadOfErroring() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders"))
                .thenThrow(new ServiceCallException("'orders' rejected the configured secret."));

        mvc.perform(get("/services/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("o-1"),
                        containsString("rejected the configured secret"))));
    }

    @Test
    void unknownServiceIs404() throws Exception {
        mvc.perform(get("/services/nope")).andExpect(status().isNotFound());
    }

    @Test
    void historyPageShowsEntries() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.history("orders", "limits.max", "int", 100)).thenReturn(List.of(
                new ConfigHistoryEntry("limits.max", PropertyType.INT, 2, "10", "50", "alice",
                        Instant.parse("2026-09-25T10:00:00Z"), "Reverted to v1"),
                new ConfigHistoryEntry("limits.max", PropertyType.INT, 1, null, "10", "bob",
                        Instant.parse("2026-09-24T09:00:00Z"), null)));

        mvc.perform(history("limits.max", "int"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("v2"),
                        containsString("alice"),
                        containsString("Reverted to v1"),
                        containsString("2026-09-25 10:00:00 UTC"),
                        containsString("(created)"),
                        containsString("type=int&amp;value=10&amp;comment=Reverted%20to%20v1"))));
    }

    @Test
    void editFormMatchesThePropertysType() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(edit("limits.max", "int"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Edit property"),
                        containsString("name=\"key\" value=\"limits.max\""),
                        containsString("name=\"type\" value=\"int\""),
                        containsString("type=\"text\" inputmode=\"numeric\" name=\"value\" value=\"50\""),
                        containsString("Maximum items per order"))));
        mvc.perform(edit("feature.x.enabled", "boolean"))
                .andExpect(content().string(allOf(
                        containsString("type=\"radio\" name=\"value\" value=\"true\" checked=\"checked\""),
                        containsString("type=\"radio\" name=\"value\" value=\"false\">"))));
    }

    @Test
    void propertiesCannotBeAddedFromTheAdmin() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(get("/services/orders/edit")).andExpect(status().isBadRequest());
        mvc.perform(get("/services/orders/edit").param("key", "limits.max")).andExpect(status().isBadRequest());
        mvc.perform(edit("brand.new", "int"))
                .andExpect(content().string(containsString("There is no property &#39;brand.new&#39; of type int")));
        // Nor a new type for an existing key
        mvc.perform(edit("limits.max", "string"))
                .andExpect(content().string(containsString("There is no property &#39;limits.max&#39; of type string")));
        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "brand.new").param("type", "int").param("value", "1").param("changedBy", "alice"))
                .andExpect(content().string(allOf(
                        containsString("Properties are added in each service&#39;s code"),
                        not(containsString("<h1>Review change</h1>")))));
    }

    @Test
    void reviewShowsCurrentAndNewValueBeforeAnythingIsWritten() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(review("limits.max", "int", "75").param("comment", "more traffic"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("<h1>Review change</h1>"),
                        containsString(">50<"),
                        containsString(">75<"),
                        containsString("more traffic"),
                        containsString("name=\"type\" value=\"int\""),
                        containsString("action=\"/services/orders/update\""),
                        containsString("Apply change"))));
        verify(serviceClient, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reviewRejectsInvalidUnchangedAndWronglyTypedInput() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(post("/services/orders/edit/review").param("key", " ").param("value", "1"))
                .andExpect(content().string(allOf(
                        containsString("Key is required."),
                        containsString("Type is required."),
                        containsString("Enter your name"),
                        not(containsString("<h1>Review change</h1>")))));
        mvc.perform(review("limits.max", "int", "50"))
                .andExpect(content().string(allOf(
                        containsString("already has this value"),
                        not(containsString("<h1>Review change</h1>")))));
        mvc.perform(review("limits.max", "int", "3.5"))
                .andExpect(content().string(allOf(
                        containsString("&#39;3.5&#39; is not a valid int."),
                        not(containsString("<h1>Review change</h1>")))));
    }

    @Test
    void anInvalidValueKeepsWhatWasTypedAndHighlightsTheField() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        // Letters in an int field: shown back as typed, with the reason on the field
        mvc.perform(review("limits.max", "int", "abc"))
                .andExpect(content().string(allOf(
                        containsString("name=\"value\" value=\"abc\""),
                        containsString("class=\"mono invalid\""),
                        containsString("aria-invalid=\"true\" aria-describedby=\"value-error\""),
                        containsString("<p class=\"cs-field-error\" id=\"value-error\">&#39;abc&#39; is not a valid int.</p>"),
                        not(containsString("cs-banner error")))));
        // Booleans: the true/false buttons are highlighted the same way, for a wrong value or none at all
        mvc.perform(review("feature.x.enabled", "boolean", "yes"))
                .andExpect(content().string(allOf(
                        containsString("<fieldset class=\"cs-field cs-choice invalid\" aria-invalid=\"true\""),
                        containsString("&#39;yes&#39; is not a valid boolean."))));
        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "feature.x.enabled").param("type", "boolean").param("changedBy", "alice"))
                .andExpect(content().string(allOf(
                        containsString("<fieldset class=\"cs-field cs-choice invalid\" aria-invalid=\"true\""),
                        containsString("<p class=\"cs-field-error\" id=\"value-error\">Value is required.</p>"),
                        not(containsString("cs-banner error")))));
        // The same check guards a direct update, before anything is sent to the service
        mvc.perform(post("/services/orders/update")
                        .param("key", "limits.max").param("value", "abc").param("type", "int").param("changedBy", "alice"))
                .andExpect(content().string(containsString("aria-invalid=\"true\"")));
        verify(serviceClient, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void applyingAnUpdateSendsTheKeyAndTypeAndRedirectsWithAConfirmation() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());
        when(serviceClient.update("orders", "limits.max", "int", "75", "alice", "more traffic"))
                .thenReturn(Optional.of(entry("limits.max", PropertyType.INT, 3, "50", "75")));

        MvcResult result = mvc.perform(post("/services/orders/update")
                        .param("key", "limits.max").param("value", "75").param("type", "int")
                        .param("changedBy", " alice ").param("comment", "more traffic"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services/orders"))
                .andExpect(flash().attribute("notice", containsString("Updated 'limits.max' (v3)")))
                .andReturn();

        // The name is remembered for the next change in this session
        mvc.perform(get("/services/orders/delete").param("key", "feature.old.flag").param("type", "boolean")
                        .session((MockHttpSession) result.getRequest().getSession()))
                .andExpect(content().string(containsString("value=\"alice\"")));
    }

    @Test
    void failedUpdateIsShownWithTheFormStillFilledIn() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.update(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceCallException("Could not reach any instance of 'orders' (1 tried)"));

        mvc.perform(post("/services/orders/update")
                        .param("key", "banner.text").param("type", "string").param("value", "hello").param("changedBy", "alice"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Update failed: Could not reach any instance"),
                        containsString(">hello</textarea>"))));
    }

    @Test
    void deletingAnOrphanAsksForConfirmationWarnsAboutRollbacksThenDeletes() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());
        when(serviceClient.delete("orders", "feature.old.flag", "boolean", "bob", null))
                .thenReturn(Optional.of(entry("feature.old.flag", PropertyType.BOOLEAN, 4, "true", null)));

        mvc.perform(get("/services/orders/delete").param("key", "feature.old.flag").param("type", "boolean"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Delete <code>feature.old.flag</code>?"),
                        containsString("If you roll back to a version that declares this property"),
                        containsString(">true<"),
                        containsString("name=\"type\" value=\"boolean\""),
                        containsString("Delete property"))));
        verify(serviceClient, never()).delete(any(), any(), any(), any(), any());

        mvc.perform(post("/services/orders/delete").param("key", "feature.old.flag").param("type", "boolean")
                        .param("changedBy", "bob").param("comment", ""))
                .andExpect(redirectedUrl("/services/orders?view=orphans"))
                .andExpect(flash().attribute("notice", containsString("Deleted 'feature.old.flag' (v4)")));
    }

    @Test
    void aPropertyInUseCannotBeDeleted() throws Exception {
        register("orders", "o-1", 8080);
        register("orders", "o-2", 8081);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(get("/services/orders/delete").param("key", "limits.max").param("type", "int"))
                .andExpect(content().string(allOf(
                        containsString("(int) is declared by 2 active instances of orders"),
                        not(containsString("Delete property")))));
        mvc.perform(post("/services/orders/delete").param("key", "limits.max").param("type", "int").param("changedBy", "bob"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("so it is in use and can&#39;t be deleted")));
        verify(serviceClient, never()).delete(any(), any(), any(), any(), any());
    }

    @Test
    void failedDeleteIsShown() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.delete(any(), any(), any(), any(), any()))
                .thenThrow(new ServiceCallException("'orders' rejected the configured secret."));

        mvc.perform(post("/services/orders/delete").param("key", "feature.old.flag").param("type", "boolean")
                        .param("changedBy", "bob"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delete failed: &#39;orders&#39; rejected the configured secret.")));
        mvc.perform(post("/services/orders/delete").param("key", "feature.old.flag").param("type", "boolean"))
                .andExpect(content().string(containsString("Enter your name")));
    }

    @Test
    void historyOfADeletedPropertyOffersNoRestore() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.history("orders", "feature.old.flag", "boolean", 100)).thenReturn(List.of(
                entry("feature.old.flag", PropertyType.BOOLEAN, 3, "true", null),
                entry("feature.old.flag", PropertyType.BOOLEAN, 2, "false", "true"),
                entry("feature.old.flag", PropertyType.BOOLEAN, 1, null, "false")));

        mvc.perform(history("feature.old.flag", "boolean"))
                .andExpect(content().string(allOf(
                        containsString("(deleted)"),
                        containsString("This property was deleted"),
                        not(containsString("comment=Reverted")))));
    }

    @Test
    void writePagesFor404UnknownServices() throws Exception {
        mvc.perform(get("/services/nope/edit").param("key", "a.b").param("type", "int")).andExpect(status().isNotFound());
        mvc.perform(post("/services/nope/update").param("key", "a.b").param("type", "int").param("value", "1")
                        .param("changedBy", "x"))
                .andExpect(status().isNotFound());
        verify(serviceClient, never()).update(any(), any(), any(), any(), any(), any());
    }

    /** limits.max and feature.x.enabled are declared by the registered instances; feature.old.flag is an orphan. */
    private static List<ConfigEntry> config() {
        return List.of(
                new ConfigEntry("feature.old.flag", "boolean", "true"),
                new ConfigEntry("feature.x.enabled", "boolean", "true"),
                new ConfigEntry("limits.max", "int", "50"));
    }

    private static ConfigHistoryEntry entry(String key, PropertyType type, long version, String oldValue, String newValue) {
        return new ConfigHistoryEntry(key, type, version, oldValue, newValue, "alice", Instant.parse("2026-09-25T10:00:00Z"),
                null);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder edit(String key, String type) {
        return get("/services/orders/edit").param("key", key).param("type", type);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder history(String key, String type) {
        return get("/services/orders/history").param("key", key).param("type", type);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder review(String key, String type,
            String value) {
        return post("/services/orders/edit/review").param("key", key).param("type", type).param("value", value)
                .param("changedBy", "alice");
    }

    /** Registers an instance declaring feature.x.enabled (boolean) and limits.max (int). */
    private void register(String service, String id, int port) throws Exception {
        register(service, id, port, PROPERTIES);
    }

    private void register(String service, String id, int port, String properties) throws Exception {
        mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"serviceName\":\"%s\",\"instanceId\":\"%s\",\"host\":\"localhost\",\"port\":%d,\"team\":\"team-a\"%s}"
                                .formatted(service, id, port, properties)))
                .andExpect(status().isCreated());
    }
}

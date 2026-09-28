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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
                        containsString("/services/orders/history?key=limits.max"))));
    }

    @Test
    void servicePageEditsValuesOnlyAndDeletesOnlyOrphans() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        // Properties in use: editable, never deletable, and no way to add one
        mvc.perform(get("/services/orders"))
                .andExpect(content().string(allOf(
                        containsString("/services/orders/edit?key=limits.max"),
                        not(containsString("/services/orders/delete?key=limits.max")),
                        not(containsString("/services/orders/edit?key=feature.old.flag")),
                        containsString("href=\"/services/orders?view=orphans\""),
                        not(containsString("Add entry")))));
        // Orphans are on their own tab, where they can be deleted
        mvc.perform(get("/services/orders").param("view", "orphans"))
                .andExpect(content().string(allOf(
                        containsString("/services/orders/delete?key=feature.old.flag"),
                        containsString("No running instance declares these"),
                        not(containsString("/services/orders/edit?key=limits.max")))));
    }

    @Test
    void nothingIsAnOrphanWhileAnInstanceHasNotReportedItsProperties() throws Exception {
        register("orders", "o-1", 8080);
        registerWithoutProperties("orders", "o-old", 8081); // an instance of an older configstream version
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
        when(serviceClient.history("orders", "limits.max", 100)).thenReturn(List.of(
                new ConfigHistoryEntry("limits.max", 2, "10", "50", "alice", Instant.parse("2026-09-25T10:00:00Z"),
                        "Reverted to v1"),
                new ConfigHistoryEntry("limits.max", 1, null, "10", "bob", Instant.parse("2026-09-24T09:00:00Z"),
                        null)));

        mvc.perform(get("/services/orders/history").param("key", "limits.max"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("v2"),
                        containsString("alice"),
                        containsString("Reverted to v1"),
                        containsString("2026-09-25 10:00:00 UTC"),
                        containsString("(created)"),
                        containsString("value=10&amp;comment=Reverted%20to%20v1"))));
    }

    @Test
    void editFormMatchesThePropertysType() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(get("/services/orders/edit").param("key", "limits.max"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Edit property"),
                        containsString("name=\"key\" value=\"limits.max\""),
                        containsString("type=\"number\" step=\"1\" name=\"value\" value=\"50\""),
                        containsString("Maximum items per order"))));
        mvc.perform(get("/services/orders/edit").param("key", "feature.x.enabled"))
                .andExpect(content().string(allOf(
                        containsString("type=\"radio\" name=\"value\" value=\"true\" checked=\"checked\""),
                        containsString("type=\"radio\" name=\"value\" value=\"false\">"))));
    }

    @Test
    void propertiesCannotBeAddedFromTheAdmin() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(get("/services/orders/edit")).andExpect(status().isBadRequest());
        mvc.perform(get("/services/orders/edit").param("key", "brand.new"))
                .andExpect(content().string(containsString("There is no property &#39;brand.new&#39;")));
        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "brand.new").param("value", "1").param("changedBy", "alice"))
                .andExpect(content().string(allOf(
                        containsString("Properties are added in each service&#39;s configstream.yml"),
                        not(containsString("<h1>Review change</h1>")))));
    }

    @Test
    void reviewShowsCurrentAndNewValueBeforeAnythingIsWritten() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "limits.max").param("value", "75").param("changedBy", "alice")
                        .param("comment", "more traffic"))
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
                        containsString("Enter your name"),
                        not(containsString("<h1>Review change</h1>")))));
        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "limits.max").param("value", "50").param("changedBy", "alice"))
                .andExpect(content().string(allOf(
                        containsString("already has this value"),
                        not(containsString("<h1>Review change</h1>")))));
        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "limits.max").param("value", "3.5").param("changedBy", "alice"))
                .andExpect(content().string(allOf(
                        containsString("&#39;3.5&#39; is not a valid int."),
                        not(containsString("<h1>Review change</h1>")))));
    }

    @Test
    void applyingAnUpdateSendsTheTypeAndRedirectsWithAConfirmation() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());
        when(serviceClient.update("orders", "limits.max", "75", "int", "alice", "more traffic"))
                .thenReturn(Optional.of(entry("limits.max", 3, "50", "75")));

        MvcResult result = mvc.perform(post("/services/orders/update")
                        .param("key", "limits.max").param("value", "75").param("type", "int")
                        .param("changedBy", " alice ").param("comment", "more traffic"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services/orders"))
                .andExpect(flash().attribute("notice", containsString("Updated 'limits.max' (v3)")))
                .andReturn();

        // The name is remembered for the next change in this session
        mvc.perform(get("/services/orders/delete").param("key", "feature.old.flag")
                        .session((MockHttpSession) result.getRequest().getSession()))
                .andExpect(content().string(containsString("value=\"alice\"")));
    }

    @Test
    void failedUpdateIsShownWithTheFormStillFilledIn() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.update(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceCallException("Could not reach any instance of 'orders' (1 tried)"));

        mvc.perform(post("/services/orders/update")
                        .param("key", "banner.text").param("value", "hello").param("changedBy", "alice"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Update failed: Could not reach any instance"),
                        containsString(">hello</textarea>"))));
    }

    @Test
    void deletingAnOrphanAsksForConfirmationWarnsAboutRollbacksThenDeletes() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(config());
        when(serviceClient.delete("orders", "feature.old.flag", "bob", null))
                .thenReturn(Optional.of(entry("feature.old.flag", 4, "true", null)));

        mvc.perform(get("/services/orders/delete").param("key", "feature.old.flag"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Delete <code>feature.old.flag</code>?"),
                        containsString("If you roll back to a version that declares this property"),
                        containsString(">true<"),
                        containsString("Delete property"))));
        verify(serviceClient, never()).delete(any(), any(), any(), any());

        mvc.perform(post("/services/orders/delete").param("key", "feature.old.flag").param("changedBy", "bob")
                        .param("comment", ""))
                .andExpect(redirectedUrl("/services/orders?view=orphans"))
                .andExpect(flash().attribute("notice", containsString("Deleted 'feature.old.flag' (v4)")));
    }

    @Test
    void aPropertyInUseCannotBeDeleted() throws Exception {
        register("orders", "o-1", 8080);
        register("orders", "o-2", 8081);
        when(serviceClient.currentConfig("orders")).thenReturn(config());

        mvc.perform(get("/services/orders/delete").param("key", "limits.max"))
                .andExpect(content().string(allOf(
                        containsString("is declared by 2 active instances of orders"),
                        not(containsString("Delete property")))));
        mvc.perform(post("/services/orders/delete").param("key", "limits.max").param("changedBy", "bob"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("so it is in use and can&#39;t be deleted")));
        verify(serviceClient, never()).delete(any(), any(), any(), any());
    }

    @Test
    void failedDeleteIsShown() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.delete(any(), any(), any(), any()))
                .thenThrow(new ServiceCallException("'orders' rejected the configured secret."));

        mvc.perform(post("/services/orders/delete").param("key", "feature.old.flag").param("changedBy", "bob"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delete failed: &#39;orders&#39; rejected the configured secret.")));
        mvc.perform(post("/services/orders/delete").param("key", "feature.old.flag"))
                .andExpect(content().string(containsString("Enter your name")));
    }

    @Test
    void historyOfADeletedPropertyOffersNoRestore() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.history("orders", "feature.old.flag", 100)).thenReturn(List.of(
                entry("feature.old.flag", 3, "50", null),
                entry("feature.old.flag", 2, "10", "50"),
                entry("feature.old.flag", 1, null, "10")));

        mvc.perform(get("/services/orders/history").param("key", "feature.old.flag"))
                .andExpect(content().string(allOf(
                        containsString("(deleted)"),
                        containsString("This property was deleted"),
                        not(containsString("comment=Reverted")))));
    }

    @Test
    void writePagesFor404UnknownServices() throws Exception {
        mvc.perform(get("/services/nope/edit").param("key", "a.b")).andExpect(status().isNotFound());
        mvc.perform(post("/services/nope/update").param("key", "a.b").param("value", "1").param("changedBy", "x"))
                .andExpect(status().isNotFound());
        verify(serviceClient, never()).update(any(), any(), any(), any(), any(), any());
    }

    /** limits.max and feature.x.enabled are declared by the registered instances; feature.old.flag is an orphan. */
    private static Map<String, ConfigEntry> config() {
        return new TreeMap<>(Map.of(
                "feature.x.enabled", new ConfigEntry("boolean", "true"),
                "limits.max", new ConfigEntry("int", "50"),
                "feature.old.flag", new ConfigEntry("boolean", "true")));
    }

    private static ConfigHistoryEntry entry(String key, long version, String oldValue, String newValue) {
        return new ConfigHistoryEntry(key, version, oldValue, newValue, "alice", Instant.parse("2026-09-25T10:00:00Z"),
                null);
    }

    private void register(String service, String id, int port) throws Exception {
        register(service, id, port, ",\"properties\":["
                + "{\"key\":\"feature.x.enabled\",\"type\":\"boolean\",\"description\":null},"
                + "{\"key\":\"limits.max\",\"type\":\"int\",\"description\":\"Maximum items per order\"}]");
    }

    private void registerWithoutProperties(String service, String id, int port) throws Exception {
        register(service, id, port, "");
    }

    private void register(String service, String id, int port, String properties) throws Exception {
        mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"serviceName\":\"%s\",\"instanceId\":\"%s\",\"host\":\"localhost\",\"port\":%d,\"team\":\"team-a\"%s}"
                                .formatted(service, id, port, properties)))
                .andExpect(status().isCreated());
    }
}

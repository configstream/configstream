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
    void servicePageShowsInstancesAndConfig() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders"))
                .thenReturn(new TreeMap<>(Map.of("feature.x.enabled", "true", "limits.max", "50")));

        mvc.perform(get("/services/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("o-1"),
                        containsString("http://localhost:8080"),
                        containsString("Active instances"),
                        containsString("feature.x.enabled"),
                        containsString("limits.max"),
                        containsString("/services/orders/history?key=limits.max"))));
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
                        containsString("(created)"))));
    }

    @Test
    void servicePageLinksToEditDeleteAndAdd() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(Map.of("limits.max", "50"));

        mvc.perform(get("/services/orders"))
                .andExpect(content().string(allOf(
                        containsString("/services/orders/edit?key=limits.max"),
                        containsString("/services/orders/delete?key=limits.max"),
                        containsString("Add entry"))));
    }

    @Test
    void editFormStartsFromTheCurrentValue() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(Map.of("limits.max", "50"));

        mvc.perform(get("/services/orders/edit").param("key", "limits.max"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Edit entry"),
                        containsString("value=\"limits.max\""),
                        containsString("readonly=\"readonly\""),
                        containsString(">50</textarea>"))));
        mvc.perform(get("/services/orders/edit"))
                .andExpect(content().string(allOf(containsString("New entry"), not(containsString("readonly")))));
    }

    @Test
    void reviewShowsCurrentAndNewValueBeforeAnythingIsWritten() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(Map.of("limits.max", "50"));

        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "limits.max").param("value", "75").param("changedBy", "alice")
                        .param("comment", "more traffic"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("<h1>Review change</h1>"),
                        containsString(">50<"),
                        containsString(">75<"),
                        containsString("more traffic"),
                        containsString("action=\"/services/orders/update\""),
                        containsString("Apply change"))));
        verify(serviceClient, never()).update(any(), any(), any(), any(), any());
    }

    @Test
    void reviewOfANewKeySaysItWillBeCreated() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(Map.of());

        mvc.perform(post("/services/orders/edit/review")
                        .param("key", "brand.new").param("value", "").param("changedBy", "alice"))
                .andExpect(content().string(containsString("this creates the key")));
    }

    @Test
    void reviewRejectsInvalidAndUnchangedInput() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(Map.of("limits.max", "50"));

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
    }

    @Test
    void applyingAnUpdateRedirectsWithAConfirmation() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.update("orders", "limits.max", "75", "alice", "more traffic"))
                .thenReturn(Optional.of(entry("limits.max", 3, "50", "75")));

        MvcResult result = mvc.perform(post("/services/orders/update")
                        .param("key", "limits.max").param("value", "75").param("changedBy", " alice ")
                        .param("comment", "more traffic"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/services/orders"))
                .andExpect(flash().attribute("notice", containsString("Updated 'limits.max' (v3)")))
                .andReturn();

        // The name is remembered for the next change in this session
        mvc.perform(get("/services/orders/delete").param("key", "limits.max")
                        .session((MockHttpSession) result.getRequest().getSession()))
                .andExpect(content().string(containsString("value=\"alice\"")));
    }

    @Test
    void failedUpdateIsShownWithTheFormStillFilledIn() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.update(any(), any(), any(), any(), any()))
                .thenThrow(new ServiceCallException("Could not reach any instance of 'orders' (1 tried)"));

        mvc.perform(post("/services/orders/update")
                        .param("key", "limits.max").param("value", "75").param("changedBy", "alice"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Update failed: Could not reach any instance"),
                        containsString(">75</textarea>"))));
    }

    @Test
    void deleteAsksForConfirmationThenDeletes() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.currentConfig("orders")).thenReturn(Map.of("limits.max", "50"));
        when(serviceClient.delete("orders", "limits.max", "bob", null))
                .thenReturn(Optional.of(entry("limits.max", 4, "50", null)));

        mvc.perform(get("/services/orders/delete").param("key", "limits.max"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Delete <code>limits.max</code>?"),
                        containsString(">50<"),
                        containsString("Delete key"))));
        verify(serviceClient, never()).delete(any(), any(), any(), any());

        mvc.perform(post("/services/orders/delete").param("key", "limits.max").param("changedBy", "bob")
                        .param("comment", ""))
                .andExpect(redirectedUrl("/services/orders"))
                .andExpect(flash().attribute("notice", containsString("Deleted 'limits.max' (v4)")));
    }

    @Test
    void failedDeleteIsShown() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.delete(any(), any(), any(), any()))
                .thenThrow(new ServiceCallException("'orders' rejected the configured secret."));

        mvc.perform(post("/services/orders/delete").param("key", "limits.max").param("changedBy", "bob"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delete failed: &#39;orders&#39; rejected the configured secret.")));
        mvc.perform(post("/services/orders/delete").param("key", "limits.max"))
                .andExpect(content().string(containsString("Enter your name")));
    }

    @Test
    void historyShowsDeletionsAndOffersRestore() throws Exception {
        register("orders", "o-1", 8080);
        when(serviceClient.history("orders", "limits.max", 100)).thenReturn(List.of(
                entry("limits.max", 3, "50", null),
                entry("limits.max", 2, "10", "50"),
                entry("limits.max", 1, null, "10")));

        mvc.perform(get("/services/orders/history").param("key", "limits.max"))
                .andExpect(content().string(allOf(
                        containsString("(deleted)"),
                        containsString("value=50&amp;comment=Restored%20after%20deletion%20in%20v3"),
                        containsString("value=10&amp;comment=Reverted%20to%20v1"))));
    }

    @Test
    void writePagesFor404UnknownServices() throws Exception {
        mvc.perform(get("/services/nope/edit")).andExpect(status().isNotFound());
        mvc.perform(post("/services/nope/update").param("key", "a").param("value", "1").param("changedBy", "x"))
                .andExpect(status().isNotFound());
        verify(serviceClient, never()).update(any(), any(), any(), any(), any());
    }

    private static ConfigHistoryEntry entry(String key, long version, String oldValue, String newValue) {
        return new ConfigHistoryEntry(key, version, oldValue, newValue, "alice", Instant.parse("2026-09-25T10:00:00Z"),
                null);
    }

    private void register(String service, String id, int port) throws Exception {
        mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"serviceName\":\"%s\",\"instanceId\":\"%s\",\"host\":\"localhost\",\"port\":%d,\"team\":\"team-a\"}"
                                .formatted(service, id, port)))
                .andExpect(status().isCreated());
    }
}

package io.github.configstream.admin;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.configstream.adminhost.AdminHostApplication;
import io.github.configstream.admin.client.ConfigEntry;
import io.github.configstream.admin.client.ServiceClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The dashboard moved under a path, as when the host app has pages of its own. */
@SpringBootTest(classes = AdminHostApplication.class, properties = {
        "configstream.admin-server.dashboard.path=/admin/",
        "configstream.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration"})
@AutoConfigureMockMvc
class DashboardPathTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ServiceClient serviceClient;

    @Test
    void dashboardPagesAndTheirLinksLiveUnderThePath() throws Exception {
        mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"serviceName\":\"orders\",\"instanceId\":\"o-1\",\"host\":\"localhost\",\"port\":8080}"))
                .andExpect(status().isCreated()); // the registration API does not move
        when(serviceClient.currentConfig("orders")).thenReturn(List.of(new ConfigEntry("limits.max", "int", "50")));

        mvc.perform(get("/admin/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("href=\"/admin/services/orders\""),
                        containsString("href=\"/configstream-admin/admin.css\""))));
        mvc.perform(get("/admin/services/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("/admin/services/orders/edit?key=limits.max"),
                        not(containsString("href=\"/services/")))));
        mvc.perform(get("/admin")).andExpect(redirectedUrl("/admin/"));
        mvc.perform(get("/services/orders")).andExpect(status().isNotFound());
    }

    @Test
    void writesRedirectBackUnderThePath() throws Exception {
        mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"serviceName\":\"orders\",\"instanceId\":\"o-1\",\"host\":\"localhost\",\"port\":8080}"))
                .andExpect(status().isCreated());
        when(serviceClient.update("orders", "limits.max", "int", "75", "alice", null)).thenReturn(Optional.empty());

        mvc.perform(post("/admin/services/orders/update")
                        .param("key", "limits.max").param("type", "int").param("value", "75").param("changedBy", "alice"))
                .andExpect(redirectedUrl("/admin/services/orders"));
    }
}

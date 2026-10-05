package io.github.configstream.admin;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.configstream.admin.access.AdminUser;
import io.github.configstream.admin.access.ConfigStreamAdminPermissions;
import io.github.configstream.admin.client.ConfigEntry;
import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.admin.registry.ServiceSummary;
import io.github.configstream.adminhost.AdminHostApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** A company's own permissions replace the default: here everyone may look, but nobody may change anything. */
@SpringBootTest(classes = {AdminHostApplication.class, CustomPermissionsTest.ReadOnly.class}, properties = {
        "configstream.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration"})
@AutoConfigureMockMvc
class CustomPermissionsTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ServiceClient serviceClient;

    @Test
    void viewOnlyPeopleSeeNoEditButtonsAndCantChangeAnything() throws Exception {
        mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"serviceName\":\"orders\",\"instanceId\":\"o-1\",\"host\":\"localhost\",\"port\":8080,"
                                + "\"team\":\"team-a\",\"properties\":[{\"key\":\"limits.max\",\"type\":\"int\"}]}"))
                .andExpect(status().isCreated());
        when(serviceClient.currentConfig("orders")).thenReturn(Map.of("limits.max", new ConfigEntry("int", "50")));

        mvc.perform(get("/services/orders").with(signedIn("tina")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("limits.max"),
                        not(containsString("/services/orders/edit")))));
        // Checked again on the server: hiding the button isn't the only protection
        mvc.perform(post("/services/orders/update").with(signedIn("tina"))
                        .param("key", "limits.max").param("value", "75").param("type", "int"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/services/orders/delete").with(signedIn("tina")).param("key", "feature.old.flag"))
                .andExpect(status().isForbidden());
        verify(serviceClient, never()).update(any(), any(), any(), any(), any(), any());
        verify(serviceClient, never()).delete(any(), any(), any(), any());
    }

    private static RequestPostProcessor signedIn(String name) {
        return request -> {
            request.setUserPrincipal(() -> name);
            return request;
        };
    }

    @TestConfiguration
    static class ReadOnly {
        @Bean
        ConfigStreamAdminPermissions readOnlyPermissions() {
            return new ConfigStreamAdminPermissions() {
                @Override
                public boolean canView(AdminUser user, ServiceSummary service) {
                    return true;
                }

                @Override
                public boolean canEdit(AdminUser user, ServiceSummary service) {
                    return false;
                }
            };
        }
    }
}

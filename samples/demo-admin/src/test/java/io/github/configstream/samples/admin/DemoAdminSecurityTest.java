package io.github.configstream.samples.admin;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The sample's login: people sign in, services register without one, and forms carry Spring Security's CSRF token. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoAdminSecurityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void peopleMustSignIn() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void theHeaderShowsWhoIsSignedIn() throws Exception {
        mvc.perform(get("/").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("<span>alice</span>"),
                        containsString("href=\"/logout\""),
                        not(containsString("No login: trusted networks only")))));
    }

    @Test
    void servicesRegisterWithoutSigningIn() throws Exception {
        register("orders", "o-1").andExpect(status().isCreated());
    }

    @Test
    void formsCarryTheCsrfTokenAndNoNameField() throws Exception {
        register("orders", "o-2").andExpect(status().isCreated());

        // The service isn't really running, so the page also shows that it can't be reached
        mvc.perform(get("/services/orders/edit").param("key", "limits.max").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("name=\"_csrf\""),
                        not(containsString("name=\"changedBy\" value")))));
    }

    private org.springframework.test.web.servlet.ResultActions register(String service, String instanceId)
            throws Exception {
        return mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                "{\"serviceName\":\"%s\",\"instanceId\":\"%s\",\"host\":\"localhost\",\"port\":1}"
                        .formatted(service, instanceId)));
    }
}

package io.github.configstream.samples.admin;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
        mvc.perform(get("/").with(user("alice").roles("team-a")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("<span>alice</span>"),
                        containsString("action=\"/logout\""),
                        not(containsString("No login: trusted networks only")))));
    }

    @Test
    void signingOutTakesOneClick() throws Exception {
        // The header's Sign out button posts with the CSRF token, so there's no confirmation page
        mvc.perform(post("/logout").with(user("alice").roles("team-a")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void eachPersonSeesTheirTeamsServices() throws Exception {
        register("orders", "o-3").andExpect(status().isCreated());   // team-a

        mvc.perform(get("/").with(user("alice").roles("team-a")))
                .andExpect(content().string(containsString("href=\"/services/orders\"")));
        mvc.perform(get("/").with(user("bob").roles("team-b")))
                .andExpect(content().string(not(containsString("href=\"/services/orders\""))));
        mvc.perform(get("/services/orders").with(user("bob").roles("team-b"))).andExpect(status().isNotFound());
        mvc.perform(get("/").with(user("admin").roles("config-admins")))
                .andExpect(content().string(containsString("href=\"/services/orders\"")));
    }

    @Test
    void servicesRegisterWithoutSigningIn() throws Exception {
        register("orders", "o-1").andExpect(status().isCreated());
    }

    @Test
    void formsCarryTheCsrfTokenAndNoNameField() throws Exception {
        register("orders", "o-2").andExpect(status().isCreated());

        // The service isn't really running, so the page also shows that it can't be reached
        mvc.perform(get("/services/orders/edit").param("key", "limits.max").with(user("alice").roles("team-a")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("name=\"_csrf\""),
                        not(containsString("name=\"changedBy\" value")))));
    }

    private org.springframework.test.web.servlet.ResultActions register(String service, String instanceId)
            throws Exception {
        return mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                "{\"serviceName\":\"%s\",\"instanceId\":\"%s\",\"host\":\"localhost\",\"port\":1,\"team\":\"team-a\"}"
                        .formatted(service, instanceId)));
    }
}

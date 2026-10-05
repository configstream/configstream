package io.github.configstream.admin.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.configstream.admin.ConfigStreamAdminProperties;
import java.security.Principal;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistrationControllerTest {

    private final InstanceRegistry registry = new InstanceRegistry(new ConfigStreamAdminProperties(), Clock.systemUTC());

    private MockMvc mvc(boolean allowUnauthenticated) {
        return MockMvcBuilders.standaloneSetup(
                new RegistrationController(registry, new RegistrationGuard(allowUnauthenticated))).build();
    }

    @Test
    void aServiceWithItsOwnTokenRegistersFromAnywhere() throws Exception {
        MockMvc mvc = mvc(false);

        mvc.perform(register("payments", "p-1").with(remote()).principal(token("payments")))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/instances/p-1/heartbeat").with(remote()).principal(token("payments")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/instances/p-1").with(remote()).principal(token("payments")))
                .andExpect(status().isNoContent());
        assertThat(registry.services()).isEmpty();
    }

    @Test
    void aTokenForAnotherServiceIsRefused() throws Exception {
        MockMvc mvc = mvc(false);

        mvc.perform(register("payments", "p-1").with(remote()).principal(token("orders")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("authenticated as 'orders'")));
        assertThat(registry.service("payments")).isEmpty();
    }

    @Test
    void anotherServiceCantHeartbeatOrRemoveAnInstance() throws Exception {
        MockMvc mvc = mvc(false);
        mvc.perform(register("payments", "p-1").principal(token("payments"))).andExpect(status().isCreated());

        mvc.perform(put("/api/instances/p-1/heartbeat").principal(token("orders"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/instances/p-1").principal(token("orders"))).andExpect(status().isForbidden());
        assertThat(registry.service("payments")).isPresent();
    }

    @Test
    void anotherServiceCantTakeOverAnInstanceId() throws Exception {
        MockMvc mvc = mvc(false);
        mvc.perform(register("payments", "shared-id").principal(token("payments"))).andExpect(status().isCreated());

        mvc.perform(register("orders", "shared-id").principal(token("orders")))
                .andExpect(status().isConflict());
        assertThat(registry.serviceNameOf("shared-id")).contains("payments");
    }

    @Test
    void withoutATokenOnlyThisMachineCanRegister() throws Exception {
        MockMvc mvc = mvc(false);

        mvc.perform(register("payments", "p-1").with(remote()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(containsString("needs a token identifying the service")));
        // Local mode: trying configstream on one machine needs no setup
        mvc.perform(register("payments", "p-1")).andExpect(status().isCreated());
        mvc.perform(put("/api/instances/p-1/heartbeat").with(remote())).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRegistrationCanBeAllowedOnTrustedNetworks() throws Exception {
        mvc(true).perform(register("payments", "p-1").with(remote())).andExpect(status().isCreated());
    }

    @Test
    void unknownInstancesStillGetNotFound() throws Exception {
        mvc(false).perform(put("/api/instances/nope/heartbeat").with(remote())).andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder register(String service, String instanceId) {
        return post("/api/instances").contentType(MediaType.APPLICATION_JSON).content(
                "{\"serviceName\":\"%s\",\"instanceId\":\"%s\",\"host\":\"10.0.0.7\",\"port\":8080}"
                        .formatted(service, instanceId));
    }

    /** What the host application's security leaves on the request after validating a token for {@code service}. */
    private static Principal token(String service) {
        return () -> service;
    }

    private static RequestPostProcessor remote() {
        return request -> {
            request.setRemoteAddr("10.0.0.7");
            return request;
        };
    }
}

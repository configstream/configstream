package io.github.configstream.admin.registry;

import io.github.configstream.admin.ConfigStreamAdminProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The instance registry and the API services use to register with it. */
@Configuration(proxyBeanMethods = false)
public class ConfigStreamAdminRegistryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigStreamAdminRegistryConfiguration.class);

    @Bean
    InstanceRegistry configStreamAdminInstanceRegistry(ConfigStreamAdminProperties properties) {
        return new InstanceRegistry(properties, Clock.systemUTC());
    }

    @Bean
    RegistrationController configStreamAdminRegistrationController(InstanceRegistry registry,
            ConfigStreamAdminProperties properties) {
        if (properties.isAllowUnauthenticatedRegistration()) {
            log.warn("configstream.admin-server.allow-unauthenticated-registration is on: anyone who can reach "
                    + "/api/instances can register an instance for any service. Use it only on fully trusted networks.");
        } else {
            log.info("Services register with a token identifying them; without one, only services on this machine "
                    + "can register (local mode).");
        }
        return new RegistrationController(registry, new RegistrationGuard(properties.isAllowUnauthenticatedRegistration()));
    }
}

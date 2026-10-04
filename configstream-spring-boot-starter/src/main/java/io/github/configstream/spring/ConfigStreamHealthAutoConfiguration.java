package io.github.configstream.spring;

import io.github.configstream.api.ConfigChangeSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Adds the {@code configstream} entry to {@code /actuator/health} in applications that use Spring Boot Actuator.
 * Turn it off with {@code management.health.configstream.enabled=false}.
 */
@AutoConfiguration(after = ConfigStreamAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(ConfigChangeSource.class)
@ConditionalOnProperty(name = "management.health.configstream.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigStreamHealthAutoConfiguration {

    @Bean
    HealthIndicator configstreamHealthIndicator(ConfigChangeSource source, ConfigStreamProperties properties) {
        return new ConfigStreamHealthIndicator(source, properties.getHealth().getDownAfter(), Clock.systemUTC());
    }
}

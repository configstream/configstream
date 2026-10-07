package io.github.configstream.spring;

import io.github.configstream.api.ConfigChangeSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds the {@code configstream} entry to {@code /actuator/health} in applications that use Spring Boot Actuator.
 * Turn it off with {@code management.health.configstream.enabled=false}.
 *
 * <p>Spring Boot 4 moved {@code HealthIndicator} to another package, so there is one configuration per Boot version,
 * each matched by class name (Boot 3 first, in case both are on the classpath). The bean is named
 * {@code configstreamHealthIndicator} on both, which Actuator lists as {@code configstream}.
 */
@AutoConfiguration(after = ConfigStreamAutoConfiguration.class)
@ConditionalOnBean(ConfigChangeSource.class)
@ConditionalOnProperty(name = "management.health.configstream.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigStreamHealthAutoConfiguration {

    static final String BOOT_3_HEALTH_INDICATOR = "org.springframework.boot.actuate.health.HealthIndicator";
    static final String BOOT_4_HEALTH_INDICATOR = "org.springframework.boot.health.contributor.HealthIndicator";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = BOOT_3_HEALTH_INDICATOR)
    static class Boot3 {

        @Bean
        ConfigStreamHealthIndicator configstreamHealthIndicator(ConfigChangeSource source,
                ConfigStreamProperties properties) {
            return new ConfigStreamHealthIndicator(source, properties.getHealth().getDownAfter(), Clock.systemUTC());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = BOOT_4_HEALTH_INDICATOR)
    @ConditionalOnMissingClass(BOOT_3_HEALTH_INDICATOR)
    static class Boot4 {

        @Bean
        ConfigStreamBoot4HealthIndicator configstreamHealthIndicator(ConfigChangeSource source,
                ConfigStreamProperties properties) {
            return new ConfigStreamBoot4HealthIndicator(source, properties.getHealth().getDownAfter(),
                    Clock.systemUTC());
        }
    }
}

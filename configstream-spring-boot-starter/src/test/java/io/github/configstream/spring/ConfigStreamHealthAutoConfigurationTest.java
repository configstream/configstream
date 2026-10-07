package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConfigStreamHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigStreamAutoConfiguration.class, ConfigStreamHealthAutoConfiguration.class))
            .withUserConfiguration(ConfigStreamAutoConfigurationTest.FakeSourceConfig.class)
            .withPropertyValues("configstream.manifest=classpath:manifests/configstream.yml");

    @Test
    void addsTheConfigstreamHealthEntry() {
        // Named configstreamHealthIndicator, so Actuator lists it as "configstream"
        runner.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(HealthIndicator.class)
                .hasBean("configstreamHealthIndicator"));
    }

    @Test
    void usesSpringBoot4sHealthIndicatorOnSpringBoot4() {
        // Boot 4 has no org.springframework.boot.actuate.health, only org.springframework.boot.health.contributor
        runner.withClassLoader(new FilteredClassLoader(ConfigStreamHealthAutoConfiguration.BOOT_3_HEALTH_INDICATOR))
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(org.springframework.boot.health.contributor.HealthIndicator.class)
                        .hasSingleBean(ConfigStreamBoot4HealthIndicator.class)
                        .hasBean("configstreamHealthIndicator"));
    }

    @Test
    void canBeTurnedOff() {
        runner.withPropertyValues("management.health.configstream.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("configstreamHealthIndicator"));
    }

    @Test
    void absentWithoutActuator() {
        runner.withClassLoader(new FilteredClassLoader(ConfigStreamHealthAutoConfiguration.BOOT_3_HEALTH_INDICATOR,
                        ConfigStreamHealthAutoConfiguration.BOOT_4_HEALTH_INDICATOR))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("configstreamHealthIndicator"));
    }
}

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
    void canBeTurnedOff() {
        runner.withPropertyValues("management.health.configstream.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HealthIndicator.class));
    }

    @Test
    void absentWithoutActuator() {
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("configstreamHealthIndicator"));
    }
}

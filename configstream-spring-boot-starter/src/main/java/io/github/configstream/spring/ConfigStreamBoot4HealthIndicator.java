package io.github.configstream.spring;

import io.github.configstream.api.ConfigChangeSource;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** The {@code configstream} health entry on Spring Boot 4. The decision itself is {@link ConfigStreamHealth}. */
class ConfigStreamBoot4HealthIndicator implements HealthIndicator {

    private final ConfigStreamHealth health;

    ConfigStreamBoot4HealthIndicator(ConfigChangeSource source, Duration downAfter, Clock clock) {
        this.health = new ConfigStreamHealth(source, downAfter, clock);
    }

    @Override
    public Health health() {
        ConfigStreamHealth.Result result = health.check();
        return Health.status(result.status()).withDetails(result.details()).build();
    }
}

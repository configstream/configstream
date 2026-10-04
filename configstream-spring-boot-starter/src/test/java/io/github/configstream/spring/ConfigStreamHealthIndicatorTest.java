package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.configstream.api.ConfigChangeListener;
import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigSourceStatus;
import io.github.configstream.api.ConfigValue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class ConfigStreamHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final StatusSource source = new StatusSource();
    private final ConfigStreamHealthIndicator indicator = new ConfigStreamHealthIndicator(source, Duration.ofMinutes(2),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void upWhileConnected() {
        source.status = new ConfigSourceStatus(true, NOW.minusSeconds(600), NOW.minusSeconds(5), null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("connected", true)
                .containsEntry("connectedSince", "2026-10-04T09:50:00Z")
                .containsEntry("lastChangeReceived", "2026-10-04T09:59:55Z");
    }

    @Test
    void stillUpWhileReconnectingAfterAShortInterruption() {
        source.status = new ConfigSourceStatus(false, NOW.minusSeconds(30), null, "MongoSocketReadTimeoutException: timed out");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("connected", false)
                .containsEntry("reconnecting", true)
                .containsEntry("lastError", "MongoSocketReadTimeoutException: timed out");
    }

    @Test
    void downOnceCutOffLongerThanTheThreshold() {
        source.status = new ConfigSourceStatus(false, NOW.minusSeconds(180), NOW.minusSeconds(900), "MongoSecurityException: bad auth");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("disconnectedSince", "2026-10-04T09:57:00Z")
                .containsEntry("lastError", "MongoSecurityException: bad auth")
                .containsKey("reason")
                .doesNotContainKey("reconnecting");
        assertThat((String) health.getDetails().get("reason")).contains("for 180s");
    }

    @Test
    void unknownWhenTheSourceDoesNotReport() {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    private static class StatusSource implements ConfigChangeSource {
        ConfigSourceStatus status;

        @Override
        public Map<String, ConfigValue> loadInitial() {
            return Map.of();
        }

        @Override
        public void start(ConfigChangeListener listener) {
        }

        @Override
        public void stop() {
        }

        @Override
        public ConfigSourceStatus status() {
            return status;
        }
    }
}

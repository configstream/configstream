package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MongoClientSettingsTest {

    private static MongoClientSettings settings(String uri, Duration timeout) {
        return ConfigStreamAutoConfiguration.MongoSourceConfiguration.clientSettings(new ConnectionString(uri), timeout);
    }

    @Test
    void appliesASocketTimeoutByDefault() {
        // The driver's own default is 0: wait forever on a silently dropped connection
        assertThat(settings("mongodb://localhost/db", Duration.ofSeconds(30))
                .getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(30_000);
    }

    @Test
    void theUriWins() {
        assertThat(settings("mongodb://localhost/db?socketTimeoutMS=45000", Duration.ofSeconds(30))
                .getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(45_000);
    }

    @Test
    void keepsTheRestOfTheUri() {
        assertThat(settings("mongodb://localhost/db?replicaSet=rs0", Duration.ofSeconds(30))
                .getClusterSettings().getRequiredReplicaSetName()).isEqualTo("rs0");
    }

    @Test
    void rejectsATimeoutTooShortForTheChangeStream() {
        assertThatThrownBy(() -> settings("mongodb://localhost/db", Duration.ofMillis(800)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configstream.mongo.socket-timeout must be at least 5s");
    }
}

package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.configstream.spring.ConfigStreamAutoConfiguration.ConfigStreamCollections;
import org.junit.jupiter.api.Test;

class ConfigStreamCollectionsTest {

    private final ConfigStreamProperties.Mongo mongo = new ConfigStreamProperties.Mongo();

    @Test
    void namedAfterTheServiceByDefault() {
        assertThat(ConfigStreamCollections.resolve(mongo, "orders"))
                .isEqualTo(new ConfigStreamCollections("orders_config", "orders_config_history"));
    }

    @Test
    void historyFollowsAConfiguredConfigCollection() {
        mongo.setConfigCollection("team_a_orders");

        assertThat(ConfigStreamCollections.resolve(mongo, "orders"))
                .isEqualTo(new ConfigStreamCollections("team_a_orders", "team_a_orders_history"));
    }

    @Test
    void bothCanBeSetExplicitly() {
        mongo.setConfigCollection("orders_settings");
        mongo.setHistoryCollection("orders_audit");

        assertThat(ConfigStreamCollections.resolve(mongo, null))
                .isEqualTo(new ConfigStreamCollections("orders_settings", "orders_audit"));
    }

    @Test
    void needsAServiceNameUnlessTheCollectionIsSet() {
        assertThatThrownBy(() -> ConfigStreamCollections.resolve(mongo, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.application.name, which is not set")
                .hasMessageContaining("configstream.mongo.config-collection");
    }

    @Test
    void rejectsTheSameNameForBoth() {
        mongo.setConfigCollection("orders");
        mongo.setHistoryCollection("orders");

        assertThatThrownBy(() -> ConfigStreamCollections.resolve(mongo, "orders"))
                .hasMessageContaining("they must differ");
    }
}

package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.configstream.api.ConfigCache;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.Manifest;
import io.github.configstream.api.Property;
import io.github.configstream.api.PropertyDeclaration;
import io.github.configstream.api.PropertyType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigServiceTest {

    static final Property<Boolean> FUNDS_ENABLED = Property.of("feature.funds.enabled", Boolean.class, false);
    static final Property<Integer> FUNDS_LIMIT = Property.of("feature.funds.limit", Integer.class, 3);

    private final ConfigCache cache = new ConfigCache();
    // As with configstream-prod.yml applied: the limit's initial value is 10 in this environment
    private final Manifest manifest = Manifest.of("configstream.yml", List.of(
                    new PropertyDeclaration("feature.funds.enabled", PropertyType.BOOLEAN, false, null),
                    new PropertyDeclaration("feature.funds.limit", PropertyType.INT, 3, null)))
            .withInitialValues(Map.of("feature.funds.limit", 10), "configstream-prod.yml");
    private final ConfigService service = new ConfigService(cache, manifest);

    @Test
    void theStoredValueAlwaysWins() {
        cache.onSnapshot(Map.of(
                "feature.funds.enabled", new ConfigValue(PropertyType.BOOLEAN, true),
                "feature.funds.limit", new ConfigValue(PropertyType.INT, 25)));

        assertThat(service.get(FUNDS_ENABLED)).isTrue();
        assertThat(service.get(FUNDS_LIMIT)).isEqualTo(25);
    }

    @Test
    void aMissingPropertyUsesThisEnvironmentsInitialValue() {
        assertThat(service.get(FUNDS_ENABLED)).isFalse();
        assertThat(service.get(FUNDS_LIMIT)).as("from configstream-prod.yml, not the constant's 3").isEqualTo(10);
    }

    @Test
    void aValueStoredAsAnotherTypeIsNotUsed() {
        cache.onSnapshot(Map.of("feature.funds.limit", new ConfigValue(PropertyType.STRING, "25")));

        assertThat(service.get(FUNDS_LIMIT)).isEqualTo(10);
    }

    @Test
    void anUndeclaredPropertyUsesTheConstantsInitialValue() {
        Property<String> undeclared = Property.of("banner.text", String.class, "hello");

        assertThat(service.get(undeclared)).isEqualTo("hello");
    }
}

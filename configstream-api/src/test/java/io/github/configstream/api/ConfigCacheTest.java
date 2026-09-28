package io.github.configstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigCacheTest {

    private static final ConfigValue ONE = new ConfigValue(PropertyType.INT, 1);
    private static final ConfigValue TWO = new ConfigValue(PropertyType.INT, 2);
    private static final ConfigValue THREE = new ConfigValue(PropertyType.INT, 3);

    private final ConfigCache cache = new ConfigCache();

    @Test
    void snapshotPopulatesCache() {
        cache.onSnapshot(Map.of("a.key", ONE, "b.key", TWO));

        assertThat(cache.get("a.key")).contains(ONE);
        assertThat(cache.getAll()).isEqualTo(Map.of("a.key", ONE, "b.key", TWO));
    }

    @Test
    void laterSnapshotReplacesEverything() {
        cache.onSnapshot(Map.of("a.key", ONE, "b.key", TWO));
        cache.onSnapshot(Map.of("b.key", THREE, "c.key", ONE));

        assertThat(cache.getAll()).isEqualTo(Map.of("b.key", THREE, "c.key", ONE));
    }

    @Test
    void upsertAndDelete() {
        cache.onChange(ConfigChange.upsert("a.key", ONE));
        cache.onChange(ConfigChange.upsert("a.key", TWO));
        assertThat(cache.get("a.key")).contains(TWO);

        cache.onChange(ConfigChange.delete("a.key"));
        assertThat(cache.get("a.key")).isEmpty();
    }

    @Test
    void deletingUnknownKeyIsNoOp() {
        cache.onChange(ConfigChange.delete("missing.key"));

        assertThat(cache.getAll()).isEmpty();
    }

    @Test
    void getAllReturnsImmutableCopy() {
        cache.onChange(ConfigChange.upsert("a.key", ONE));
        Map<String, ConfigValue> copy = cache.getAll();
        cache.onChange(ConfigChange.upsert("b.key", TWO));

        assertThat(copy).containsOnlyKeys("a.key");
        assertThatThrownBy(() -> copy.put("x.key", ONE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changeValidatesValue() {
        assertThatThrownBy(() -> new ConfigChange(ConfigChange.Type.UPSERT, "a.key", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConfigChange(ConfigChange.Type.DELETE, "a.key", ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueMustMatchItsType() {
        assertThatThrownBy(() -> new ConfigValue(PropertyType.INT, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Integer");
    }
}

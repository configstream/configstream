package io.github.configstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigCacheTest {

    private static final PropertyId A = PropertyId.of("a.key", PropertyType.INT);
    private static final PropertyId B = PropertyId.of("b.key", PropertyType.INT);
    private static final PropertyId C = PropertyId.of("c.key", PropertyType.INT);
    private static final ConfigValue ONE = new ConfigValue(PropertyType.INT, 1);
    private static final ConfigValue TWO = new ConfigValue(PropertyType.INT, 2);
    private static final ConfigValue THREE = new ConfigValue(PropertyType.INT, 3);

    private final ConfigCache cache = new ConfigCache();

    @Test
    void snapshotPopulatesCache() {
        cache.onSnapshot(Map.of(A, ONE, B, TWO));

        assertThat(cache.get(A)).contains(ONE);
        assertThat(cache.getAll()).isEqualTo(Map.of(A, ONE, B, TWO));
    }

    @Test
    void laterSnapshotReplacesEverything() {
        cache.onSnapshot(Map.of(A, ONE, B, TWO));
        cache.onSnapshot(Map.of(B, THREE, C, ONE));

        assertThat(cache.getAll()).isEqualTo(Map.of(B, THREE, C, ONE));
    }

    @Test
    void upsertAndDelete() {
        cache.onChange(ConfigChange.upsert(A, ONE));
        cache.onChange(ConfigChange.upsert(A, TWO));
        assertThat(cache.get(A)).contains(TWO);

        cache.onChange(ConfigChange.delete(A));
        assertThat(cache.get(A)).isEmpty();
    }

    @Test
    void theSameKeyWithAnotherTypeIsAnotherProperty() {
        PropertyId asString = PropertyId.of("a.key", PropertyType.STRING);
        cache.onChange(ConfigChange.upsert(A, ONE));
        cache.onChange(ConfigChange.upsert(asString, new ConfigValue(PropertyType.STRING, "one")));

        assertThat(cache.get(A)).contains(ONE);
        assertThat(cache.get(asString)).contains(new ConfigValue(PropertyType.STRING, "one"));
        cache.onChange(ConfigChange.delete(A));
        assertThat(cache.get(asString)).isPresent();
    }

    @Test
    void deletingUnknownKeyIsNoOp() {
        cache.onChange(ConfigChange.delete(PropertyId.of("missing.key", PropertyType.INT)));

        assertThat(cache.getAll()).isEmpty();
    }

    @Test
    void getAllReturnsImmutableCopy() {
        cache.onChange(ConfigChange.upsert(A, ONE));
        Map<PropertyId, ConfigValue> copy = cache.getAll();
        cache.onChange(ConfigChange.upsert(B, TWO));

        assertThat(copy).containsOnlyKeys(A);
        assertThatThrownBy(() -> copy.put(C, ONE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changeValidatesValue() {
        assertThatThrownBy(() -> new ConfigChange(ConfigChange.Type.UPSERT, A, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConfigChange(ConfigChange.Type.DELETE, A, ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigChange.upsert(A, new ConfigValue(PropertyType.STRING, "1")))
                .hasMessage("a string value can't belong to a.key (int)");
    }

    @Test
    void valueMustMatchItsType() {
        assertThatThrownBy(() -> new ConfigValue(PropertyType.INT, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Integer");
    }
}

package io.github.configstream.admin.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.configstream.admin.ConfigStreamAdminProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstanceRegistryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));
    private final InstanceRegistry registry = new InstanceRegistry(new ConfigStreamAdminProperties(), clock);

    @Test
    void groupsInstancesByServiceSortedByName() {
        registry.register(instance("orders", "o-1"));
        registry.register(instance("orders", "o-2"));
        registry.register(instance("billing", "b-1"));

        assertThat(registry.services()).extracting(ServiceSummary::serviceName).containsExactly("billing", "orders");
        assertThat(registry.service("orders").orElseThrow().instances())
                .extracting(RegisteredInstance::instanceId).containsExactly("o-1", "o-2");
    }

    @Test
    void instanceIsDownAfterLeaseExpiresAndUpAgainOnHeartbeat() {
        registry.register(instance("orders", "o-1"));

        clock.advance(Duration.ofSeconds(45));
        assertThat(onlyInstance().up()).as("exactly at lease end").isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(onlyInstance().up()).isFalse();
        assertThat(registry.service("orders").orElseThrow().activeCount()).isZero();

        assertThat(registry.heartbeat("o-1")).isTrue();
        assertThat(onlyInstance().up()).isTrue();
    }

    @Test
    void activeInstancesLeaveOutThoseThatStoppedHeartbeating() {
        registry.register(instance("orders", "a-stale"));
        clock.advance(Duration.ofMinutes(1));
        registry.register(instance("orders", "b-fresh"));

        ServiceSummary orders = registry.service("orders").orElseThrow();
        assertThat(orders.activeInstances()).extracting(RegisteredInstance::instanceId).containsExactly("b-fresh");
        assertThat(orders.activeCount()).isEqualTo(1);
    }

    @Test
    void upInstancesAreListedFirst() {
        registry.register(instance("orders", "a-stale"));
        clock.advance(Duration.ofMinutes(1));
        registry.register(instance("orders", "b-fresh"));

        assertThat(registry.service("orders").orElseThrow().instances())
                .extracting(RegisteredInstance::instanceId).containsExactly("b-fresh", "a-stale");
    }

    @Test
    void evictsInstancesSilentForTooLong() {
        registry.register(instance("orders", "o-1"));

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThat(registry.services()).isEmpty();
        assertThat(registry.heartbeat("o-1")).as("evicted instance must re-register").isFalse();
    }

    @Test
    void heartbeatForUnknownInstanceIsRejected() {
        assertThat(registry.heartbeat("nope")).isFalse();
    }

    @Test
    void reRegisteringRefreshesTheInstance() {
        registry.register(new InstanceRegistration("orders", "o-1", "10.0.0.1", 8080, "team-a", null));
        clock.advance(Duration.ofMinutes(5));
        registry.register(new InstanceRegistration("orders", "o-1", "10.0.0.2", 9090, "team-b", null));

        RegisteredInstance instance = onlyInstance();
        assertThat(instance.up()).isTrue();
        assertThat(instance.baseUrl()).isEqualTo("http://10.0.0.2:9090");
        assertThat(registry.service("orders").orElseThrow().team()).isEqualTo("team-b");
    }

    @Test
    void deregisterRemovesTheInstance() {
        registry.register(instance("orders", "o-1"));
        registry.deregister("o-1");
        registry.deregister("o-1"); // idempotent

        assertThat(registry.services()).isEmpty();
    }

    @Test
    void instanceWithoutPortHasNoAddress() {
        registry.register(new InstanceRegistration("worker", "w-1", "10.0.0.1", null, null, null));

        assertThat(onlyInstance("worker").baseUrl()).isNull();
    }

    private RegisteredInstance onlyInstance() {
        return onlyInstance("orders");
    }

    @Test
    void aPropertyIsAnOrphanOnlyWhenNoActiveInstanceDeclaresIt() {
        // Blue-green: blue still declares the old flag, green declares the new one
        registry.register(declaring("orders", "blue", "feature.old.flag", "limits.max"));
        registry.register(declaring("orders", "green", "feature.new.flag", "limits.max"));

        ServiceSummary orders = registry.service("orders").orElseThrow();
        assertThat(orders.isOrphan("feature.old.flag", "boolean")).isFalse();
        assertThat(orders.isOrphan("feature.new.flag", "boolean")).isFalse();
        assertThat(orders.isOrphan("feature.gone", "boolean")).isTrue();
        assertThat(orders.declaringCount("limits.max", "boolean")).isEqualTo(2);
        assertThat(orders.declaredProperties()).extracting(DeclaredProperty::key)
                .containsExactlyInAnyOrder("feature.old.flag", "limits.max", "feature.new.flag");

        // Blue stops: the old flag becomes an orphan
        registry.deregister("blue");
        assertThat(registry.service("orders").orElseThrow().isOrphan("feature.old.flag", "boolean")).isTrue();
    }

    @Test
    void aKeyWhoseTypeChangedIsTwoPropertiesUntilTheOldVersionStops() {
        // Blue-green: green changed limits.max from int to string
        registry.register(new InstanceRegistration("orders", "blue", "localhost", 8080, null,
                List.of(new DeclaredProperty("limits.max", "int", "Old limit"))));
        registry.register(new InstanceRegistration("orders", "green", "localhost", 8081, null,
                List.of(new DeclaredProperty("limits.max", "string", "New limit"))));

        ServiceSummary orders = registry.service("orders").orElseThrow();
        assertThat(orders.declaringCount("limits.max", "int")).isEqualTo(1);
        assertThat(orders.declaringCount("limits.max", "string")).isEqualTo(1);
        assertThat(orders.isOrphan("limits.max", "int")).isFalse();
        assertThat(orders.declaration("limits.max", "string")).get().extracting(DeclaredProperty::description)
                .isEqualTo("New limit");
        assertThat(orders.declaredProperties()).hasSize(2);

        registry.deregister("blue");
        orders = registry.service("orders").orElseThrow();
        assertThat(orders.isOrphan("limits.max", "int")).isTrue();
        assertThat(orders.isOrphan("limits.max", "string")).isFalse();
    }

    @Test
    void anInstanceThatStoppedHeartbeatingNoLongerKeepsItsPropertiesInUse() {
        registry.register(declaring("orders", "blue", "feature.old.flag"));
        clock.advance(Duration.ofMinutes(1));
        registry.register(declaring("orders", "green", "feature.new.flag"));

        assertThat(registry.service("orders").orElseThrow().isOrphan("feature.old.flag", "boolean")).isTrue();
    }

    @Test
    void nothingIsAnOrphanWhileAnActiveInstanceHasNotReportedItsProperties() {
        registry.register(declaring("orders", "new", "limits.max"));
        registry.register(instance("orders", "old")); // older configstream: sends no properties

        assertThat(registry.service("orders").orElseThrow().isOrphan("feature.old.flag", "boolean")).isFalse();
    }

    /** An instance declaring the keys, all as booleans. */
    private static InstanceRegistration declaring(String service, String id, String... keys) {
        return new InstanceRegistration(service, id, "localhost", 8080, "team-a",
                java.util.Arrays.stream(keys).map(k -> new DeclaredProperty(k, "boolean", null)).toList());
    }

    private RegisteredInstance onlyInstance(String service) {
        return registry.service(service).orElseThrow().instances().get(0);
    }

    static InstanceRegistration instance(String service, String id) {
        return new InstanceRegistration(service, id, "localhost", 8080, "team-a", null);
    }

    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

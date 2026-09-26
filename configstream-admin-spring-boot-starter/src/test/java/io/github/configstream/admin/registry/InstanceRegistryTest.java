package io.github.configstream.admin.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.configstream.admin.ConfigStreamAdminProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
        registry.register(new InstanceRegistration("orders", "o-1", "10.0.0.1", 8080, "team-a"));
        clock.advance(Duration.ofMinutes(5));
        registry.register(new InstanceRegistration("orders", "o-1", "10.0.0.2", 9090, "team-b"));

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
        registry.register(new InstanceRegistration("worker", "w-1", "10.0.0.1", null, null));

        assertThat(onlyInstance("worker").baseUrl()).isNull();
    }

    private RegisteredInstance onlyInstance() {
        return onlyInstance("orders");
    }

    private RegisteredInstance onlyInstance(String service) {
        return registry.service(service).orElseThrow().instances().get(0);
    }

    static InstanceRegistration instance(String service, String id) {
        return new InstanceRegistration(service, id, "localhost", 8080, "team-a");
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

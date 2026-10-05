package io.github.configstream.admin.registry;

import io.github.configstream.admin.ConfigStreamAdminProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory registry of running configstream instances, lease-based like Eureka: an instance is up
 * while its heartbeats keep arriving within the lease duration, shown as down once they stop, and
 * evicted after {@code configstream.admin-server.evict-after}. Instances re-register by themselves if the admin
 * app restarts and loses this state.
 */
public class InstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);

    private final Map<String, Entry> instances = new ConcurrentHashMap<>();
    private final ConfigStreamAdminProperties properties;
    private final Clock clock;

    public InstanceRegistry(ConfigStreamAdminProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Adds the instance, or refreshes it if it re-registers with the same ID. */
    public void register(InstanceRegistration registration) {
        Instant now = clock.instant();
        Entry previous = instances.put(registration.instanceId(), new Entry(registration, now, now));
        if (previous == null) {
            log.info("Registered {}/{} at {}:{}", registration.serviceName(), registration.instanceId(),
                    registration.host(), registration.port());
        }
    }

    /** The service an instance registered for, if it is known. */
    public Optional<String> serviceNameOf(String instanceId) {
        Entry entry = instances.get(instanceId);
        return entry == null ? Optional.empty() : Optional.of(entry.registration.serviceName());
    }

    /** @return false if the instance is unknown, telling it to register again */
    public boolean heartbeat(String instanceId) {
        Instant now = clock.instant();
        return instances.computeIfPresent(instanceId,
                (id, entry) -> new Entry(entry.registration, entry.registeredAt, now)) != null;
    }

    public void deregister(String instanceId) {
        Entry removed = instances.remove(instanceId);
        if (removed != null) {
            log.info("Deregistered {}/{}", removed.registration.serviceName(), instanceId);
        }
    }

    /** All services, sorted by name, each with its instances (up ones first). */
    public List<ServiceSummary> services() {
        Instant now = clock.instant();
        evictExpired(now);
        return instances.values().stream()
                .collect(Collectors.groupingBy(e -> e.registration.serviceName()))
                .entrySet().stream()
                .map(group -> summarize(group.getKey(), group.getValue(), now))
                .sorted(Comparator.comparing(ServiceSummary::serviceName))
                .toList();
    }

    public Optional<ServiceSummary> service(String serviceName) {
        return services().stream().filter(s -> s.serviceName().equals(serviceName)).findFirst();
    }

    private ServiceSummary summarize(String serviceName, List<Entry> entries, Instant now) {
        List<RegisteredInstance> views = entries.stream()
                .map(e -> new RegisteredInstance(e.registration, e.registeredAt, e.lastHeartbeat,
                        !e.lastHeartbeat.plus(properties.getLeaseDuration()).isBefore(now)))
                .sorted(Comparator.comparing(RegisteredInstance::up).reversed()
                        .thenComparing(RegisteredInstance::instanceId))
                .toList();
        String team = entries.stream()
                .max(Comparator.comparing(e -> e.registeredAt))
                .map(e -> e.registration.team())
                .orElse(null);
        return new ServiceSummary(serviceName, team, views);
    }

    private void evictExpired(Instant now) {
        instances.values().removeIf(e -> {
            boolean expired = e.lastHeartbeat.plus(properties.getEvictAfter()).isBefore(now);
            if (expired) {
                log.info("Evicted {}/{}: no heartbeat since {}", e.registration.serviceName(),
                        e.registration.instanceId(), e.lastHeartbeat);
            }
            return expired;
        });
    }

    private record Entry(InstanceRegistration registration, Instant registeredAt, Instant lastHeartbeat) {
    }
}

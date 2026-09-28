package io.github.configstream.admin.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One service and all of its registered instances.
 *
 * @param team taken from the most recently registered instance; may be {@code null}
 */
public record ServiceSummary(String serviceName, String team, List<RegisteredInstance> instances) {

    /**
     * Instances that sent a heartbeat within the lease duration. The admin shows only these: it is a config
     * tool, not a health monitor, so instances that stopped heartbeating are dropped rather than shown as down.
     */
    public List<RegisteredInstance> activeInstances() {
        return instances.stream().filter(RegisteredInstance::up).toList();
    }

    public long activeCount() {
        return activeInstances().size();
    }

    /** Every property declared by at least one active instance, by key. */
    public Map<String, DeclaredProperty> declaredProperties() {
        Map<String, DeclaredProperty> declared = new LinkedHashMap<>();
        for (RegisteredInstance instance : activeInstances()) {
            List<DeclaredProperty> properties = instance.registration().properties();
            if (properties != null) {
                properties.forEach(p -> declared.putIfAbsent(p.key(), p));
            }
        }
        return declared;
    }

    public Optional<DeclaredProperty> declaration(String key) {
        return Optional.ofNullable(declaredProperties().get(key));
    }

    /**
     * Whether {@code key} is an orphan: stored, but declared by no active instance, so nothing reads it and it may be
     * deleted. Never true while an active instance hasn't reported its declarations, or while none is active.
     */
    public boolean isOrphan(String key) {
        List<RegisteredInstance> active = activeInstances();
        boolean allReported = !active.isEmpty()
                && active.stream().allMatch(i -> i.registration().properties() != null);
        return allReported && !declaredProperties().containsKey(key);
    }

    /** How many active instances declare {@code key}. */
    public long declaringCount(String key) {
        return activeInstances().stream()
                .filter(i -> i.registration().properties() != null
                        && i.registration().properties().stream().anyMatch(p -> p.key().equals(key)))
                .count();
    }
}

package io.github.configstream.admin.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One service and all of its registered instances.
 *
 * <p>A property is identified by its key and type: when a new version of a service changes a field's type, the same
 * key is stored with both types while old and new instances run side by side, and the old one becomes an orphan once
 * no active instance declares it.
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

    /** Every property declared by at least one active instance, once per key and type. */
    public List<DeclaredProperty> declaredProperties() {
        List<DeclaredProperty> declared = new ArrayList<>();
        for (RegisteredInstance instance : activeInstances()) {
            List<DeclaredProperty> properties = instance.registration().properties();
            if (properties != null) {
                properties.stream()
                        .filter(p -> declared.stream().noneMatch(d -> d.is(p.key(), p.type())))
                        .forEach(declared::add);
            }
        }
        return declared;
    }

    public Optional<DeclaredProperty> declaration(String key, String type) {
        return declaredProperties().stream().filter(p -> p.is(key, type)).findFirst();
    }

    /**
     * Whether the property is an orphan: stored, but declared by no active instance, so nothing reads it and it may be
     * deleted. Never true while an active instance hasn't reported its declarations, or while none is active.
     */
    public boolean isOrphan(String key, String type) {
        List<RegisteredInstance> active = activeInstances();
        boolean allReported = !active.isEmpty()
                && active.stream().allMatch(i -> i.registration().properties() != null);
        return allReported && declaringCount(key, type) == 0;
    }

    /** How many active instances declare the property. */
    public long declaringCount(String key, String type) {
        return activeInstances().stream()
                .filter(i -> i.registration().properties() != null
                        && i.registration().properties().stream().anyMatch(p -> p.is(key, type)))
                .count();
    }
}

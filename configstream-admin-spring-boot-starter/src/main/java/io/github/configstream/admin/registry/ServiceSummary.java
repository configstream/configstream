package io.github.configstream.admin.registry;

import java.util.List;

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
}

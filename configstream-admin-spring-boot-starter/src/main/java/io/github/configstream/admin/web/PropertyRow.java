package io.github.configstream.admin.web;

import io.github.configstream.admin.client.ConfigEntry;
import io.github.configstream.admin.registry.ServiceSummary;
import java.util.List;

/**
 * One property as the service page shows it. The key and type identify it: the same key can be listed twice, with two
 * types, while instances running different versions of the service (for example during a blue-green deployment)
 * declare it differently.
 *
 * @param description as declared by an instance (from the field's Javadoc); {@code null} if none does or it has none
 * @param orphan      stored, but declared by no active instance: nothing reads it, and it may be deleted
 * @param usedBy      how many active instances declare it
 * @param activeCount how many instances are active
 */
public record PropertyRow(String key, String type, String value, String description, boolean orphan, long usedBy,
        long activeCount) {

    public boolean isBoolean() {
        return "boolean".equals(type);
    }

    /** Whether only some of the active instances declare it, as while old and new versions of the service run side by side. */
    public boolean partlyUsed() {
        return usedBy > 0 && usedBy < activeCount;
    }

    static List<PropertyRow> of(List<ConfigEntry> config, ServiceSummary service) {
        long active = service.activeCount();
        return config.stream()
                .map(e -> new PropertyRow(e.key(), e.type(), e.value(),
                        service.declaration(e.key(), e.type()).map(d -> d.description()).orElse(null),
                        service.isOrphan(e.key(), e.type()), service.declaringCount(e.key(), e.type()), active))
                .toList();
    }
}

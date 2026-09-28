package io.github.configstream.admin.web;

import io.github.configstream.admin.client.ConfigEntry;
import io.github.configstream.admin.registry.DeclaredProperty;
import io.github.configstream.admin.registry.ServiceSummary;
import java.util.List;
import java.util.Map;

/**
 * One property as the service page shows it.
 *
 * @param description from the manifest of an instance that declares it; {@code null} if none does or it has none
 * @param orphan      stored, but declared by no active instance: nothing reads it, and it may be deleted
 */
public record PropertyRow(String key, String type, String value, String description, boolean orphan) {

    public boolean isBoolean() {
        return "boolean".equals(type);
    }

    static List<PropertyRow> of(Map<String, ConfigEntry> config, ServiceSummary service) {
        Map<String, DeclaredProperty> declared = service.declaredProperties();
        return config.entrySet().stream()
                .map(e -> new PropertyRow(e.getKey(), e.getValue().type(), e.getValue().value(),
                        declared.containsKey(e.getKey()) ? declared.get(e.getKey()).description() : null,
                        service.isOrphan(e.getKey())))
                .toList();
    }
}

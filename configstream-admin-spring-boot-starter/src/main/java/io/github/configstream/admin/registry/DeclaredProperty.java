package io.github.configstream.admin.registry;

/**
 * A property an instance's manifest declares, as sent when it registers.
 *
 * @param type        {@code boolean}, {@code int}, {@code decimal} or {@code string}
 * @param description may be {@code null}
 */
public record DeclaredProperty(String key, String type, String description) {
}

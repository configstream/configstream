package io.github.configstream.admin.registry;

/**
 * A live property an instance declares (in its {@code @LiveConfig} classes), as sent when it registers.
 *
 * @param type        {@code boolean}, {@code int}, {@code decimal} or {@code string}
 * @param description may be {@code null}
 */
public record DeclaredProperty(String key, String type, String description) {
}

package io.github.configstream.admin.client;

/**
 * A property as a service reports it: its type and its value as text.
 *
 * @param type {@code boolean}, {@code int}, {@code decimal} or {@code string}
 */
public record ConfigEntry(String type, String value) {
}

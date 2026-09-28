package io.github.configstream.admin.client;

/**
 * A property as a service reports it: its key, its type and its value as text. The key and type identify it.
 *
 * @param type {@code boolean}, {@code int}, {@code decimal} or {@code string}
 */
public record ConfigEntry(String key, String type, String value) {

    public boolean is(String key, String type) {
        return this.key.equals(key) && this.type.equals(type);
    }
}

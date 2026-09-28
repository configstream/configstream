package io.github.configstream.admin.registry;

/**
 * A live property an instance declares (in its {@code @LiveConfig} classes), as sent when it registers.
 *
 * @param type        {@code boolean}, {@code int}, {@code decimal} or {@code string}
 * @param description may be {@code null}
 */
public record DeclaredProperty(String key, String type, String description) {

    /** Whether this is the property with that key and type: the same key with another type is another property. */
    public boolean is(String key, String type) {
        return this.key.equals(key) && this.type.equals(type);
    }
}

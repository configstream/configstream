package io.github.configstream.api;

import java.util.Objects;

/**
 * A request to set an existing property's value, carrying who asked for it so the change is auditable.
 *
 * <p>Rolling back is just another update: write the historical value again, ideally with a comment
 * such as {@code "Reverted to v3"}.
 *
 * @param key       the property key
 * @param value     the new value as text, parsed according to the property's type
 * @param type      the type the caller believes the property has; {@code null} to skip the check. A different type
 *                  is rejected: types are defined only by the application's code
 * @param changedBy the authenticated identity making the change, e.g. a user name from the admin app
 * @param comment   optional free-text reason; may be {@code null}
 */
public record ConfigUpdate(String key, String value, PropertyType type, String changedBy, String comment) {

    public ConfigUpdate {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(changedBy, "changedBy");
    }
}

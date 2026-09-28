package io.github.configstream.api;

import java.util.Objects;

/**
 * A request to set an existing property's value, carrying who asked for it so the change is auditable.
 *
 * <p>Rolling back is just another update: write the historical value again, ideally with a comment
 * such as {@code "Reverted to v3"}.
 *
 * @param id        the property's key and type
 * @param value     the new value as text, parsed according to the property's type
 * @param changedBy the authenticated identity making the change, e.g. a user name from the admin app
 * @param comment   optional free-text reason; may be {@code null}
 */
public record ConfigUpdate(PropertyId id, String value, String changedBy, String comment) {

    public ConfigUpdate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(changedBy, "changedBy");
    }
}

package io.github.configstream.api;

import java.util.Objects;

/**
 * A request to delete one property, carrying who asked for it so the deletion is auditable.
 *
 * <p>The property is removed from the store and every cache; its history is kept.
 *
 * @param id        the property's key and type
 * @param changedBy the authenticated identity making the change
 * @param comment   optional free-text reason; may be {@code null}
 */
public record ConfigDeletion(PropertyId id, String changedBy, String comment) {

    public ConfigDeletion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(changedBy, "changedBy");
    }
}

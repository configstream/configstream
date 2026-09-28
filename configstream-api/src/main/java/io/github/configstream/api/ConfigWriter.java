package io.github.configstream.api;

import java.util.Optional;

/**
 * Writes properties to the backing store, recording each change in the {@link ConfigHistory}.
 * Writers never touch caches directly: every running instance, including this one, picks the change
 * up through its {@link ConfigChangeSource}.
 */
public interface ConfigWriter {

    /**
     * Creates the property with its initial value, unless the key already exists, in which case nothing is written:
     * an existing value is never replaced. Safe to call from many instances at once.
     *
     * @return the type stored under the key: {@code initial}'s type if it was just created, otherwise the existing
     *     property's type, which the caller must compare (a property's type never changes)
     */
    PropertyType createIfAbsent(String key, ConfigValue initial, String changedBy, String comment);

    /**
     * Replaces an existing property's value. The new value and its history entry are stored together or not at all.
     *
     * @return the recorded history entry, or empty if the property already had this value (nothing is written and
     *     no version is used up)
     * @throws PropertyNotFoundException if the property doesn't exist: updates never create properties
     * @throws InvalidConfigValueException if the value isn't valid for the property's type, or the update names a
     *     different type
     */
    Optional<ConfigHistoryEntry> write(ConfigUpdate update);

    /**
     * Removes the property from the store and every cache, recording the deletion in the history (with a
     * {@code null} new value). Its history is kept.
     *
     * @return the recorded history entry, or empty if the property doesn't exist
     */
    Optional<ConfigHistoryEntry> delete(ConfigDeletion deletion);
}

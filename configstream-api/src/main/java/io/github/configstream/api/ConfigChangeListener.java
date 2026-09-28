package io.github.configstream.api;

import java.util.Map;

/**
 * Receives config data from a {@link ConfigChangeSource}.
 *
 * <p>Callbacks are always invoked sequentially (never concurrently) and in the order the
 * changes happened in the backing store.
 */
public interface ConfigChangeListener {

    /**
     * The complete current state of the config store. Replaces everything the listener knew before.
     * Called once on startup, and again if the source has to resynchronise (e.g. it lost its
     * position in the change stream).
     */
    void onSnapshot(Map<String, ConfigValue> entries);

    /** A single incremental change that happened after the most recent snapshot. */
    void onChange(ConfigChange change);
}

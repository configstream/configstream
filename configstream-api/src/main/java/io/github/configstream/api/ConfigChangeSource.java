package io.github.configstream.api;

import java.util.Map;

/**
 * A backing store that can report its current config and push subsequent changes.
 * Implemented once per database (MongoDB change streams, later Postgres LISTEN/NOTIFY, ...).
 */
public interface ConfigChangeSource extends AutoCloseable {

    /** Reads every property from the store. Entries whose value doesn't fit their type are left out. Does not require {@link #start}. */
    Map<String, ConfigValue> loadInitial();

    /**
     * Starts watching the store. Implementations must:
     * <ol>
     *   <li>begin capturing changes <em>before</em> reading the initial state, so nothing written
     *       during startup is lost;</li>
     *   <li>deliver the initial state via {@link ConfigChangeListener#onSnapshot} before this
     *       method returns;</li>
     *   <li>then deliver every later change via {@link ConfigChangeListener#onChange} on a
     *       background thread.</li>
     * </ol>
     *
     * @throws IllegalStateException if already started
     */
    void start(ConfigChangeListener listener);

    /** Stops watching and releases resources. Safe to call more than once. */
    void stop();

    /**
     * Whether the source is receiving changes right now, or {@code null} if it doesn't report it (or hasn't
     * started). Used by health checks.
     */
    default ConfigSourceStatus status() {
        return null;
    }

    @Override
    default void close() {
        stop();
    }
}

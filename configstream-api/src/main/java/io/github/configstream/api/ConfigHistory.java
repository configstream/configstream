package io.github.configstream.api;

import java.util.List;

/** Read access to the append-only change history recorded by a {@link ConfigWriter}. */
public interface ConfigHistory {

    /** The most recent changes to the property, newest first, at most {@code limit} of them. */
    List<ConfigHistoryEntry> history(PropertyId id, int limit);
}

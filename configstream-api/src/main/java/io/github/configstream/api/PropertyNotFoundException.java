package io.github.configstream.api;

/** A change to a property that doesn't exist. Properties are created only from the manifest, never by a change. */
public class PropertyNotFoundException extends RuntimeException {

    public PropertyNotFoundException(String key) {
        super("No property '" + key + "'. Properties are created only from the application manifest "
                + "(configstream.yml).");
    }
}

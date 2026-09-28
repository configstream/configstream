package io.github.configstream.api;

/** A change to a property that doesn't exist. Properties are created only by a service that declares them, never by a change. */
public class PropertyNotFoundException extends RuntimeException {

    public PropertyNotFoundException(PropertyId id) {
        super("No property '" + id.key() + "' of type " + id.type().typeName()
                + ". Properties are created only when a service that declares them starts.");
    }
}

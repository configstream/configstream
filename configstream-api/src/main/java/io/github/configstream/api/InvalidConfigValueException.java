package io.github.configstream.api;

/**
 * A value that doesn't fit the property's type, e.g. text that isn't a valid int. The message is meant for the person
 * who made the change.
 */
public class InvalidConfigValueException extends IllegalArgumentException {

    public InvalidConfigValueException(String message) {
        super(message);
    }
}

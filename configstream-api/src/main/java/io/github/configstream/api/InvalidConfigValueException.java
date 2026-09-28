package io.github.configstream.api;

/**
 * A value, or a change to one, that doesn't fit the property: text that isn't a valid value of its type, or an
 * attempt to change its type. The message is meant for the person who made the change.
 */
public class InvalidConfigValueException extends IllegalArgumentException {

    /** Returned when a change names a different type than the property was declared with. */
    public static final String TYPE_CHANGE_NOT_ALLOWED =
            "Type change not allowed. Types are defined in the application's code.";

    public InvalidConfigValueException(String message) {
        super(message);
    }
}

package io.github.configstream.api;

import java.util.regex.Pattern;

/**
 * The rules for property keys: at least two dot-separated segments, each starting with a letter and continuing with
 * letters, digits, {@code -} or {@code _}, e.g. {@code feature.funds.enabled}. Code generation relies on this: the
 * first segment names the class and the rest the constant ({@code Feature.FUNDS_ENABLED}).
 */
public final class PropertyKeys {

    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)+");

    private PropertyKeys() {
    }

    public static boolean isValid(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** Returns {@code key} if it is valid, otherwise throws an exception explaining the rule. */
    public static String requireValid(String key) {
        if (!isValid(key)) {
            throw new IllegalArgumentException("Invalid property key '" + key + "': use at least two dot-separated "
                    + "parts, each starting with a letter, e.g. feature.funds.enabled.");
        }
        return key;
    }
}

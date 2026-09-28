package io.github.configstream.spring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;

/**
 * Stops startup when the configuration sets a key under a {@link LiveConfig} class's prefix that the class has no
 * property for, such as {@code feature.funds.limt} for {@code feature.funds.limit}. Spring ignores such keys, so the
 * property would silently start from another value, and once created, keep it.
 *
 * <p>Keys are compared the way Spring binds them, so {@code discount-rate}, {@code discountRate} and the environment
 * variable {@code FEATURE_FUNDS_DISCOUNTRATE} all count as the {@code discountRate} field. Keys belonging to another
 * {@code @ConfigurationProperties} class with a longer prefix (e.g. {@code feature.funds.http} under
 * {@code feature.funds}) are left to that class.
 */
class UnknownKeyCheck {

    private static final int MAX_SUGGESTION_DISTANCE = 3;

    private final Environment environment;
    private final ListableBeanFactory beans;
    private volatile List<ConfigurationPropertyName> allPrefixes;

    UnknownKeyCheck(Environment environment, ListableBeanFactory beans) {
        this.environment = environment;
        this.beans = beans;
    }

    /** @throws IllegalStateException listing every unknown key, where it is set, and the likely intended key */
    void verify(LiveConfigClass liveClass) {
        ConfigurationPropertyName prefix = ConfigurationPropertyName.of(liveClass.prefix());
        List<String> keys = liveClass.properties().stream().map(LiveConfigClass.LiveProperty::key).toList();
        Set<String> known = keys.stream().map(k -> uniform(ConfigurationPropertyName.of(k))).collect(Collectors.toSet());
        List<ConfigurationPropertyName> nested = allPrefixes().stream()
                .filter(prefix::isAncestorOf)
                .toList();

        Map<String, String> unknown = new TreeMap<>(); // key as written -> where it is set
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (!(source instanceof IterableConfigurationPropertySource iterable)) {
                continue;
            }
            iterable.stream()
                    .filter(prefix::isAncestorOf)
                    .filter(name -> !known.contains(uniform(name)))
                    .filter(name -> nested.stream().noneMatch(n -> n.equals(name) || n.isAncestorOf(name)))
                    .forEach(name -> unknown.putIfAbsent(name.toString(), origin(source, name)));
        }
        if (unknown.isEmpty()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        unknown.forEach((key, origin) -> problems.add("'" + key + "' (set in " + origin + ")" + suggestion(key, keys)));
        boolean one = problems.size() == 1;
        throw new IllegalStateException(liveClass.type().getName() + " (@LiveConfig) has no property for "
                + String.join("; ", problems) + ". Spring would ignore " + (one ? "it" : "them") + ", so the "
                + (one ? "property meant would start from another value" : "properties meant would start from other values")
                + ". Fix or remove " + (one ? "the setting" : "these settings") + ".");
    }

    /** The prefixes of every {@code @ConfigurationProperties} bean, found from their definitions, without creating them. */
    private List<ConfigurationPropertyName> allPrefixes() {
        List<ConfigurationPropertyName> prefixes = allPrefixes;
        if (prefixes == null) {
            prefixes = new ArrayList<>();
            for (String name : beans.getBeanNamesForAnnotation(ConfigurationProperties.class)) {
                ConfigurationProperties annotation = beans.findAnnotationOnBean(name, ConfigurationProperties.class, false);
                if (annotation != null && !annotation.prefix().isEmpty()) {
                    prefixes.add(ConfigurationPropertyName.of(annotation.prefix()));
                }
            }
            allPrefixes = prefixes;
        }
        return prefixes;
    }

    /** The name in Spring's uniform form (lower case, no dashes), so relaxed spellings compare equal. */
    private static String uniform(ConfigurationPropertyName name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.getNumberOfElements(); i++) {
            out.append(i == 0 ? "" : ".").append(name.getElement(i, Form.UNIFORM));
        }
        return out.toString();
    }

    private static String origin(ConfigurationPropertySource source, ConfigurationPropertyName name) {
        ConfigurationProperty property = source.getConfigurationProperty(name);
        if (property != null && property.getOrigin() != null) {
            return property.getOrigin().toString();
        }
        return source.getUnderlyingSource() != null ? source.getUnderlyingSource().toString() : "the configuration";
    }

    private static String suggestion(String unknown, List<String> keys) {
        return keys.stream()
                .min(Comparator.comparingInt(k -> distance(unknown, k)))
                .filter(k -> distance(unknown, k) <= MAX_SUGGESTION_DISTANCE)
                .map(k -> ", did you mean '" + k + "'?")
                .orElse("");
    }

    /** Levenshtein distance: the fewest single-character edits turning {@code a} into {@code b}. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}

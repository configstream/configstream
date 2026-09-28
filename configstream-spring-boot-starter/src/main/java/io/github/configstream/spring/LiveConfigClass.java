package io.github.configstream.spring;

import io.github.configstream.api.PropertyDeclaration;
import io.github.configstream.api.PropertyKeys;
import io.github.configstream.api.PropertyType;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;

/**
 * What a {@link LiveConfig} class declares: one live property per JavaBean property, keyed by the class's
 * {@code @ConfigurationProperties} prefix plus the property name in kebab case ({@code discountRate} in
 * {@code feature.funds} is {@code feature.funds.discount-rate}), typed by the field, and starting from the value Spring
 * has bound into the bean.
 */
record LiveConfigClass(Class<?> type, String prefix, List<LiveProperty> properties) {

    /** One live property: its declaration, and how to read and (if it has a setter) update it on the bean. */
    record LiveProperty(PropertyDeclaration declaration, Method getter, Method setter) {

        String key() {
            return declaration.key();
        }
    }

    /**
     * Reads the declarations of {@code bean}, already bound by Spring.
     *
     * @param descriptions property descriptions by key, e.g. from Spring's configuration metadata
     * @throws IllegalStateException explaining what to change if the class can't be live
     */
    static LiveConfigClass of(Class<?> type, Object bean, Map<String, String> descriptions) {
        MergedAnnotation<ConfigurationProperties> properties = MergedAnnotations.from(type)
                .get(ConfigurationProperties.class);
        if (!properties.isPresent()) {
            throw error(type, "@LiveConfig needs @ConfigurationProperties on the same class, to know the prefix.");
        }
        if (type.isRecord() || Modifier.isFinal(type.getModifiers())) {
            throw error(type, "a @LiveConfig class can't be final or a record: its getters are served from the live "
                    + "values. Use a regular class with getters (and setters).");
        }
        String prefix = properties.getString("prefix").isEmpty() ? properties.getString("value") : properties.getString("prefix");
        List<LiveProperty> live = new ArrayList<>();
        for (PropertyDescriptor descriptor : BeanUtils.getPropertyDescriptors(type)) {
            Method getter = descriptor.getReadMethod();
            if (getter == null || getter.getDeclaringClass() == Object.class) {
                continue;
            }
            String key = prefix + "." + kebab(descriptor.getName());
            PropertyType propertyType = propertyType(descriptor.getPropertyType());
            if (propertyType == null) {
                throw error(type, "property '" + descriptor.getName() + "' has type "
                        + descriptor.getPropertyType().getSimpleName() + ", which can't be live. Use boolean, int, "
                        + "BigDecimal or String, or move it to a @ConfigurationProperties class without @LiveConfig.");
            }
            if (!PropertyKeys.isValid(key)) {
                throw error(type, "property '" + descriptor.getName() + "' would have the key '" + key + "', which is "
                        + "not a valid property key.");
            }
            Object initial = read(getter, bean);
            if (initial == null) {
                throw error(type, "property '" + descriptor.getName() + "' (" + key + ") has no value. Give the field a "
                        + "default, or set it in application.yml, so the property can be created with it.");
            }
            live.add(new LiveProperty(new PropertyDeclaration(key, propertyType, initial, descriptions.get(key)),
                    getter, descriptor.getWriteMethod()));
        }
        return new LiveConfigClass(type, prefix, List.copyOf(live));
    }

    private static PropertyType propertyType(Class<?> javaType) {
        if (javaType == boolean.class || javaType == Boolean.class) {
            return PropertyType.BOOLEAN;
        }
        if (javaType == int.class || javaType == Integer.class) {
            return PropertyType.INT;
        }
        if (javaType == BigDecimal.class) {
            return PropertyType.DECIMAL;
        }
        if (javaType == String.class) {
            return PropertyType.STRING;
        }
        return null;
    }

    /** {@code discountRate} gives {@code discount-rate}, the form Spring uses in configuration files. */
    static String kebab(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (!out.isEmpty()) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Object read(Method getter, Object bean) {
        try {
            return getter.invoke(bean);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not read " + getter + ": " + e.getMessage(), e);
        }
    }

    private static IllegalStateException error(Class<?> type, String problem) {
        return new IllegalStateException(type.getName() + ": " + problem);
    }
}

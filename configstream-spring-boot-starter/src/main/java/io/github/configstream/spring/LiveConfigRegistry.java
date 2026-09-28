package io.github.configstream.spring;

import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.PropertyDeclaration;
import io.github.configstream.api.PropertyType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.event.EventListener;

/**
 * The application's live properties, collected from its {@link LiveConfig} classes. For each class it creates the
 * properties missing from the store (with the value Spring bound), rejects a type that differs from the stored one,
 * serves the class's getters from the live values, and passes every change on to its setters.
 */
public class LiveConfigRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveConfigRegistry.class);

    private final ConfigService config;
    private final ConfigWriter writer;
    private final String serviceName;
    private final Map<String, String> descriptions;
    private final UnknownKeyCheck unknownKeys;

    private final Map<String, PropertyDeclaration> declarations = new ConcurrentHashMap<>();
    private final Map<String, List<Binding>> bindings = new ConcurrentHashMap<>();

    /**
     * @param writer       creates missing properties; {@code null} if the store is read-only here, in which case they
     *                     use their bound values until they exist
     * @param descriptions property descriptions by key, shown in the admin app
     * @param unknownKeys  rejects settings under a live class's prefix that match none of its properties
     */
    LiveConfigRegistry(ConfigService config, ConfigWriter writer, String serviceName, Map<String, String> descriptions,
            UnknownKeyCheck unknownKeys) {
        this.config = config;
        this.writer = writer;
        this.serviceName = serviceName;
        this.descriptions = descriptions;
        this.unknownKeys = unknownKeys;
    }

    /** Every live property this application declares. */
    public List<PropertyDeclaration> declarations() {
        return List.copyOf(declarations.values());
    }

    public boolean declares(String key) {
        return declarations.containsKey(key);
    }

    /**
     * Makes {@code bean}, a bound {@link LiveConfig} class, live and returns what the application should use in its
     * place: a proxy whose getters return the live values.
     *
     * @throws IllegalStateException if the class can't be live, the configuration sets a key under its prefix that it
     *     has no property for, or a property's type differs from the stored one
     */
    Object register(Object bean, Class<?> type) {
        LiveConfigClass liveClass = LiveConfigClass.of(type, bean, descriptions);
        // Before anything is created: a mistyped key would otherwise create the property with the wrong value
        unknownKeys.verify(liveClass);
        List<String> typeChanges = new ArrayList<>();
        Map<Method, String> getters = new ConcurrentHashMap<>();
        for (LiveConfigClass.LiveProperty property : liveClass.properties()) {
            PropertyDeclaration declaration = property.declaration();
            PropertyDeclaration other = declarations.putIfAbsent(property.key(), declaration);
            if (other != null && other.type() != declaration.type()) {
                typeChanges.add("'" + property.key() + "' is declared as " + other.type().typeName() + " and as "
                        + declaration.type().typeName() + " in " + type.getSimpleName());
                continue;
            }
            if (writer != null) {
                PropertyType stored = writer.createIfAbsent(property.key(), declaration.initial(),
                        serviceName + " (startup)", "Created from " + type.getSimpleName());
                if (stored != declaration.type()) {
                    typeChanges.add("'" + property.key() + "' is stored as " + stored.typeName() + " but declared as "
                            + declaration.type().typeName() + " (" + type.getSimpleName() + "."
                            + property.getter().getName() + ")");
                    continue;
                }
                // A property just created is known right away, without waiting for the change stream to report it
                config.seedIfAbsent(property.key(), declaration.initial());
            }
            bindings.computeIfAbsent(property.key(), k -> new ArrayList<>()).add(new Binding(bean, property));
            getters.put(property.getter(), property.key());
            apply(bean, property, current(property.key()));
        }
        if (!typeChanges.isEmpty()) {
            throw new IllegalStateException("A property's type can't change: " + String.join("; ", typeChanges)
                    + ". To change a type, rename the field so the property gets a new key; the old key is shown as "
                    + "orphaned once no running instance declares it.");
        }
        log.info("{} is live: {}", type.getSimpleName(),
                liveClass.properties().stream().map(LiveConfigClass.LiveProperty::key).toList());
        return proxy(bean, getters);
    }

    /**
     * A property's live value: the stored one, or its initial value while it is missing from the store (for example
     * deleted by hand) or its stored value doesn't fit its type.
     */
    Object current(String key) {
        PropertyDeclaration declaration = declarations.get(key);
        ConfigValue stored = config.value(key);
        if (stored != null && stored.type() == declaration.type()) {
            return stored.value();
        }
        return declaration.initialValue();
    }

    /** Passes a change on to the setters of every bean declaring the property. */
    @EventListener
    void onConfigChanged(ConfigChangedEvent event) {
        List<Binding> bound = bindings.get(event.key());
        if (bound == null) {
            return;
        }
        Object value = current(event.key());
        for (Binding binding : bound) {
            apply(binding.bean(), binding.property(), value);
        }
    }

    private void apply(Object bean, LiveConfigClass.LiveProperty property, Object value) {
        Method setter = property.setter();
        if (setter == null) {
            return; // no setter: the getter still returns the live value through the proxy
        }
        try {
            setter.invoke(bean, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            log.warn("Could not update {} through {}: {}", property.key(), setter, e.toString());
        }
    }

    /** A subclass proxy whose live getters return the current value, safely across threads; other calls pass through. */
    private Object proxy(Object bean, Map<Method, String> getters) {
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> {
            String key = getters.get(invocation.getMethod());
            return key != null ? current(key) : invocation.proceed();
        });
        return factory.getProxy(bean.getClass().getClassLoader());
    }

    private record Binding(Object bean, LiveConfigClass.LiveProperty property) {
    }
}

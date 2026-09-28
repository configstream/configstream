package io.github.configstream.spring;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Hands each {@link LiveConfig} bean to the {@link LiveConfigRegistry} after Spring has bound it (binding happens
 * before initialization), and puts the registry's live proxy in its place.
 */
class LiveConfigPostProcessor implements BeanPostProcessor {

    // Looked up on first use: a post-processor must not pull other beans in early
    private final ObjectProvider<LiveConfigRegistry> registry;

    LiveConfigPostProcessor(ObjectProvider<LiveConfigRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopUtils.getTargetClass(bean);
        if (!AnnotatedElementUtils.hasAnnotation(type, LiveConfig.class)) {
            return bean;
        }
        return registry.getObject().register(bean, type);
    }
}

package io.github.configstream.samples.orders.config;

import io.github.configstream.api.Property;

/** Properties declared in configstream.yml under {@code feature.*}. Written by hand until code generation arrives. */
public final class Feature {

    public static final Property<Boolean> X_ENABLED = Property.of("feature.x.enabled", Boolean.class, false);

    private Feature() {
    }
}

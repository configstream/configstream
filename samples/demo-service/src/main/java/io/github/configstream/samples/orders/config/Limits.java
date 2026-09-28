package io.github.configstream.samples.orders.config;

import io.github.configstream.api.Property;

/** Properties declared in configstream.yml under {@code limits.*}. Written by hand until code generation arrives. */
public final class Limits {

    public static final Property<Integer> MAX = Property.of("limits.max", Integer.class, 100);

    private Limits() {
    }
}

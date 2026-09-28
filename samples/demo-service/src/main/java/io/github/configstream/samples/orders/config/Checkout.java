package io.github.configstream.samples.orders.config;

import io.github.configstream.api.Property;

/** Properties declared in configstream.yml under {@code checkout.*}. Written by hand until code generation arrives. */
public final class Checkout {

    public static final Property<String> BANNER = Property.of("checkout.banner", String.class, "Free shipping on orders over $50");

    private Checkout() {
    }
}

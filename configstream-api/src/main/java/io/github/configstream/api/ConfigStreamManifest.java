package io.github.configstream.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates typed {@link Property} constants from the application's manifest at compile time, so a misspelled key
 * or a value read as the wrong type doesn't compile. Put it on any one class, typically the application class, and
 * add {@code configstream-processor} as an annotation processor.
 *
 * <p>Each key's first part names a class and the rest a constant: {@code feature.funds.limit} becomes
 * {@code Feature.FUNDS_LIMIT}, a {@code Property<Integer>} if the manifest declares it as {@code int}. The build also
 * checks the manifest and every {@code configstream-<environment>.yml} next to it.
 *
 * <pre>{@code
 * @SpringBootApplication
 * @ConfigStreamManifest
 * public class OrdersApplication { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface ConfigStreamManifest {

    /**
     * The manifest's path among the application's resources. If you change it, also set {@code configstream.manifest}
     * (e.g. {@code classpath:config/configstream.yml}) so the application reads the same file.
     */
    String value() default "configstream.yml";

    /** The package for the generated classes; by default the annotated class's package. */
    String packageName() default "";
}

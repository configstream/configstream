package io.github.configstream.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a {@code @ConfigurationProperties} class live: its properties are stored in the config store and changes
 * reach every instance within about a second, with no restart. Code keeps reading them through the class's getters.
 *
 * <pre>{@code
 * @ConfigurationProperties("feature.funds")
 * @LiveConfig
 * public class FundsProperties {
 *     private boolean enabled = false;   // feature.funds.enabled (boolean)
 *     private int limit = 3;             // feature.funds.limit (int)
 *     // getters and setters
 * }
 * }</pre>
 *
 * <p>On startup each property missing from the store is created with the value Spring has bound (the field's default,
 * or {@code application.yml}, {@code application-prod.yml} and so on); after that the stored value always wins, and it
 * is changed in the admin app. A property's type never changes: to change it, rename the field.
 *
 * <p>Supported field types: {@code boolean}, {@code int} (and their wrappers), {@link java.math.BigDecimal} and
 * {@link String}. The class must not be final or a record, because its getters are served from the live values.
 * Only classes with this annotation are stored; other configuration, such as connection details, never is.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LiveConfig {
}

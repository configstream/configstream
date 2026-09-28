package io.github.configstream.samples.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * An ordinary service that uses configstream only through its starter. Its live properties are the
 * {@code @LiveConfig} classes in this package, found by {@link ConfigurationPropertiesScan}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DemoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }
}

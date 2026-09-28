package io.github.configstream.samples.orders;

import io.github.configstream.api.ConfigStreamManifest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * An ordinary service that uses configstream only through its starter. {@link ConfigStreamManifest} generates
 * {@code Feature}, {@code Limits} and {@code Checkout} in this package from {@code configstream.yml}.
 */
@SpringBootApplication
@ConfigStreamManifest
public class DemoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }
}

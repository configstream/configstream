package io.github.configstream.demoservice;

import io.github.configstream.spring.LiveConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * An application that uses configstream purely through its Spring Boot starter and two {@link LiveConfig} classes,
 * standing in for a real service in the end-to-end test. Lives outside the configstream-admin package so the admin
 * app's component scan doesn't pick it up.
 */
@SpringBootApplication
@EnableConfigurationProperties({DemoServiceApplication.FeatureX.class, DemoServiceApplication.Limits.class})
public class DemoServiceApplication {

    /** Declares feature.x.enabled (boolean, initially true). */
    @ConfigurationProperties("feature.x")
    @LiveConfig
    public static class FeatureX {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Declares limits.max (int, initially 10). */
    @ConfigurationProperties("limits")
    @LiveConfig
    public static class Limits {
        private int max = 10;

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            this.max = max;
        }
    }
}

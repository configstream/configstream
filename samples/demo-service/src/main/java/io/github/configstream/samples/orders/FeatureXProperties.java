package io.github.configstream.samples.orders;

import io.github.configstream.spring.LiveConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Live settings for feature X. */
@ConfigurationProperties("feature.x")
@LiveConfig
public class FeatureXProperties {

    /** Turns feature X on for every instance. */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

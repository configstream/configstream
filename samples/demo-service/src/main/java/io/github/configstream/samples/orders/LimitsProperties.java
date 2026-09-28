package io.github.configstream.samples.orders;

import io.github.configstream.spring.LiveConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Live order limits. In prod, application-prod.yml starts max at 500. */
@ConfigurationProperties("limits")
@LiveConfig
public class LimitsProperties {

    /** Maximum items per order. */
    private int max = 100;

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = max;
    }
}

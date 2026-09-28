package io.github.configstream.spring;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** A typical live properties class, as an application would write it. */
@ConfigurationProperties("feature.funds")
@LiveConfig
public class FundsProperties {

    /** Show the funds page. */
    private boolean enabled = false;

    /** Maximum funds shown per page. */
    private int limit = 3;

    private BigDecimal discountRate = new BigDecimal("0.05");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public BigDecimal getDiscountRate() {
        return discountRate;
    }

    public void setDiscountRate(BigDecimal discountRate) {
        this.discountRate = discountRate;
    }

    /** Reads the field directly, as code inside the class does, to check that setters are updated too. */
    int limitField() {
        return limit;
    }
}

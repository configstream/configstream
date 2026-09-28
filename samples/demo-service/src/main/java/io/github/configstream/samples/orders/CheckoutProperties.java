package io.github.configstream.samples.orders;

import io.github.configstream.spring.LiveConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Live checkout texts. */
@ConfigurationProperties("checkout")
@LiveConfig
public class CheckoutProperties {

    /** Banner shown at the top of the checkout page. */
    private String banner = "Free shipping on orders over $50";

    public String getBanner() {
        return banner;
    }

    public void setBanner(String banner) {
        this.banner = banner;
    }
}

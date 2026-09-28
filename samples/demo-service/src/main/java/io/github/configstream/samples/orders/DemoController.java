package io.github.configstream.samples.orders;

import io.github.configstream.spring.ConfigChangedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shows live property values, read through ordinary @ConfigurationProperties classes, and logs every change. */
@RestController
class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final FeatureXProperties featureX;
    private final LimitsProperties limits;
    private final CheckoutProperties checkout;
    private final int port;

    DemoController(FeatureXProperties featureX, LimitsProperties limits, CheckoutProperties checkout,
            @Value("${server.port}") int port) {
        this.featureX = featureX;
        this.limits = limits;
        this.checkout = checkout;
        this.port = port;
    }

    @GetMapping("/demo")
    Map<String, Object> demo() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instancePort", port);
        body.put("feature.x.enabled", featureX.isEnabled());
        body.put("limits.max", limits.getMax());
        body.put("checkout.banner", checkout.getBanner());
        return body;
    }

    @EventListener
    void onConfigChanged(ConfigChangedEvent event) {
        log.info("Config changed: {} '{}' -> '{}'", event.key(), event.oldValue(), event.newValue());
    }
}

package io.github.configstream.samples.orders;

import io.github.configstream.samples.orders.config.Checkout;
import io.github.configstream.samples.orders.config.Feature;
import io.github.configstream.samples.orders.config.Limits;
import io.github.configstream.spring.ConfigChangedEvent;
import io.github.configstream.spring.ConfigService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shows live property values, and logs every change as it arrives. */
@RestController
class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final ConfigService config;
    private final int port;

    DemoController(ConfigService config, @Value("${server.port}") int port) {
        this.config = config;
        this.port = port;
    }

    @GetMapping("/demo")
    Map<String, Object> demo() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instancePort", port);
        body.put("feature.x.enabled", config.get(Feature.X_ENABLED));
        body.put("limits.max", config.get(Limits.MAX));
        body.put("checkout.banner", config.get(Checkout.BANNER));
        return body;
    }

    @EventListener
    void onConfigChanged(ConfigChangedEvent event) {
        log.info("Config changed: {} '{}' -> '{}'", event.key(), event.oldValue(), event.newValue());
    }
}

package io.github.configstream.compat.boot4.orders;

import io.github.configstream.api.ConfigStreamManifest;
import io.github.configstream.spring.ConfigChangedEvent;
import io.github.configstream.spring.ConfigService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A Spring Boot 4 service using configstream the way the README describes: generated constants and change events. */
@SpringBootApplication
@ConfigStreamManifest
public class OrdersApplication {

    @RestController
    static class DemoController {

        private final ConfigService config;
        final List<String> changes = new CopyOnWriteArrayList<>();

        DemoController(ConfigService config) {
            this.config = config;
        }

        @GetMapping("/demo")
        Map<String, Object> demo() {
            return Map.of("feature.x.enabled", config.get(Feature.X_ENABLED), "limits.max", config.get(Limits.MAX));
        }

        @GetMapping("/demo/changes")
        List<String> changes() {
            return changes;
        }

        @EventListener
        void onChange(ConfigChangedEvent event) {
            changes.add(event.key() + ": " + event.oldValue() + " -> " + event.newValue());
        }
    }
}

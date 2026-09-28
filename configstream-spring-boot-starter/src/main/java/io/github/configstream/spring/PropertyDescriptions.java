package io.github.configstream.spring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;

/**
 * Property descriptions from Spring Boot's configuration metadata ({@code META-INF/spring-configuration-metadata.json}),
 * which {@code spring-boot-configuration-processor} writes from the fields' Javadoc. Optional: without it, live
 * properties simply have no description in the admin app.
 */
final class PropertyDescriptions {

    static final String METADATA = "META-INF/spring-configuration-metadata.json";

    private static final Logger log = LoggerFactory.getLogger(PropertyDescriptions.class);

    private PropertyDescriptions() {
    }

    static Map<String, String> load(ClassLoader classLoader) {
        Map<String, String> descriptions = new HashMap<>();
        try {
            Enumeration<URL> files = classLoader.getResources(METADATA);
            while (files.hasMoreElements()) {
                URL file = files.nextElement();
                try (InputStream in = file.openStream()) {
                    Map<String, Object> metadata = JsonParserFactory.getJsonParser()
                            .parseMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (metadata.get("properties") instanceof List<?> properties) {
                        for (Object property : properties) {
                            if (property instanceof Map<?, ?> p && p.get("name") instanceof String name
                                    && p.get("description") instanceof String description) {
                                descriptions.putIfAbsent(name, description);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    log.debug("Skipping unreadable configuration metadata {}", file, e);
                }
            }
        } catch (IOException e) {
            log.debug("Could not look for configuration metadata", e);
        }
        return descriptions;
    }
}

package io.github.configstream.spring;

import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers the {@code /internal/config} endpoints (current values, update, delete, history), but only when
 * {@code configstream.internal.secret} is set: they are off unless explicitly configured, never open
 * by default.
 */
@AutoConfiguration(after = ConfigStreamAutoConfiguration.class)
@ConditionalOnProperty(prefix = "configstream", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "configstream.internal", name = "secret")
@ConditionalOnBean({ConfigService.class, LiveConfigRegistry.class, ConfigWriter.class, ConfigHistory.class})
@EnableConfigurationProperties(ConfigStreamProperties.class)
public class ConfigStreamEndpointAutoConfiguration {

    static final int MIN_SECRET_LENGTH = 16;

    @Bean
    InternalConfigController configStreamInternalConfigController(ConfigService config, LiveConfigRegistry registry,
            ConfigWriter writer, ConfigHistory history, ConfigStreamProperties properties) {
        String secret = properties.getInternal().getSecret();
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("configstream.internal.secret must be at least "
                    + MIN_SECRET_LENGTH + " characters; generate one with e.g. `openssl rand -hex 32`.");
        }
        return new InternalConfigController(config, registry, writer, history, secret);
    }
}

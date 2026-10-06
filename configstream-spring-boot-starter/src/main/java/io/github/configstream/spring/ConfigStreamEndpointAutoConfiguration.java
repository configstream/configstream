package io.github.configstream.spring;

import io.github.configstream.api.ConfigHistory;
import io.github.configstream.api.ConfigWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers the {@code /internal/config} endpoints (current values, update, delete, history), but only when
 * {@code configstream.internal.secret} or {@code configstream.internal.admin-principal} is set: they are off unless
 * explicitly configured, never open by default.
 */
@AutoConfiguration(after = ConfigStreamAutoConfiguration.class)
@ConditionalOnProperty(prefix = "configstream", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@Conditional(ConfigStreamEndpointAutoConfiguration.CallerConfigured.class)
@ConditionalOnBean({ConfigService.class, ConfigWriter.class, ConfigHistory.class})
@EnableConfigurationProperties(ConfigStreamProperties.class)
public class ConfigStreamEndpointAutoConfiguration {

    static final int MIN_SECRET_LENGTH = 16;

    @Bean
    InternalConfigController configStreamInternalConfigController(ConfigService config,
            ConfigWriter writer, ConfigHistory history, ConfigStreamProperties properties) {
        String secret = blankToNull(properties.getInternal().getSecret());
        if (secret != null && secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("configstream.internal.secret must be at least "
                    + MIN_SECRET_LENGTH + " characters; generate one with e.g. `openssl rand -hex 32`.");
        }
        String adminPrincipal = blankToNull(properties.getInternal().getAdminPrincipal());
        if (secret == null && adminPrincipal == null) {
            throw new IllegalStateException("configstream.internal.secret or configstream.internal.admin-principal "
                    + "must have a value.");
        }
        return new InternalConfigController(config, writer, history, secret, adminPrincipal);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Either way of authenticating the admin server is configured. */
    static class CallerConfigured extends AnyNestedCondition {

        CallerConfigured() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "configstream.internal", name = "secret")
        static class Secret {
        }

        @ConditionalOnProperty(prefix = "configstream.internal", name = "admin-principal")
        static class AdminPrincipal {
        }
    }
}

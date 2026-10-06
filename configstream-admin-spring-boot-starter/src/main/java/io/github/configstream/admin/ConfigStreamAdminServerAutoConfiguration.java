package io.github.configstream.admin;

import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.admin.registry.ConfigStreamAdminRegistryConfiguration;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.admin.web.ConfigStreamAdminWebConfiguration;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * The admin server: registration API ({@code /api/instances}), the dashboard, and the client it uses to
 * call services. Active only in servlet web apps annotated with {@link EnableConfigStreamAdminServer}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(ConfigStreamAdminServerMarkerConfiguration.Marker.class)
@EnableConfigurationProperties(ConfigStreamAdminProperties.class)
@Import({ConfigStreamAdminRegistryConfiguration.class, ConfigStreamAdminWebConfiguration.class})
public class ConfigStreamAdminServerAutoConfiguration {

    @Bean
    ServiceClient configStreamAdminServiceClient(ObjectProvider<RestClient.Builder> builder, InstanceRegistry registry,
            ConfigStreamAdminProperties properties, ListableBeanFactory beans) {
        // Boot's builder carries the app's message converters (e.g. Jackson with java.time support)
        RestClient.Builder http = builder.getIfAvailable(RestClient::builder);
        if (properties.usesTokens()) {
            ServiceTokens.apply(http, properties.getOauth2Client(), beans);
        }
        return new ServiceClient(http, registry, properties);
    }
}

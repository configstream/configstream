package io.github.configstream.spring;

import io.github.configstream.api.PropertyDeclaration;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Registers this instance with the admin server and keeps it alive with heartbeats. Only active
 * when {@code configstream.admin.url} is set; without it the service runs standalone.
 */
@AutoConfiguration(after = ConfigStreamAutoConfiguration.class)
@ConditionalOnProperty(prefix = "configstream", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "configstream.admin", name = "url")
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(ConfigStreamProperties.class)
public class ConfigStreamAdminAutoConfiguration {

    // Short timeouts: the admin app being slow must never hold up this service, including shutdown.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    AdminRegistration configStreamAdminRegistration(ConfigStreamProperties properties, Environment environment,
            ObjectProvider<LiveConfigRegistry> registry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient http = RestClient.builder()
                .baseUrl(properties.getAdmin().getUrl())
                .requestFactory(requestFactory)
                .build();
        return new AdminRegistration(http, () -> instanceInfo(properties, environment, registry.getIfAvailable()),
                properties.getAdmin().getHeartbeatInterval());
    }

    private static AdminRegistration.InstanceInfo instanceInfo(ConfigStreamProperties properties, Environment env,
            LiveConfigRegistry registry) {
        ConfigStreamProperties.Instance instance = properties.getInstance();
        String id = instance.getId() != null ? instance.getId() : UUID.randomUUID().toString();
        String host = instance.getHost() != null ? instance.getHost() : localAddress();
        // Set by Spring Boot once the embedded web server has started
        Integer port = instance.getPort() != null ? instance.getPort() : env.getProperty("local.server.port", Integer.class);
        String serviceName = env.getProperty("spring.application.name", "application");
        List<PropertyDeclaration> declarations = registry == null ? List.of() : registry.declarations();
        List<AdminRegistration.DeclaredProperty> declared = declarations.stream()
                .map(p -> new AdminRegistration.DeclaredProperty(p.key(), p.type().typeName(), p.description()))
                .toList();
        return new AdminRegistration.InstanceInfo(serviceName, id, host, port, properties.getTeam(), declared);
    }

    private static String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}

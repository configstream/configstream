package io.github.configstream.admin;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

/**
 * Adds OAuth2 tokens (client credentials) to calls to services, so they can tell the admin server is calling.
 * Kept apart so Spring Security's classes are only loaded when {@code configstream.admin-server.oauth2-client} is set.
 */
final class ServiceTokens {

    private static final String INTERCEPTOR =
            "org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor";

    private ServiceTokens() {
    }

    /** @throws IllegalStateException if Spring Security's OAuth2 client or the registration is missing */
    static void apply(RestClient.Builder http, String registrationId, ListableBeanFactory beans) {
        if (!ClassUtils.isPresent(INTERCEPTOR, ServiceTokens.class.getClassLoader())) {
            throw new IllegalStateException("configstream.admin-server.oauth2-client is set, but Spring Security's "
                    + "OAuth2 client isn't on the classpath. Add spring-boot-starter-oauth2-client.");
        }
        Interceptor.apply(http, registrationId, beans);
    }

    /** Separate class: only loaded once the OAuth2 client is known to be present. */
    private static final class Interceptor {

        static void apply(RestClient.Builder http, String registrationId, ListableBeanFactory beans) {
            ClientRegistrationRepository registrations = beans.getBeanProvider(ClientRegistrationRepository.class)
                    .getIfAvailable();
            if (registrations == null || registrations.findByRegistrationId(registrationId) == null) {
                throw new IllegalStateException("configstream.admin-server.oauth2-client is '" + registrationId
                        + "', but there is no spring.security.oauth2.client.registration." + registrationId
                        + " (with authorization-grant-type: client_credentials).");
            }
            // The admin server's own token, not the signed-in person's: one token, cached, for every service
            var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations,
                    new InMemoryOAuth2AuthorizedClientService(registrations));
            manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
            OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
            interceptor.setClientRegistrationIdResolver(request -> registrationId);
            http.requestInterceptor(interceptor);
        }
    }
}

package io.github.configstream.samples.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * An example of adding login to the admin server: configstream leaves login to the application hosting it. A real
 * deployment would sign people in through the company's identity provider (e.g. {@code oauth2Login()} with Okta or
 * Azure AD) instead of these two local test users.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        // Services register here, not people: configstream checks them itself (a token
                        // identifying the service, or local mode on this machine)
                        .requestMatchers("/api/instances/**").permitAll()
                        .requestMatchers("/configstream-admin/**").permitAll()   // the dashboard's CSS and scripts
                        .anyRequest().authenticated())
                // Services aren't browsers, so the registration API has no CSRF token to send
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/instances/**"))
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Local test users only: never use these, or plain-text passwords, anywhere real. Their roles are their login
     * groups: alice is in team-a (which owns the demo's orders service), bob in team-b, and admin in config-admins,
     * which application.yml makes an admin group that sees every service.
     */
    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password("{noop}alice-local").roles("team-a").build(),
                User.withUsername("bob").password("{noop}bob-local").roles("team-b").build(),
                User.withUsername("admin").password("{noop}admin-local").roles("config-admins").build());
    }
}

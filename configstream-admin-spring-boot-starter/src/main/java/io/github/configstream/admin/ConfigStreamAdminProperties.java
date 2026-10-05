package io.github.configstream.admin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code configstream.admin-server.*}. */
@ConfigurationProperties(prefix = "configstream.admin-server")
public class ConfigStreamAdminProperties {

    /**
     * How long an instance counts as up after its last heartbeat. Instances send one every 15s by
     * default, so the default tolerates two missed heartbeats.
     */
    private Duration leaseDuration = Duration.ofSeconds(45);

    /** How long after its last heartbeat a down instance is removed from the registry. */
    private Duration evictAfter = Duration.ofMinutes(10);

    /** Timeout for each call to a service's internal endpoints. */
    private Duration requestTimeout = Duration.ofSeconds(5);

    /**
     * Shared secret per service name, matching that service's {@code configstream.internal.secret}.
     * Needed to read (and later change) its config.
     */
    private Map<String, String> serviceSecrets = new HashMap<>();

    /** Secret used for services not listed in {@code service-secrets}. */
    private String defaultServiceSecret;

    /**
     * Accept registrations from callers that aren't authenticated, from any address. Off by default: then services
     * register with a token identifying them (authenticated by this application, e.g. as an OAuth2 resource server),
     * and without a token only from this machine. Turn on only on networks where every caller is trusted.
     */
    private boolean allowUnauthenticatedRegistration;

    private final Dashboard dashboard = new Dashboard();

    public Dashboard getDashboard() {
        return dashboard;
    }

    public boolean isAllowUnauthenticatedRegistration() {
        return allowUnauthenticatedRegistration;
    }

    public void setAllowUnauthenticatedRegistration(boolean allowUnauthenticatedRegistration) {
        this.allowUnauthenticatedRegistration = allowUnauthenticatedRegistration;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getEvictAfter() {
        return evictAfter;
    }

    public void setEvictAfter(Duration evictAfter) {
        this.evictAfter = evictAfter;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Map<String, String> getServiceSecrets() {
        return serviceSecrets;
    }

    public void setServiceSecrets(Map<String, String> serviceSecrets) {
        this.serviceSecrets = serviceSecrets;
    }

    public String getDefaultServiceSecret() {
        return defaultServiceSecret;
    }

    public void setDefaultServiceSecret(String defaultServiceSecret) {
        this.defaultServiceSecret = defaultServiceSecret;
    }

    /** The secret for {@code serviceName}, or {@code null} if none is configured. */
    public String secretFor(String serviceName) {
        return serviceSecrets.getOrDefault(serviceName, defaultServiceSecret);
    }

    public static class Dashboard {

        /**
         * Path the dashboard is served under, e.g. {@code /admin} when the host app has pages of its
         * own. The registration API is always at {@code /api/instances}.
         */
        private String path = "/";

        /**
         * Where the header's "Sign out" link goes, e.g. {@code /logout} with Spring Security's default setup. No link
         * unless set. The signed-in user's name is shown whenever the host application has a login.
         */
        private String logoutPath;

        public String getLogoutPath() {
            return logoutPath;
        }

        public void setLogoutPath(String logoutPath) {
            this.logoutPath = logoutPath;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        /** The path as a link prefix: {@code ""} for the root, otherwise {@code "/x"} with no trailing slash. */
        public String basePath() {
            String p = path == null ? "" : path.trim();
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p.isEmpty() || p.startsWith("/") ? p : "/" + p;
        }
    }
}

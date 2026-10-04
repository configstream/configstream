package io.github.configstream.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code configstream.*} in {@code application.yml}. */
@ConfigurationProperties(prefix = "configstream")
public class ConfigStreamProperties {

    /** Whether to start configstream at all. */
    private boolean enabled = true;

    /** Team that owns this service, e.g. {@code team-a}. The admin app uses it to decide who can see it. */
    private String team;

    /**
     * The manifest declaring this application's properties. On startup, each declared property missing from the
     * store is created with its initial value; existing values are never changed.
     */
    private String manifest = "classpath:configstream.yml";

    /**
     * The environment this instance runs in, e.g. {@code prod}. If set, initial values from
     * {@code configstream-<environment>.yml} (next to the manifest) replace the manifest's, for properties created
     * in this environment. Set it explicitly, e.g. in {@code application-prod.yml}.
     */
    private String environment;

    private final Mongo mongo = new Mongo();

    private final Internal internal = new Internal();

    private final Admin admin = new Admin();

    private final Instance instance = new Instance();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getManifest() {
        return manifest;
    }

    public void setManifest(String manifest) {
        this.manifest = manifest;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Mongo getMongo() {
        return mongo;
    }

    public Internal getInternal() {
        return internal;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Instance getInstance() {
        return instance;
    }

    public static class Internal {

        /**
         * Shared secret the admin app must send in the {@code X-ConfigStream-Secret} header to call
         * {@code POST /internal/config/update}. The endpoint does not exist unless this is set.
         * At least 16 characters.
         */
        private String secret;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public static class Admin {

        /** Base URL of the admin server, e.g. {@code https://configstream-admin.internal}. Registration is off unless set. */
        private String url;

        /** How often to send a heartbeat to the admin app. */
        private Duration heartbeatInterval = Duration.ofSeconds(15);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }
    }

    /** How this instance describes itself to the admin app. Defaults suit most deployments. */
    public static class Instance {

        /** Unique ID for this running instance. Defaults to a random UUID per start. */
        private String id;

        /** Host the admin app should call. Defaults to this machine's IP address. */
        private String host;

        /** Port the admin app should call. Defaults to the port the embedded web server started on. */
        private Integer port;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }
    }

    public static class Mongo {

        /**
         * Connection string for the config store. Must point at a replica set, since change streams
         * need one, e.g. {@code mongodb://host:27017/mydb?replicaSet=rs0}.
         */
        private String uri;

        /** Database holding the config collection. Defaults to the database named in the URI. */
        private String database;

        /**
         * Collection holding one document per property. Defaults to {@code <spring.application.name>_config}, so every
         * service gets its own collection even when services share a database.
         */
        private String configCollection;

        /**
         * Append-only collection recording every change made through configstream. Defaults to the config collection's
         * name followed by {@code _history}.
         */
        private String historyCollection;

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getConfigCollection() {
            return configCollection;
        }

        public void setConfigCollection(String configCollection) {
            this.configCollection = configCollection;
        }

        public String getHistoryCollection() {
            return historyCollection;
        }

        public void setHistoryCollection(String historyCollection) {
            this.historyCollection = historyCollection;
        }
    }
}

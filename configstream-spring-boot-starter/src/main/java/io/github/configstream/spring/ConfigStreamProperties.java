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

        /** Collection holding one document per config key. */
        private String collection = "config";

        /** Append-only collection recording every change made through configstream. Defaults to {@code <collection>_history}. */
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

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public String getHistoryCollection() {
            return historyCollection;
        }

        public void setHistoryCollection(String historyCollection) {
            this.historyCollection = historyCollection;
        }
    }
}

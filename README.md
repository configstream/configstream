# configstream

> Push-based, restart-free feature flags and configuration for Spring Boot — using the database you already run.

**Status:** 🚧 Early development (pre-0.1.0). Not ready for production use.

## What it does

`configstream` keeps feature flags and non-secret properties in a database collection/table, loads them into an
in-memory cache at startup, and updates that cache **instantly across every running instance** when a value changes —
using the database's native change notifications (MongoDB Change Streams; PostgreSQL `LISTEN/NOTIFY` planned).
No restarts, no polling, no message broker, no new infrastructure.

## Who it's for

Java / Spring Boot teams already running **MongoDB** (PostgreSQL support planned) who want fast, self-hosted flag and
config propagation without operating a separate config server.

**Not for:** secrets/credentials (use Vault or your cloud secrets manager), or teams needing percentage rollouts /
A/B experimentation (see Unleash or LaunchDarkly).

## Modules

| Module | Purpose |
|---|---|
| `configstream-api` | Storage-agnostic contracts + in-memory cache |
| `configstream-mongo` | MongoDB Change Streams backend |
| `configstream-spring-boot-starter` | **Client**: add to each service. `ConfigService` bean, `ConfigChangedEvent`, internal endpoints, registration with the admin server |
| `configstream-admin-spring-boot-starter` | **Server**: add to one Spring Boot app, plus `@EnableConfigStreamAdminServer`. Service registry + dashboard |

Like Eureka, there is a client starter and a server starter. Every service adds the client; one app, deployed once,
adds the server. Config changes do not travel through the admin server: it asks a service to write the change, and
MongoDB change streams push it to every instance.

## Usage (Spring Boot)

Add `configstream-spring-boot-starter` to your dependencies, then point it at a MongoDB replica set:

```yaml
configstream:
  mongo:
    uri: mongodb://localhost:27017/mydb?replicaSet=rs0
    collection: config        # optional, defaults to "config"
```

Store one document per key: `{ "_id": "feature.x.enabled", "value": true }`.

```java
@Service
class Checkout {
    private final ConfigService config;

    Checkout(ConfigService config) { this.config = config; }

    void run() {
        if (config.getBoolean("feature.x.enabled", false)) { /* ... */ }
    }

    @EventListener
    void onChange(ConfigChangedEvent e) {
        // e.key(), e.oldValue(), e.newValue(), fired within ~1s of the change in Mongo
    }
}
```

configstream uses its own connection and does not replace your application's `MongoClient` bean.

### Connecting to the admin server (optional)

```yaml
spring.application.name: orders
configstream:
  team: team-a
  internal:
    secret: ${CONFIGSTREAM_SECRET}      # at least 16 chars; e.g. `openssl rand -hex 32`
  admin:
    url: https://configstream-admin.internal
    heartbeat-interval: 15s            # default
```

- **`internal.secret`** enables the internal endpoints, through which the admin app changes config using *this
  service's* database credentials. Callers must send the secret in the `X-ConfigStream-Secret` header. Without a
  secret the endpoints do not exist. Serve them over HTTPS only.
  - `POST /internal/config/update` with `{"key": "...", "value": "...", "changedBy": "alice", "comment": "optional"}`
    returns the recorded history entry (200), or 204 if the value was already set.
  - `POST /internal/config/delete` with `{"key": "...", "changedBy": "alice", "comment": "optional"}` soft-deletes the
    key and returns the recorded history entry (200), or 204 if the key had no value.
  - `GET /internal/config/history?key=...&limit=50` returns that key's changes, newest first.
  - `GET /internal/config` returns every value as this instance currently sees it.
- **`admin.url`** makes the instance register with the admin app on startup, send heartbeats, and deregister on
  shutdown. If the admin app is down or unreachable the service still starts and keeps retrying in the background.
- **History:** every change made through configstream is appended to `<collection>_history` (key, version, old and
  new value, who, when, comment) in the same transaction as the change itself. To **roll back**, write the old value
  again, e.g. with `"comment": "Reverted to v3"`; history is never rewritten. Changes made directly in the database
  still reach every cache but are not recorded.
- **Deletes are soft:** the document keeps its `_id` and `version` but loses its `value`, so the key leaves every cache
  while its history stays. Writing the key again restores it and continues its version numbering.
- If your app uses **Spring Security**, permit `/internal/config/**` and exclude it from CSRF protection; the shared
  secret is what authenticates these calls.

## Admin server

The admin server is the app services register with (`configstream.admin.url`). It shows every registered service, its
active instances (those sending heartbeats), its current config and each key's change history, and lets you add, edit,
delete and restore entries. Deploy one per environment.

Turn any Spring Boot web app into the admin server, the way `@EnableEurekaServer` does:

```xml
<dependency>
    <groupId>io.github.configstream</groupId>
    <artifactId>configstream-admin-spring-boot-starter</artifactId>
</dependency>
```

```java
@SpringBootApplication
@EnableConfigStreamAdminServer
public class ConfigStreamAdminApp {
    public static void main(String[] args) {
        SpringApplication.run(ConfigStreamAdminApp.class, args);
    }
}
```

The dependency alone activates nothing; the annotation does. There is no separate admin jar to download: the admin
server is always your own Spring Boot app, deployed and configured like any other.

Every change takes two steps: an edit is reviewed against the current value before it is applied, and a delete is
confirmed on its own page. The admin app never touches a service's database. It sends the change to any healthy
instance of the service, which writes it with its own credentials, and every instance picks it up within about a
second. Failures (no instance reachable, secret rejected, request invalid) are shown on the page. If an instance
received the change but did not confirm it (a timeout or server error), the admin app does not retry on another
instance. It tells you to check the key's history first, because the change may already have been applied.

```yaml
configstream:
  admin-server:
    service-secrets:
      orders: ${ORDERS_CONFIG_SECRET}   # must match that service's configstream.internal.secret
    # default-service-secret: ...       # for services not listed above
    lease-duration: 45s                 # shown as down after this long without a heartbeat
    evict-after: 10m                    # removed from the registry after this long
    dashboard:
      path: /                           # e.g. /admin if the app has pages of its own

server.servlet.session.tracking-modes: cookie   # recommended: keeps session ids out of URLs
```

The registration API is always at `/api/instances`, whatever the dashboard path. The dashboard's templates and CSS
live under `configstream-admin/`, so they do not clash with the host app's own.

> **No login yet, and it can change live config.** Anyone who can reach the admin app can edit any registered
> service, and "changed by" is whatever they type. Login, team-based access control and CSRF protection arrive in
> Phase 6. Until then, run it only on a trusted local or dev network.

## Local development

Requirements: JDK 17+, Maven 3.9+, Docker.

```bash
docker compose up -d        # local single-node Mongo replica set
mvn verify                  # build + unit + integration tests
```

`samples/` holds two runnable apps for trying it by hand (not published): `demo-admin`, an admin server on port 8090,
and `demo-service`, an `orders` service on port 8081 whose `GET /demo` shows live values. To use a MongoDB other than
`localhost:27017` (e.g. Atlas), set the `CONFIGSTREAM_MONGO_URI` environment variable.

```bash
java -jar samples/demo-admin/target/demo-admin-0.1.0-SNAPSHOT.jar
java -jar samples/demo-service/target/demo-service-0.1.0-SNAPSHOT.jar                     # :8081
java -jar samples/demo-service/target/demo-service-0.1.0-SNAPSHOT.jar --server.port=8082   # second instance
```

## License

Apache License 2.0

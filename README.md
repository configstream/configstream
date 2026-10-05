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
| `configstream-api` | Storage-agnostic contracts, property types, the manifest, in-memory cache |
| `configstream-processor` | **Build time**: generates typed property constants from `configstream.yml` and checks it |
| `configstream-mongo` | MongoDB Change Streams backend |
| `configstream-spring-boot-starter` | **Client**: add to each service. `ConfigService` bean, `ConfigChangedEvent`, internal endpoints, registration with the admin server |
| `configstream-admin-spring-boot-starter` | **Server**: add to one Spring Boot app, plus `@EnableConfigStreamAdminServer`. Service registry + dashboard |

Like Eureka, there is a client starter and a server starter. Every service adds the client; one app, deployed once,
adds the server. Config changes do not travel through the admin server: it asks a service to write the change, and
MongoDB change streams push it to every instance.

## Usage (Spring Boot)

configstream is for Spring Boot services that already use MongoDB. Add `configstream-spring-boot-starter` to your
dependencies; it uses your application's own MongoDB connection (its `MongoClient`, e.g. from
`spring-boot-starter-data-mongodb`) and opens none of its own. The MongoDB must be a replica set (Atlas always is),
because changes arrive through change streams.

```yaml
spring:
  application:
    name: orders              # configstream's collections: orders_config and orders_config_history
  data:
    mongodb:
      uri: mongodb://localhost:27017/orders?replicaSet=rs0&socketTimeoutMS=30000

configstream:
  mongo:
    # database: orders                       # optional, defaults to your application's database
    # config-collection: orders_settings     # optional, defaults to <spring.application.name>_config
    # history-collection: orders_audit       # optional, defaults to <config-collection>_history
```

Each service uses its own collections, named after the service, so several services can safely share one database.
Keep them in the database your service already uses: the load then spreads over your teams' clusters. Each instance
adds one change stream and borrows one connection from your pool for it.

Both collections are created automatically on the first start; the service's database user needs read and write
access (MongoDB's `readWrite` role).

### Declare properties in `configstream.yml`

Every property is declared in `src/main/resources/configstream.yml`, with a type (`boolean`, `int`, `decimal` or
`string`) and an initial value:

```yaml
properties:
  - key: feature.funds.enabled
    type: boolean
    initialValue: false
    description: Show the funds page   # optional
  - key: feature.funds.limit
    type: int
    initialValue: 3
```

- **The manifest is the only way to add properties.** On startup, each declared property that is missing from
  MongoDB is created with its initial value, so a first deploy to a new environment finds everything it needs.
  Existing values are never changed: the initial value only matters the first time.
- **The stored value always wins.** The initial value is used only while a property is missing from MongoDB or its
  stored value doesn't fit its type.
- **Types never change.** Starting a version that declares an existing key with a different type fails with an
  explanation. To change a type, declare the property under a new key.
- **Per-environment initial values:** `configstream-prod.yml` next to the manifest can give declared properties a
  different initial value in prod (`properties: { feature.funds.limit: 10 }`). It is used when
  `configstream.environment=prod`, e.g. set in `application-prod.yml`. Environment files can't declare new keys.

### Read them through generated constants

Typed constants are generated from the manifest at compile time, so a misspelled key or a value read as the wrong
type doesn't compile. Add the annotation processor to the build:

```xml
<!-- Maven: maven-compiler-plugin -->
<configuration>
    <annotationProcessorPaths>
        <path>
            <groupId>io.github.configstream</groupId>
            <artifactId>configstream-processor</artifactId>
            <version>${configstream.version}</version>
        </path>
    </annotationProcessorPaths>
</configuration>
```

```kotlin
// Gradle
annotationProcessor("io.github.configstream:configstream-processor:$configstreamVersion")
```

and put `@ConfigStreamManifest` on any one class, typically the application class:

```java
@SpringBootApplication
@ConfigStreamManifest   // packageName = "..." to generate elsewhere; default: this class's package
public class OrdersApplication { ... }
```

Each key's first part becomes a class and the rest a constant: `feature.funds.limit` gives `Feature.FUNDS_LIMIT`, a
`Property<Integer>`. The build fails, naming the file and property, if the manifest or any `configstream-<env>.yml`
next to it is invalid, or if two keys would generate the same name. After editing only `configstream.yml`, rebuild
(e.g. `mvn clean compile`, or Rebuild in the IDE): incremental compiles only notice changed `.java` files.

```java
@Service
class Checkout {
    private final ConfigService config;

    Checkout(ConfigService config) { this.config = config; }

    void run() {
        int limit = config.get(Feature.FUNDS_LIMIT);
    }

    @EventListener
    void onChange(ConfigChangedEvent e) {
        // e.isFor(Feature.FUNDS_LIMIT), e.oldValue(), e.newValue(); fired within ~1s of the change in Mongo
    }
}
```

Stored as one document per property: `{ "_id": "feature.funds.limit", "type": "int", "value": 3, "version": 1 }`.
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
  - `POST /internal/config/update` with `{"key": "...", "value": "...", "type": "int", "changedBy": "alice",
    "comment": "optional"}` (`type` optional) sets an existing property and returns the recorded history entry (200),
    or 204 if the value was already set. It never creates a property (404), and rejects a value that doesn't fit the
    property's type or a different `type` (400). Rejections carry `{"error": "..."}`, a message for the person.
  - `POST /internal/config/delete` with `{"key": "...", "changedBy": "alice", "comment": "optional"}` deletes an
    orphan (a property this instance doesn't declare) and returns the recorded history entry (200), or 204 if it
    doesn't exist. Properties this instance declares can't be deleted (409).
  - `GET /internal/config/history?key=...&limit=50` returns that property's changes, newest first.
  - `GET /internal/config` returns every property as this instance currently sees it:
    `{"feature.funds.limit": {"type": "int", "value": "3"}}`.
- **`admin.url`** makes the instance register with the admin app on startup, send heartbeats, and deregister on
  shutdown. If the admin app is down or unreachable the service still starts and keeps retrying in the background.
- **History:** every change made through configstream is appended to `<collection>_history` (key, version, old and
  new value, who, when, comment) in the same transaction as the change itself, starting with v1 "Created from
  configstream.yml". To **roll back**, write the old value again, e.g. with `"comment": "Reverted to v3"`; history is
  never rewritten. Changes made directly in the database still reach every cache but are not recorded.
- **Deletes** remove the property but keep its history. If a version of the service that declares it starts again
  (for example after a rollback), the property is created again with that version's initial value and its versions
  continue; restore its old value from the history.
- **Blue-green and rolling deploys:** give every version the same `spring.application.name`, and let every instance
  register with the admin server, so properties still used by the old version aren't shown as orphans.
- If your app uses **Spring Security**, permit `/internal/config/**` and exclude it from CSRF protection; the shared
  secret is what authenticates these calls.

### When the connection to MongoDB fails

Each instance keeps its last known values while cut off, then reconnects and catches up on every change it missed
(or reloads everything if it was away too long).

- **Dead connections are noticed if your client has a socket timeout.** Set `socketTimeoutMS` (e.g. `30000`) on your
  MongoDB URI: a connection dropped silently by a firewall then fails and is reopened. Without it, the MongoDB driver
  waits indefinitely, and the instance may miss changes for a long time. configstream logs a warning at startup when
  `spring.data.mongodb.uri` has no `socketTimeoutMS`.
- **The watcher can't die silently.** If it stops on an unexpected error, it restarts and reloads all values.
- **Health check.** With Spring Boot Actuator, `/actuator/health` has a `configstream` entry: UP while connected, still
  UP while reconnecting after a short interruption, and DOWN once cut off longer than `configstream.health.down-after`
  (default `2m`), with the last error. Use it for alerts or readiness, not for a liveness check that restarts the app:
  if MongoDB is down, restarting every instance doesn't help. Turn it off with
  `management.health.configstream.enabled=false`.

## Admin server

The admin server is the app services register with (`configstream.admin.url`). It shows every registered service, its
active instances (those sending heartbeats), each property with its type, value and description, and each property's
change history. Deploy one per environment.

It **edits values only**. Properties are added, renamed and retyped in each service's `configstream.yml`, so there is
no Add button, and inputs match the type (true/false buttons for a boolean, a number field for an int). Instances tell
the admin server which properties their manifest declares when they register; a stored property that no active
instance declares is marked **Orphan**, and only orphans can be deleted.

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

Every change takes two steps: an edit is checked against the property's type and reviewed against the current value
before it is applied, and a delete is confirmed on its own page, which warns that rolling back to a version declaring
the property creates it again with that version's initial value. The admin app never touches a service's database.
It sends the change to any healthy
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

Integration tests start MongoDB in Docker. Without Docker, point them at any replica set, such as a free Atlas cluster:
set `CONFIGSTREAM_TEST_MONGO_URI` to its connection string, and each run uses its own `cstest_*` database, dropped
afterwards.

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

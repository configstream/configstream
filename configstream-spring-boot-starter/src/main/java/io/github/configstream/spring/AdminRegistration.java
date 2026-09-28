package io.github.configstream.spring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Registers this instance with the admin app, then sends a heartbeat every
 * {@code configstream.admin.heartbeat-interval}. The admin app treats missed heartbeats as the
 * instance being down; a clean shutdown also deregisters explicitly.
 *
 * <p>The admin app is optional: every call runs on a background thread, and failures are logged
 * and retried on the next tick, never thrown into the application. If the admin app answers a
 * heartbeat with 404 (it restarted and forgot this instance), the instance registers again.
 *
 * <p>Admin API contract:
 * <ul>
 *   <li>{@code POST /api/instances} with an {@link InstanceInfo} body: register</li>
 *   <li>{@code PUT /api/instances/{instanceId}/heartbeat}: heartbeat</li>
 *   <li>{@code DELETE /api/instances/{instanceId}}: deregister</li>
 * </ul>
 */
class AdminRegistration implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminRegistration.class);

    private final RestClient http;
    private final Supplier<InstanceInfo> instanceInfo;
    private final Duration heartbeatInterval;

    private ScheduledExecutorService executor;
    private InstanceInfo instance;
    private volatile boolean registered;
    private boolean failing; // only touched by the executor thread

    /**
     * @param instanceInfo resolved at {@link #start()}, once the web server has picked its port
     */
    AdminRegistration(RestClient http, Supplier<InstanceInfo> instanceInfo, Duration heartbeatInterval) {
        this.http = http;
        this.instanceInfo = instanceInfo;
        this.heartbeatInterval = heartbeatInterval;
    }

    @Override
    public synchronized void start() {
        instance = instanceInfo.get();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configstream-admin");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, 0, heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
        if (registered) {
            try {
                http.delete().uri("/api/instances/{id}", instance.instanceId()).retrieve().toBodilessEntity();
                log.info("Deregistered from the admin server");
            } catch (RuntimeException e) {
                log.debug("Could not deregister from the admin server; it will expire the lease", e);
            }
            registered = false;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    /** Start after everything else (so the port is known) and stop first (deregister before shutdown). */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }

    boolean isRegistered() {
        return registered;
    }

    private void tick() {
        try {
            if (registered) {
                heartbeat();
            } else {
                register();
            }
            if (failing) {
                log.info("Admin server reachable again");
                failing = false;
            }
        } catch (RuntimeException e) {
            // Warn once per outage rather than every tick
            if (!failing) {
                log.warn("Admin server call failed; will keep retrying every {} s. Cause: {}",
                        heartbeatInterval.toSeconds(), e.toString());
                failing = true;
            } else {
                log.debug("Admin server call failed again", e);
            }
        }
    }

    private void register() {
        http.post().uri("/api/instances").body(instance).retrieve().toBodilessEntity();
        registered = true;
        log.info("Registered with the admin server as {}/{}", instance.serviceName(), instance.instanceId());
    }

    private void heartbeat() {
        try {
            http.put().uri("/api/instances/{id}/heartbeat", instance.instanceId()).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
            log.info("Admin server no longer knows this instance; registering again");
            registered = false;
            register();
        }
    }

    /**
     * What this instance tells the admin app about itself. {@code port} and {@code team} may be null.
     * {@code properties} are the live ones its {@link LiveConfig} classes declare, so the admin app can tell which stored properties are
     * still in use (and which are orphans).
     */
    record InstanceInfo(String serviceName, String instanceId, String host, Integer port, String team,
            List<DeclaredProperty> properties) {
    }

    /** A live property this instance declares, as sent to the admin app. {@code description} may be null. */
    record DeclaredProperty(String key, String type, String description) {
    }
}

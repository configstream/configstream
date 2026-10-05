package io.github.configstream.admin.registry;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API configstream instances call to register, heartbeat and deregister. Each call may only act for the service
 * the caller is authenticated as (see {@link RegistrationGuard}); rejected calls get 401 or 403 with
 * {@code {"error": "..."}}.
 */
@RestController
@RequestMapping("/api/instances")
class RegistrationController {

    private final InstanceRegistry registry;
    private final RegistrationGuard guard;

    RegistrationController(InstanceRegistry registry, RegistrationGuard guard) {
        this.registry = registry;
        this.guard = guard;
    }

    @PostMapping
    ResponseEntity<?> register(@RequestBody(required = false) InstanceRegistration registration,
            HttpServletRequest request) {
        if (registration == null || !registration.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        RegistrationGuard.Refusal refusal = guard.check(request, registration.serviceName());
        if (refusal != null) {
            return error(refusal.status(), refusal.message());
        }
        Optional<String> owner = registry.serviceNameOf(registration.instanceId());
        if (owner.isPresent() && !owner.get().equals(registration.serviceName())) {
            // Otherwise one service could replace another's instance
            return error(HttpStatus.CONFLICT, "Instance ID '" + registration.instanceId()
                    + "' is already registered for another service.");
        }
        registry.register(registration);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 404 tells the instance this registry doesn't know it (e.g. after a restart), so it re-registers. */
    @PutMapping("/{instanceId}/heartbeat")
    ResponseEntity<?> heartbeat(@PathVariable String instanceId, HttpServletRequest request) {
        Optional<String> service = registry.serviceNameOf(instanceId);
        if (service.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        RegistrationGuard.Refusal refusal = guard.check(request, service.get());
        if (refusal != null) {
            return error(refusal.status(), refusal.message());
        }
        return registry.heartbeat(instanceId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{instanceId}")
    ResponseEntity<?> deregister(@PathVariable String instanceId, HttpServletRequest request) {
        Optional<String> service = registry.serviceNameOf(instanceId);
        if (service.isPresent()) {
            RegistrationGuard.Refusal refusal = guard.check(request, service.get());
            if (refusal != null) {
                return error(refusal.status(), refusal.message());
            }
            registry.deregister(instanceId);
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }

    record ErrorResponse(String error) {
    }
}

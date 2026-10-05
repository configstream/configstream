package io.github.configstream.admin.registry;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Principal;
import org.springframework.http.HttpStatus;

/**
 * Decides who may register, heartbeat and deregister instances of a service.
 *
 * <p>The host application authenticates callers, normally by validating OAuth2 tokens from the company's identity
 * provider (Spring Security's resource server). This guard then checks the caller's identity (its principal name) is
 * the service it acts for: a token for {@code orders} can't register {@code payments}. Use
 * {@code spring.security.oauth2.resourceserver.jwt.principal-claim-name} if the service name is in a claim other than
 * {@code sub}.
 *
 * <p>Without authentication, only callers on the same machine are accepted ("local mode", for trying configstream on
 * a laptop), unless {@code configstream.admin-server.allow-unauthenticated-registration} is set, for networks where
 * every caller is trusted.
 */
class RegistrationGuard {

    private final boolean allowUnauthenticated;

    RegistrationGuard(boolean allowUnauthenticated) {
        this.allowUnauthenticated = allowUnauthenticated;
    }

    /** @return why the request is refused, or {@code null} if it may act for {@code serviceName} */
    Refusal check(HttpServletRequest request, String serviceName) {
        Principal caller = request.getUserPrincipal();
        if (caller != null) {
            if (serviceName.equals(caller.getName())) {
                return null;
            }
            return new Refusal(HttpStatus.FORBIDDEN, "The caller is authenticated as '" + caller.getName()
                    + "', so it can't act for service '" + serviceName + "'. Each service needs a token that "
                    + "identifies it by its spring.application.name.");
        }
        if (allowUnauthenticated || isLocal(request.getRemoteAddr())) {
            return null;
        }
        return new Refusal(HttpStatus.UNAUTHORIZED, "Registering with this admin server needs a token identifying the "
                + "service (set configstream.admin.oauth2-client on the service, and protect /api/instances with "
                + "OAuth2 on the admin server). Without one, only services on the admin server's own machine can "
                + "register.");
    }

    private static boolean isLocal(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    record Refusal(HttpStatus status, String message) {
    }
}

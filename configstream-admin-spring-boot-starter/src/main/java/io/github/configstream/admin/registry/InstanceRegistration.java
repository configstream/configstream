package io.github.configstream.admin.registry;

import java.util.List;

/**
 * What a configstream instance sends when it registers ({@code POST /api/instances}). Matches the
 * payload built by configstream's {@code AdminRegistration}.
 *
 * @param port       may be {@code null} if the instance runs no web server; the admin app then cannot
 *                   call it
 * @param team       may be {@code null}
 * @param properties the live properties it declares; {@code null} from instances too old to send them, in which
 *                   case no property of the service is treated as an orphan
 */
public record InstanceRegistration(String serviceName, String instanceId, String host, Integer port, String team,
        List<DeclaredProperty> properties) {

    boolean isValid() {
        return notBlank(serviceName) && notBlank(instanceId) && notBlank(host);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

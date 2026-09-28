package io.github.configstream.admin.web;

import io.github.configstream.admin.client.ServiceCallException;
import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.admin.registry.ServiceSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** Read side of the UI: services, their instances, current config and per-key history. */
@Controller
class DashboardController {

    static final int HISTORY_LIMIT = 100;

    private final InstanceRegistry registry;
    private final ServiceClient client;

    DashboardController(InstanceRegistry registry, ServiceClient client) {
        this.registry = registry;
        this.client = client;
    }

    @GetMapping("/")
    String dashboard(Model model) {
        model.addAttribute("services", registry.services());
        return "configstream-admin/dashboard";
    }

    @GetMapping("/services/{serviceName}")
    String service(@PathVariable String serviceName, Model model) {
        ServiceSummary service = findService(serviceName);
        model.addAttribute("service", service);
        try {
            model.addAttribute("config", PropertyRow.of(client.currentConfig(serviceName), service));
        } catch (ServiceCallException e) {
            model.addAttribute("configError", e.getMessage());
        }
        return "configstream-admin/service";
    }

    @GetMapping("/services/{serviceName}/history")
    String history(@PathVariable String serviceName, @RequestParam String key, Model model) {
        model.addAttribute("service", findService(serviceName));
        model.addAttribute("key", key);
        try {
            model.addAttribute("entries", client.history(serviceName, key, HISTORY_LIMIT));
        } catch (ServiceCallException e) {
            model.addAttribute("historyError", e.getMessage());
        }
        return "configstream-admin/history";
    }

    private ServiceSummary findService(String serviceName) {
        return registry.service(serviceName).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No service named '" + serviceName + "' is registered"));
    }
}

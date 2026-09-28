package io.github.configstream.admin.web;

import io.github.configstream.admin.client.ServiceCallException;
import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.admin.registry.ServiceSummary;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
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

    /**
     * Every registered service, or those whose name or team contains {@code q} (ignoring case). The page also filters
     * as you type; {@code q} makes a search work without JavaScript and lets it be linked to.
     */
    @GetMapping("/")
    String dashboard(@RequestParam(required = false) String q, Model model) {
        List<ServiceSummary> all = registry.services();
        String query = q == null ? "" : q.strip();
        String needle = query.toLowerCase(Locale.ROOT);
        // Every card is rendered and the non-matching ones hidden, so filtering as you type can widen the search again
        Set<String> matches = all.stream()
                .filter(s -> s.serviceName().toLowerCase(Locale.ROOT).contains(needle)
                        || (s.team() != null && s.team().toLowerCase(Locale.ROOT).contains(needle)))
                .map(ServiceSummary::serviceName)
                .collect(Collectors.toSet());
        model.addAttribute("services", all);
        model.addAttribute("matches", matches);
        model.addAttribute("totalServices", all.size());
        model.addAttribute("query", query);
        return "configstream-admin/dashboard";
    }

    @GetMapping("/services/{serviceName}")
    String service(@PathVariable String serviceName, @RequestParam(required = false) String view, Model model) {
        ServiceSummary service = findService(serviceName);
        model.addAttribute("service", service);
        try {
            List<PropertyRow> rows = PropertyRow.of(client.currentConfig(serviceName), service);
            // Orphans are clean-up work, on their own tab next to the properties in use
            List<PropertyRow> orphans = rows.stream().filter(PropertyRow::orphan).toList();
            model.addAttribute("config", rows);
            model.addAttribute("properties", rows.stream().filter(row -> !row.orphan()).toList());
            model.addAttribute("orphans", orphans);
            model.addAttribute("showOrphans", "orphans".equals(view) && !orphans.isEmpty());
        } catch (ServiceCallException e) {
            model.addAttribute("configError", e.getMessage());
        }
        return "configstream-admin/service";
    }

    @GetMapping("/services/{serviceName}/history")
    String history(@PathVariable String serviceName, @RequestParam String key, @RequestParam String type, Model model) {
        model.addAttribute("service", findService(serviceName));
        model.addAttribute("key", key);
        model.addAttribute("type", type);
        try {
            model.addAttribute("entries", client.history(serviceName, key, type, HISTORY_LIMIT));
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

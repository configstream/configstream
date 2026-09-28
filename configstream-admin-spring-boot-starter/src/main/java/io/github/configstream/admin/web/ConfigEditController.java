package io.github.configstream.admin.web;

import io.github.configstream.admin.client.ConfigEntry;
import io.github.configstream.admin.client.ServiceCallException;
import io.github.configstream.admin.client.ServiceClient;
import io.github.configstream.admin.registry.InstanceRegistry;
import io.github.configstream.admin.registry.ServiceSummary;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.InvalidConfigValueException;
import io.github.configstream.api.PropertyType;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Write path of the UI. It edits values only: properties are added in each service's code (its {@code @LiveConfig} classes), never here,
 * and their types never change. Every change takes two steps: an edit is checked against the property's type and
 * reviewed against the current value before it is applied; a delete, allowed only for orphans (properties no active
 * instance declares), is confirmed on its own page. Writes go through the service's own internal endpoints, never to
 * its database directly, and the service checks every rule again.
 *
 * <p>There is no login yet, so "changed by" is whatever the user types; it is remembered in the session
 * to save retyping. Phase 6 replaces it with the authenticated user.
 */
@Controller
@RequestMapping("/services/{serviceName}")
class ConfigEditController {

    static final String CHANGED_BY_SESSION_KEY = "configstream-admin.changedBy";

    private final InstanceRegistry registry;
    private final ServiceClient client;
    private final String basePath;

    /** {@code basePath} is the dashboard's path prefix ({@code ""} at the root), used for redirects. */
    ConfigEditController(InstanceRegistry registry, ServiceClient client, String basePath) {
        this.registry = registry;
        this.client = client;
        this.basePath = basePath;
    }

    /** The edit form for an existing property. Pre-filled from the parameters, e.g. by the history page's "Restore" links. */
    @GetMapping("/edit")
    String edit(@PathVariable String serviceName, ChangeForm form, HttpSession session, Model model) {
        ServiceSummary service = findService(serviceName);
        if (ChangeForm.isBlank(form.key())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "key is required: properties are added in the service's code, not here");
        }
        ChangeForm filled = form.withChangedByDefault(rememberedChangedBy(session));
        ConfigEntry current = currentEntry(serviceName, filled.key(), model).orElse(null);
        if (current != null && filled.value() == null) {
            filled = filled.withValue(current.value()); // start from the current value
        }
        return editPage(service, filled, current, model);
    }

    @PostMapping("/edit/review")
    String review(@PathVariable String serviceName, ChangeForm form, HttpSession session, Model model) {
        ServiceSummary service = findService(serviceName);
        if (!validate(form, model)) {
            // Redraw the input that matches the property's type, highlighted
            return editPage(service, form, form.key() == null ? null : currentOrNull(serviceName, form.key()), model);
        }
        remember(session, form.changedBy());
        ConfigEntry current;
        try {
            current = lookUp(serviceName, form.key()).orElse(null);
        } catch (ServiceCallException e) {
            model.addAttribute("errors", List.of(e.getMessage()));
            return editPage(service, form, null, model);
        }
        if (current == null) {
            model.addAttribute("errors", List.of(notFound(form.key())));
            return editPage(service, form, null, model);
        }
        Optional<String> invalid = typeError(current.type(), form.value());
        if (invalid.isPresent()) {
            // Shown on the value field itself, which keeps what was typed
            model.addAttribute("valueError", invalid.get());
            return editPage(service, form, current, model);
        }
        if (sameValue(current, form.value())) {
            model.addAttribute("errors", List.of("'" + form.key() + "' already has this value; nothing to change."));
            return editPage(service, form, current, model);
        }
        model.addAttribute("service", service);
        model.addAttribute("form", form.withType(current.type()));
        model.addAttribute("current", current);
        model.addAttribute("description", service.declaration(form.key()).map(d -> d.description()).orElse(null));
        model.addAttribute("review", true);
        return "configstream-admin/edit";
    }

    @PostMapping("/update")
    String update(@PathVariable String serviceName, ChangeForm form, HttpSession session, Model model,
            RedirectAttributes redirect) {
        ServiceSummary service = findService(serviceName);
        if (!validate(form, model)) {
            return editPage(service, form, null, model);
        }
        Optional<String> invalid = form.type() == null ? Optional.empty() : typeError(form.type(), form.value());
        if (invalid.isPresent()) {
            model.addAttribute("valueError", invalid.get());
            return editPage(service, form, null, model);
        }
        remember(session, form.changedBy());
        Optional<ConfigHistoryEntry> entry;
        try {
            entry = client.update(serviceName, form.key(), form.value(), form.type(), form.changedBy(), form.comment());
        } catch (ServiceCallException e) {
            model.addAttribute("errors", List.of("Update failed: " + e.getMessage()));
            return editPage(service, form, null, model);
        }
        redirect.addFlashAttribute("notice", entry
                .map(e -> "Updated '" + e.key() + "' (v" + e.version() + "). All instances pick it up within about a second.")
                .orElse("'" + form.key() + "' already had this value; nothing changed."));
        return "redirect:" + basePath + "/services/{serviceName}";
    }

    @GetMapping("/delete")
    String confirmDelete(@PathVariable String serviceName, ChangeForm form, HttpSession session, Model model) {
        ServiceSummary service = findService(serviceName);
        if (ChangeForm.isBlank(form.key())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key is required");
        }
        ChangeForm filled = form.withChangedByDefault(rememberedChangedBy(session));
        model.addAttribute("current", currentEntry(serviceName, filled.key(), model).orElse(null));
        return deletePage(service, filled, model);
    }

    @PostMapping("/delete")
    String delete(@PathVariable String serviceName, ChangeForm form, HttpSession session, Model model,
            RedirectAttributes redirect) {
        ServiceSummary service = findService(serviceName);
        List<String> errors = form.validateDeletion();
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            return deletePage(service, form, model);
        }
        if (!service.isOrphan(form.key())) {
            return deletePage(service, form, model); // the page explains why it can't be deleted
        }
        remember(session, form.changedBy());
        Optional<ConfigHistoryEntry> entry;
        try {
            entry = client.delete(serviceName, form.key(), form.changedBy(), form.comment());
        } catch (ServiceCallException e) {
            model.addAttribute("errors", List.of("Delete failed: " + e.getMessage()));
            return deletePage(service, form, model);
        }
        redirect.addFlashAttribute("notice", entry
                .map(e -> "Deleted '" + e.key() + "' (v" + e.version() + "). Its history is kept.")
                .orElse("'" + form.key() + "' was already deleted; nothing changed."));
        // Back to the orphans tab, to carry on cleaning up (it falls back to the properties in use once none are left)
        return "redirect:" + basePath + "/services/{serviceName}?view=orphans";
    }

    /**
     * Checks the fields every change needs. Problems with the key or name are shown at the top; a missing value is
     * shown on the value field, like a value of the wrong type.
     */
    private static boolean validate(ChangeForm form, Model model) {
        List<String> errors = form.validateDeletion();
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
        }
        if (form.value() == null) {
            model.addAttribute("valueError", "Value is required.");
        }
        return errors.isEmpty() && form.value() != null;
    }
    private String editPage(ServiceSummary service, ChangeForm form, ConfigEntry current, Model model) {
        model.addAttribute("service", service);
        model.addAttribute("form", current != null ? form.withType(current.type()) : form);
        model.addAttribute("current", current);
        model.addAttribute("description", form.key() == null ? null
                : service.declaration(form.key()).map(d -> d.description()).orElse(null));
        model.addAttribute("review", false);
        return "configstream-admin/edit";
    }

    private String deletePage(ServiceSummary service, ChangeForm form, Model model) {
        model.addAttribute("service", service);
        model.addAttribute("form", form);
        boolean orphan = form.key() != null && service.isOrphan(form.key());
        model.addAttribute("deletable", orphan);
        if (!orphan && form.key() != null) {
            long declaring = service.declaringCount(form.key());
            model.addAttribute("inUse", declaring > 0
                    ? "'" + form.key() + "' is declared by " + declaring + (declaring == 1 ? " active instance" : " active instances")
                            + " of " + service.serviceName() + ", so it is in use and can't be deleted. Remove it from the "
                            + "service's @LiveConfig class and deploy that version first; once no running instance declares it, "
                            + "it is shown as an orphan and can be deleted."
                    : "'" + form.key() + "' can't be deleted yet: not every running instance of " + service.serviceName()
                            + " has reported which properties it uses (instances of an older configstream version don't).");
        }
        return "configstream-admin/delete";
    }

    /** The property's current type and value, or empty if it doesn't exist (or the lookup failed, shown as an error). */
    private Optional<ConfigEntry> currentEntry(String serviceName, String key, Model model) {
        try {
            Optional<ConfigEntry> entry = lookUp(serviceName, key);
            if (entry.isEmpty()) {
                model.addAttribute("errors", List.of(notFound(key)));
            }
            return entry;
        } catch (ServiceCallException e) {
            model.addAttribute("errors", List.of(e.getMessage()));
            return Optional.empty();
        }
    }

    /** The property's type and value, or {@code null} if it doesn't exist or can't be looked up right now. */
    private ConfigEntry currentOrNull(String serviceName, String key) {
        try {
            return lookUp(serviceName, key).orElse(null);
        } catch (ServiceCallException e) {
            return null;
        }
    }

    private Optional<ConfigEntry> lookUp(String serviceName, String key) {
        return Optional.ofNullable(client.currentConfig(serviceName).get(key));
    }

    private static String notFound(String key) {
        return "There is no property '" + key + "'. Properties are added in each service's code (its @LiveConfig classes), not here.";
    }

    /** Why {@code value} isn't valid for {@code typeName}, if it isn't. */
    private static Optional<String> typeError(String typeName, String value) {
        try {
            PropertyType.fromName(typeName).parse(value);
            return Optional.empty();
        } catch (InvalidConfigValueException e) {
            return Optional.of(e.getMessage());
        } catch (IllegalArgumentException unknownType) {
            return Optional.empty(); // a type this admin doesn't know; the service checks it
        }
    }

    private static boolean sameValue(ConfigEntry current, String value) {
        try {
            PropertyType type = PropertyType.fromName(current.type());
            return type.sameValue(type.parse(current.value()), type.parse(value));
        } catch (IllegalArgumentException e) {
            return current.value().equals(value);
        }
    }

    private ServiceSummary findService(String serviceName) {
        return registry.service(serviceName).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No service named '" + serviceName + "' is registered"));
    }

    private static String rememberedChangedBy(HttpSession session) {
        return session.getAttribute(CHANGED_BY_SESSION_KEY) instanceof String s ? s : null;
    }

    private static void remember(HttpSession session, String changedBy) {
        session.setAttribute(CHANGED_BY_SESSION_KEY, changedBy);
    }

    /**
     * Form fields shared by the update and delete flows. Blank comments count as none; values are kept
     * exactly as typed (an empty value is a valid string). {@code type} is the property's type, carried from the
     * review to the update so the service can reject a type change.
     */
    record ChangeForm(String key, String value, String type, String changedBy, String comment) {

        ChangeForm {
            key = trimToNull(key);
            type = trimToNull(type);
            changedBy = trimToNull(changedBy);
            comment = trimToNull(comment);
        }

        List<String> validateDeletion() {
            List<String> errors = new ArrayList<>();
            if (key == null) {
                errors.add("Key is required.");
            }
            if (changedBy == null) {
                errors.add("Enter your name, so the change can be traced back to you.");
            }
            return errors;
        }

        ChangeForm withValue(String newValue) {
            return new ChangeForm(key, newValue, type, changedBy, comment);
        }

        ChangeForm withType(String newType) {
            return new ChangeForm(key, value, newType, changedBy, comment);
        }

        ChangeForm withChangedByDefault(String remembered) {
            return new ChangeForm(key, value, type, changedBy != null ? changedBy : remembered, comment);
        }

        static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }

        private static String trimToNull(String s) {
            return isBlank(s) ? null : s.trim();
        }
    }
}

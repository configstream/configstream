// Filters the properties on the service page's current tab as you type. The page works without it; the box stays
// hidden unless this script runs.
document.addEventListener("DOMContentLoaded", () => {
    const input = document.querySelector("[data-cs-filter]");
    if (!input) {
        return;
    }
    const panel = input.closest(".cs-panel");
    const rows = Array.from(panel.querySelectorAll("[data-cs-key]"));
    const none = panel.querySelector("[data-cs-nomatch]");
    input.closest(".cs-search").hidden = false;
    input.addEventListener("input", () => {
        const q = input.value.trim().toLowerCase();
        let shown = 0;
        for (const row of rows) {
            const match = row.dataset.csKey.toLowerCase().includes(q);
            row.hidden = !match;
            if (match) {
                shown++;
            }
        }
        none.hidden = shown > 0;
    });
});

// Filters the services on the dashboard by name or team as you type. The search is also a plain GET form (?q=), so it
// works without this script and can be linked to.
document.addEventListener("DOMContentLoaded", () => {
    const input = document.querySelector("[data-cs-service-filter]");
    if (!input) {
        return;
    }
    const cards = Array.from(document.querySelectorAll("[data-cs-search]"));
    const count = document.querySelector("[data-cs-service-count]");
    const none = document.querySelector("[data-cs-service-nomatch]");
    const total = cards.length;
    input.addEventListener("input", () => {
        const q = input.value.trim().toLowerCase();
        let shown = 0;
        for (const card of cards) {
            const match = card.dataset.csSearch.toLowerCase().includes(q);
            card.hidden = !match;
            if (match) {
                shown++;
            }
        }
        count.textContent = q === "" ? total + (total === 1 ? " service" : " services")
            : shown + " of " + total + " services";
        none.textContent = "No services match '" + input.value.trim() + "'.";
        none.hidden = shown > 0;
        // Keep the address in step, so the current search can be bookmarked or shared
        const url = new URL(window.location.href);
        if (q === "") {
            url.searchParams.delete("q");
        } else {
            url.searchParams.set("q", input.value.trim());
        }
        window.history.replaceState(null, "", url);
    });
});
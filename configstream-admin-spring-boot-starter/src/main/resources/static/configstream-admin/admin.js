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

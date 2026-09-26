// Filters the config list on the service page as you type. The page works without it; the box stays
// hidden unless this script runs.
document.addEventListener("DOMContentLoaded", () => {
    const input = document.querySelector("[data-cs-filter]");
    if (!input) {
        return;
    }
    const rows = Array.from(document.querySelectorAll("[data-cs-key]"));
    const count = document.querySelector("[data-cs-count]");
    const none = document.querySelector("[data-cs-nomatch]");
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
        count.textContent = shown === rows.length ? rows.length + (rows.length === 1 ? " key" : " keys")
            : shown + " of " + rows.length + " keys";
        none.hidden = shown > 0;
    });
});

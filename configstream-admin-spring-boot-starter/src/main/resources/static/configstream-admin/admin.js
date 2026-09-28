// Filters the properties on the service page as you type, in use and orphaned alike. The page works without it; the
// box stays hidden unless this script runs.
document.addEventListener("DOMContentLoaded", () => {
    const input = document.querySelector("[data-cs-filter]");
    if (!input) {
        return;
    }
    const configPanel = input.closest(".cs-panel");
    const inUse = Array.from(configPanel.querySelectorAll("[data-cs-key]"));
    const orphans = document.querySelector(".cs-orphans");
    const orphanRows = orphans ? Array.from(orphans.querySelectorAll("[data-cs-key]")) : [];
    const count = document.querySelector("[data-cs-count]");
    const none = document.querySelector("[data-cs-nomatch]");
    input.closest(".cs-search").hidden = false;

    const filter = (rows, q) => {
        let shown = 0;
        for (const row of rows) {
            const match = row.dataset.csKey.toLowerCase().includes(q);
            row.hidden = !match;
            if (match) {
                shown++;
            }
        }
        return shown;
    };

    input.addEventListener("input", () => {
        const q = input.value.trim().toLowerCase();
        const shown = filter(inUse, q);
        count.textContent = shown === inUse.length ? inUse.length + (inUse.length === 1 ? " key" : " keys")
            : shown + " of " + inUse.length + " keys";
        none.hidden = shown > 0 || inUse.length === 0;
        if (orphans) {
            orphans.hidden = filter(orphanRows, q) === 0;
        }
    });
});

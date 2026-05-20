// doc-diff overlay: floating toggle + legend; Alt-click on a highlighted block
// opens the corresponding fragment on the baseline (live) site in a new tab.
(function () {
    const STORAGE_KEY = 'doc-diff-on';

    const btn = document.createElement('button');
    btn.id = 'doc-diff-toggle';
    btn.type = 'button';

    const legend = document.createElement('div');
    legend.id = 'doc-diff-legend';
    legend.innerHTML =
        '<strong>Doc diff vs baseline</strong>' +
        '<div><span class="swatch reused"></span> reused (exact)</div>' +
        '<div><span class="swatch modified"></span> modified (fuzzy match)</div>' +
        '<div><span class="swatch new"></span> new (no match in baseline)</div>' +
        '<small>Alt-click a block to open its original on the baseline.</small>';

    const setOn = (on) => {
        document.body.classList.toggle('doc-diff-on', on);
        btn.textContent = on ? 'Diff: ON' : 'Diff: off';
        try { localStorage.setItem(STORAGE_KEY, on ? '1' : '0'); } catch (e) { /* ignore */ }
    };

    btn.addEventListener('click', () => {
        setOn(!document.body.classList.contains('doc-diff-on'));
    });

    document.addEventListener('click', (e) => {
        if (!e.altKey) return;
        const el = e.target.closest('[data-source]');
        if (!el) return;
        e.preventDefault();
        window.open(el.getAttribute('data-source'), '_blank', 'noopener');
    });

    const attach = () => {
        document.body.appendChild(btn);
        document.body.appendChild(legend);
        let saved = '0';
        try { saved = localStorage.getItem(STORAGE_KEY) || '0'; } catch (e) { /* ignore */ }
        setOn(saved === '1');
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', attach);
    } else {
        attach();
    }
})();

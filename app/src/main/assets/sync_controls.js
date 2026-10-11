(function (controls) {
    'use strict';
    const documents = [document];
    function walk(doc, depth) {
        if (depth > 3) return;
        doc.querySelectorAll('iframe,frame').forEach(frame => {
            try { if (frame.contentDocument) { documents.push(frame.contentDocument); walk(frame.contentDocument, depth + 1); } } catch (_) {}
        });
    }
    walk(document, 0);
    let changed = false;
    for (const [key, value] of Object.entries(controls)) {
        const element = documents.map(doc => doc.getElementById(key) || Array.from(doc.querySelectorAll('select')).find(e => e.name === key)).find(Boolean);
        if (!element || !Array.from(element.options).some(option => option.value === value)) return { state: 'term_missing' };
        if (element.value !== value) {
            element.value = value; element.dispatchEvent(new Event('change', { bubbles: true })); changed = true;
        }
    }
    if (changed) {
        for (const doc of documents) {
            const button = Array.from(doc.querySelectorAll('button,input[type=button],input[type=submit],a')).find(e =>
                e.getClientRects().length && /^(?:查询|搜索|查\s*询|search|query)$/i.test((e.innerText || e.value || '').trim()));
            if (button) { button.click(); break; }
        }
    }
    return { state: 'ready', changed: changed };
})

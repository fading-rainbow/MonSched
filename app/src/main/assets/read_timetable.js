(function () {
    'use strict';
    const token = window.__qingkeImportToken;
    const docs = [document];
    function walk(doc, depth) {
        if (depth > 3) return;
        doc.querySelectorAll('iframe,frame').forEach(function (frame) {
            try {
                const child = frame.contentDocument;
                if (child) {
                    docs.push(child); walk(child, depth + 1);
                }
            } catch (_) { /* Cross-origin frames remain isolated. */ }
        });
    }
    walk(document, 0);
    const tables = [];
    docs.forEach(function (doc) {
        doc.querySelectorAll('table').forEach(function (table) {
            // Read course tables only; never extract login fields or the full document.
            const t = table.innerText || '';
            const header = table.querySelector('tr');
            const h = header ? (header.innerText || '') : '';
            const columnList = /课程名称|courseName|\bname\b/i.test(h) && /星期|weekday|\bday\b/i.test(h) && /周次|weeks/i.test(h) && /节次|开始节|sections|startSection|start_section/i.test(h);
            if ((t.indexOf('周') !== -1 && t.indexOf('节') !== -1) || table.id.indexOf('kbgrid') !== -1 || columnList) {
                const copy = table.cloneNode(true);
                copy.querySelectorAll('input,select,textarea,script,style').forEach(function (e) { e.remove(); });
                tables.push(copy.outerHTML);
            }
        });
    });
    const html = tables.join('\n');
    function finish(json) {
        if (window.__qingkeImportToken === token) {
            window.__qingkeImportResult = JSON.stringify({ json: json || null, html: html });
        }
    }
    if (window.__qingkeImportSystem && window.__qingkeImportSystem !== 'zhengfang') { finish(null); return; }
    const doc = docs.find(function (d) {
        return d.querySelector('#xnm') && d.querySelector('#xqm') && /kbcx/i.test(d.location.pathname);
    });
    if (!doc) { finish(null); return; }
    const year = doc.querySelector('#xnm').value;
    const term = doc.querySelector('#xqm').value;
    if (!/^\d{4}$/.test(year) || !/^\d+$/.test(term)) { finish(null); return; }
    const context = doc.location.pathname.split('/kbcx/')[0];
    const url = new URL(context + '/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151', doc.location.href);
    if (url.origin !== doc.location.origin) { finish(null); return; }
    const controller = new AbortController();
    const timer = setTimeout(function () { controller.abort(); }, 10000);
    doc.defaultView.fetch(url.href, {
        method: 'POST', credentials: 'same-origin', signal: controller.signal,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
        body: new URLSearchParams({ xnm: year, xqm: term, kzlx: 'ck' }).toString()
    }).then(function (response) {
        if (!response.ok) throw new Error('HTTP error');
        return response.json();
    }).then(function (data) {
        clearTimeout(timer); finish(Array.isArray(data.kbList) ? data : null);
    }).catch(function () { clearTimeout(timer); finish(null); });
})();

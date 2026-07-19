function renderMarkdown(md) {
    var html = '', lines = md.split('\n'), i = 0;
    while (i < lines.length) {
        var line = lines[i];
        // Fenced code block
        if (line.startsWith('```')) {
            var lang = line.slice(3).trim(), code = '';
            i++;
            while (i < lines.length && !lines[i].startsWith('```')) { code += escHtml(lines[i]) + '\n'; i++; }
            i++;
            // A mermaid fence renders as a diagram — but not here. This function is pure string
            // work and is tested as such, in a JSContext with no DOM and no mermaid. All it does is
            // mark the block; renderDiagrams (in the page, after the innerHTML swap) turns it into
            // a picture. The <pre> it emits is doing four jobs at once: what you see before mermaid
            // has run, what you see if mermaid failed, what you see if the bundle is missing, and
            // the source that a re-theme re-renders from. So it is kept, and hidden by CSS once a
            // diagram has been attached, rather than replaced.
            if (lang === 'mermaid') {
                html += '<div class="rm-mermaid"><pre><code>' + code + '</code></pre></div>\n';
            } else {
                html += '<pre><code>' + code + '</code></pre>\n';
            }
            continue;
        }
        // Heading
        var hm = line.match(/^(#{1,6}) (.*)/);
        if (hm) { var lvl = hm[1].length; html += '<h'+lvl+'>' + inline(hm[2]) + '</h'+lvl+'>\n'; i++; continue; }
        // GFM table
        if (line.includes('|') && i+1 < lines.length && /^[\s|:–-]+$/.test(lines[i+1].replace(/[|]/g,''))) {
            var heads = parseCells(line);
            html += '<table>\n<thead>\n<tr>' + heads.map(function(h){return '<th>'+inline(h)+'</th>';}).join('') + '</tr>\n</thead>\n<tbody>\n';
            i += 2;
            while (i < lines.length && lines[i].includes('|')) {
                var cells = parseCells(lines[i]);
                html += '<tr>' + cells.map(function(c){return '<td>'+inline(c)+'</td>';}).join('') + '</tr>\n'; i++;
            }
            html += '</tbody>\n</table>\n'; continue;
        }
        // Unordered list
        if (/^[-*+] /.test(line)) {
            html += '<ul>\n';
            while (i < lines.length && /^[-*+] /.test(lines[i])) { html += '<li>' + inline(lines[i].replace(/^[-*+] /,'')) + '</li>\n'; i++; }
            html += '</ul>\n'; continue;
        }
        // Ordered list
        if (/^\d+\. /.test(line)) {
            html += '<ol>\n';
            while (i < lines.length && /^\d+\. /.test(lines[i])) { html += '<li>' + inline(lines[i].replace(/^\d+\. /,'')) + '</li>\n'; i++; }
            html += '</ol>\n'; continue;
        }
        // Blockquote
        if (line.startsWith('> ')) {
            var bq = '';
            while (i < lines.length && lines[i].startsWith('> ')) { bq += lines[i].slice(2) + '\n'; i++; }
            html += '<blockquote>' + renderMarkdown(bq) + '</blockquote>\n'; continue;
        }
        // Thematic break
        if (/^[-*_]{3,}$/.test(line.trim())) { html += '<hr>\n'; i++; continue; }
        // Blank
        if (line.trim() === '') { i++; continue; }
        // Paragraph.
        //
        // Take the first line UNCONDITIONALLY. It got here, so no block rule wanted it, and
        // the guard below would refuse it too — every block character (#>*+- and digit-dot)
        // also begins plenty of things that are just prose: "**Date:** ...", "*emphasis*",
        // "+1 more", "#hashtag". Refusing it leaves i where it was and the outer loop spins
        // forever, which hangs the whole web view: no render, no error, a window that never
        // paints. A line of bold at the top of a document is enough to do it.
        //
        // The guard still applies to CONTINUATION lines, so a paragraph stops at the next block.
        var para = lines[i];
        i++;
        while (i < lines.length && lines[i].trim() !== '' && !/^[#>*+\-]|^\d+\./.test(lines[i]) && !lines[i].startsWith('```')) {
            para += ' ' + lines[i];
            i++;
        }
        if (para.trim()) html += '<p>' + inline(para) + '</p>\n';
    }
    return html;
}
function parseCells(row) {
    return row.replace(/^\||\|$/g,'').split('|').map(function(c){return c.trim();});
}
// Anything whose innards must survive the emphasis rules is rendered up front and parked behind
// a placeholder, then put back at the end.
//
// This is not tidiness. `_` is an emphasis character AND a perfectly ordinary character in a
// filename: crop_1298_modules.jpg. With the emphasis rules running first, the URL of every image
// and link with an underscore in it came out as crop<em>1298</em>modules.jpg — a broken path,
// silently. Holding the finished tag keeps the regexes off it.
function inline(t) {
    var held = [];
    function hold(html) { held.push(html); return '\u0000' + (held.length - 1) + '\u0000'; }

    // Code first: its contents are literal, and nothing below may touch them.
    t = t.replace(/`([^`]+)`/g, function(_, c) { return hold('<code>' + escHtml(c) + '</code>'); });

    // Images before links: ![alt](src) also matches the link shape, so links would eat it and
    // leave a stray '!' behind — which is exactly what used to happen. No images ever rendered.
    t = t.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, function(_, alt, src) {
        var safe = safeSrc(src);
        // A disallowed scheme (javascript:, file:, …) is rendered as its literal text rather than
        // emitted as a live <img>. escAttr does HTML-attribute escaping, NOT URL sanitization, so it
        // cannot be the safety boundary on its own — this is. Belt-and-braces to the page CSP.
        if (safe === null) return hold(escHtml('![' + alt + '](' + src + ')'));
        return hold('<img src="' + escAttr(safe) + '" alt="' + escAttr(alt) + '" loading="lazy">');
    });

    t = t.replace(/\[([^\]]*)\]\(([^)]+)\)/g, function(_, text, href) {
        var safe = safeHref(href);
        // javascript:/data:/vbscript: hrefs are the script-execution vector CSP cannot block (the app
        // legitimately needs script-src 'unsafe-inline'). Neutralize them to plain text here; the
        // navigation guard in RemarkPreviewPanel is the second line for anything that slips through.
        if (safe === null) return hold(escHtml('[' + text + '](' + href + ')'));
        return hold('<a href="' + escAttr(safe) + '">' + escHtml(text) + '</a>');
    });

    t = t.replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>');
    t = t.replace(/__(.+?)__/g,'<strong>$1</strong>');
    t = t.replace(/\*(.+?)\*/g,'<em>$1</em>');
    t = t.replace(/_([^_]+)_/g,'<em>$1</em>');

    return t.replace(/\u0000(\d+)\u0000/g, function(_, i) { return held[+i]; });
}
// URL-scheme allowlists — the safety boundary escAttr is not. Return the url when its scheme is
// allowed (or it is relative / scheme-less), null to reject it. A leading whitespace/control run
// cannot be used to smuggle "java\tscript:" past the check because the scheme match is anchored.
function schemeOf(url) {
    var m = String(url).match(/^\s*([a-zA-Z][a-zA-Z0-9+.\-]*):/);
    return m ? m[1].toLowerCase() : null;
}
function safeHref(url) {
    var s = schemeOf(url);
    if (s === null) return url;                 // relative / anchor / scheme-less
    if (s === 'http' || s === 'https' || s === 'mailto' || s === 'rmimg') return url;
    return null;                                // javascript:, data:, vbscript:, file:, …
}
function safeSrc(url) {
    var s = schemeOf(url);
    if (s === null) return url;                 // relative (already rewritten to rmimg: upstream)
    // http/https are kept as-is; the page CSP (img-src rmimg: data:) refuses the actual fetch, so a
    // remote image degrades to a broken-image icon rather than firing an outbound beacon.
    if (s === 'rmimg' || s === 'data' || s === 'http' || s === 'https') return url;
    return null;                                // javascript:, file:, …
}
function escHtml(s) {
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}
function escAttr(s) {
    return escHtml(s).replace(/"/g,'&quot;');
}
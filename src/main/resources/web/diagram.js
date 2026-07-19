var rmCache = new Map();      // source text -> {svg, shape}: every diagram we have ever rendered
var rmFail = new Map();       // source text -> message: every source we have already failed on
var rmLastGood = {};          // diagram ordinal -> {svg, shape}: the keep-last-good error path
var rmSeq = 0;                // unique ids for mermaid.render
var rmThemeApplied = null;
var RM_CACHE_MAX = 50;        // a long session cannot grow these without bound

var rmDark = window.matchMedia('(prefers-color-scheme: dark)');

function initMermaidTheme() {
    if (!window.mermaid) return;
    var theme = rmDark.matches ? 'dark' : 'default';
    if (rmThemeApplied === theme) return;
    mermaid.initialize({
        startOnLoad: false,          // we drive every render ourselves, per element
        suppressErrorRendering: true, // no mermaid-authored error graphics in our page
        securityLevel: 'strict',
        theme: theme
    });
    rmThemeApplied = theme;
}

function rmCachePut(map, src, val) {
    if (map.has(src)) map.delete(src);       // re-insert, so this becomes the newest
    map.set(src, val);
    while (map.size > RM_CACHE_MAX) {
        map.delete(map.keys().next().value); // Map iterates oldest-first
    }
}

/// The diagram's source: the <pre> the renderer emitted, which stays in the DOM (hidden) precisely
/// so it can be read again on every re-render and re-theme.
function diagramSource(el) {
    var code = el.querySelector('pre > code');
    return code ? code.textContent.replace(/\n+$/, '') : '';
}

/// The diagram's shape, straight out of the SVG text — no DOM, no layout, so it can be cached
/// alongside the SVG and applied synchronously. This is what keeps heights stable across a
/// re-render: the box is the right size before the browser ever paints it.
function svgShape(svgText) {
    var m = /viewBox\s*=\s*["']([^"']+)["']/.exec(svgText);
    if (!m) return null;
    var p = m[1].trim().split(/[\s,]+/);
    if (p.length !== 4) return null;
    var w = parseFloat(p[2]), h = parseFloat(p[3]);
    if (!(w > 0) || !(h > 0)) return null;
    return {w: w, h: h, aspect: w + ' / ' + h};
}

function svgNaturalSize(svg) {
    if (!svg) return {w: 0, h: 0};
    var a = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/);
    if (a.length === 4 && parseFloat(a[2]) > 0 && parseFloat(a[3]) > 0) {
        return {w: parseFloat(a[2]), h: parseFloat(a[3])};
    }
    try {
        var bb = svg.getBBox();
        if (bb.width > 0 && bb.height > 0) return {w: bb.width, h: bb.height};
    } catch (err) { /* not laid out yet */ }
    return {w: 0, h: 0};
}

// MARK: pan & zoom — one module, used by both the inline diagram and the overlay.
//
// Every listener is on the viewport itself, never on document. The page swaps #content's innerHTML
// on every render, so a document-level listener per diagram would survive its viewport and pile up
// — thousands of dead closures over an editing session. Pointer capture is what lets a drag that
// leaves the viewport keep tracking without one.
function makePanZoom(viewport, canvas) {
    var svg = canvas.querySelector('svg');
    var natural = svgNaturalSize(svg);

    // Mermaid ships its SVG with width:100% and a max-width, which makes it size itself to the
    // viewport — so it would fight the transform rather than ride it. Pin it to its natural pixels
    // and let the transform be the only thing that moves it.
    if (svg) {
        svg.removeAttribute('width');
        svg.removeAttribute('height');
        svg.style.maxWidth = 'none';
        svg.style.width = natural.w + 'px';
        svg.style.height = natural.h + 'px';
        svg.style.display = 'block';
    }

    var st = {s: 1, x: 0, y: 0};
    var api = {touched: false};   // set by any pan or zoom the reader performs: the view is theirs now

    function apply() {
        canvas.style.transform =
            'translate(' + st.x + 'px, ' + st.y + 'px) scale(' + st.s + ')';
    }

    function fit() {
        var vw = viewport.clientWidth, vh = viewport.clientHeight;
        if (!vw || !vh || !natural.w || !natural.h) return;
        var s = Math.min(vw / natural.w, vh / natural.h);
        if (s > 1) s = 1;                       // never blow a small diagram up past life size
        st.s = s;
        st.x = (vw - natural.w * s) / 2;
        st.y = (vh - natural.h * s) / 2;
        apply();
    }

    function clamp(s) { return Math.max(0.2, Math.min(10, s)); }

    /// Zoom about a point, in viewport coordinates: that point stays under the cursor.
    function zoomAt(px, py, factor) {
        var s = clamp(st.s * factor);
        var k = s / st.s;
        st.x = px - (px - st.x) * k;
        st.y = py - (py - st.y) * k;
        st.s = s;
        api.touched = true;
        apply();
    }

    function zoomCenter(factor) {
        zoomAt(viewport.clientWidth / 2, viewport.clientHeight / 2, factor);
    }

    function reset() { api.touched = false; fit(); }

    function local(e) {
        var r = viewport.getBoundingClientRect();
        return {x: e.clientX - r.left, y: e.clientY - r.top};
    }

    function onControls(e) {
        return e.target.closest && e.target.closest('.rm-diagram-controls');
    }

    var dragging = false, lastX = 0, lastY = 0;

    viewport.addEventListener('pointerdown', function(e) {
        if (e.button !== 0 || onControls(e)) return;
        e.preventDefault();          // no text selection, no native SVG drag
        dragging = true;
        lastX = e.clientX;
        lastY = e.clientY;
        viewport.setPointerCapture(e.pointerId);
        viewport.classList.add('grabbing');
    });

    viewport.addEventListener('pointermove', function(e) {
        if (!dragging) return;
        st.x += e.clientX - lastX;
        st.y += e.clientY - lastY;
        lastX = e.clientX;
        lastY = e.clientY;
        api.touched = true;
        apply();
    });

    function endDrag(e) {
        if (!dragging) return;
        dragging = false;
        if (viewport.hasPointerCapture(e.pointerId)) viewport.releasePointerCapture(e.pointerId);
        viewport.classList.remove('grabbing');
    }
    viewport.addEventListener('pointerup', endDrag);
    viewport.addEventListener('pointercancel', endDrag);

    // A PLAIN wheel is the page scrolling past a diagram, and must stay that way — a diagram that
    // ate the scroll wheel would trap the reader in the middle of the document. Only a deliberate
    // zoom gesture (ctrl or cmd held; macOS trackpad pinch arrives as ctrl+wheel) is ours.
    viewport.addEventListener('wheel', function(e) {
        if (!e.ctrlKey && !e.metaKey) return;
        e.preventDefault();
        var p = local(e);
        zoomAt(p.x, p.y, Math.exp(-e.deltaY * 0.01));
    }, {passive: false});

    // WebKit's own pinch gesture (trackpad, on the real magnification path).
    var gestureStartScale = 1;
    viewport.addEventListener('gesturestart', function(e) {
        e.preventDefault();
        gestureStartScale = st.s;
    });
    viewport.addEventListener('gesturechange', function(e) {
        e.preventDefault();
        var p = local(e);
        var target = clamp(gestureStartScale * e.scale);
        zoomAt(p.x, p.y, target / st.s);
    });
    viewport.addEventListener('gestureend', function(e) { e.preventDefault(); });

    viewport.addEventListener('dblclick', function(e) {
        if (onControls(e)) return;
        e.preventDefault();
        reset();
    });

    fit();
    api.fit = fit;
    api.reset = reset;
    api.zoomCenter = zoomCenter;
    return api;
}

function rmButton(glyph, title, fn) {
    var b = document.createElement('button');
    b.type = 'button';
    b.textContent = glyph;
    b.title = title;
    b.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        fn();
    });
    return b;
}

function buildControls(viewport, pz, opts) {
    var bar = document.createElement('div');
    bar.className = 'rm-diagram-controls';
    bar.appendChild(rmButton('+', 'Zoom in', function() { pz.zoomCenter(1.25); }));
    bar.appendChild(rmButton('\u2212', 'Zoom out', function() { pz.zoomCenter(0.8); }));
    bar.appendChild(rmButton('\u21BA', 'Reset', function() { pz.reset(); }));
    if (opts && opts.expand) bar.appendChild(rmButton('\u2922', 'Expand', opts.expand));
    if (opts && opts.close) bar.appendChild(rmButton('\u2715', 'Close', opts.close));
    viewport.appendChild(bar);
}

/// Puts a finished SVG into a placeholder. `stale` marks a picture kept from an earlier, working
/// version of a source that no longer parses.
function attachDiagram(el, svg, shape, stale) {
    var old = el.querySelector('.rm-diagram-viewport');
    if (old) old.remove();          // re-theme and stale-attach both land on an element that has one

    var viewport = document.createElement('div');
    viewport.className = 'rm-diagram-viewport';

    // The box is shaped exactly like an image, and for the same reason: width 100% of the content,
    // BUT never wider than the diagram actually is, with the height following from its aspect ratio.
    // A big diagram fills the pane and scales down to fit; a small one gets a box its own size.
    //
    // Cap only the height (the earlier design) and a small diagram gets a full-width, aspect-tall
    // box with a postage stamp adrift in the middle of it — because fit() will not scale a diagram
    // up past life size, so nothing ever fills that box. `img { max-width: 100% }` has always
    // handled this correctly, and this is that rule.
    if (shape) {
        viewport.style.aspectRatio = shape.aspect;
        viewport.style.maxWidth = shape.w + 'px';
    } else {
        viewport.style.aspectRatio = '4 / 3';   // mermaid always emits a viewBox; belt and braces
    }

    var canvas = document.createElement('div');
    canvas.className = 'rm-diagram-canvas' + (stale ? ' stale' : '');
    canvas.innerHTML = svg;         // mermaid's own output, already sanitized by its DOMPurify
    viewport.appendChild(canvas);

    // Into the DOM BEFORE makePanZoom: fit() measures clientWidth, which is 0 while detached.
    el.appendChild(viewport);
    el.classList.add('has-diagram');

    var pz = makePanZoom(viewport, canvas);
    buildControls(viewport, pz, {
        expand: function() { openDiagramOverlay(canvas.querySelector('svg')); }
    });

    // Resizing the window reflows a diagram like the paragraph next to it — but only while the
    // reader has not panned or zoomed it. After that, leave their view alone.
    if (window.ResizeObserver) {
        new ResizeObserver(function() { if (!pz.touched) pz.fit(); }).observe(viewport);
    }
    return pz;
}

/// Marks a placeholder as failed, and — if this position has ever shown a picture — puts that last
/// good picture back underneath the error, dimmed.
///
/// Idempotent, deliberately: it runs from the sync pass as well as the async catch, and an error
/// strip that appended rather than replaced would stack a new one on every re-render. Under the
/// edit window's ~400 ms flushes that is a document growing a red stripe several times a second.
function applyFailure(el, ordinal, message) {
    el.classList.add('failed');

    var old = el.querySelector('.rm-diagram-error');
    if (old) old.remove();

    var good = rmLastGood[ordinal];
    if (good) attachDiagram(el, good.svg, good.shape, true);

    var div = document.createElement('div');
    div.className = 'rm-diagram-error';
    div.textContent = message;        // textContent: an error message is never markup
    el.appendChild(div);
}

function rmErrorMessage(err) {
    var msg = (err && err.message) ? String(err.message) : String(err);
    return msg.split('\n')[0];       // one line: the rest is a parser stack nobody can act on
}

async function renderDiagrams(root) {
    // No bundle, no diagrams — and the source stays visible, which is the honest fallback.
    if (!window.mermaid) return;
    initMermaidTheme();

    var scope = root || document;
    var els = Array.prototype.slice.call(scope.querySelectorAll('.rm-mermaid'));

    // Pass 1, SYNCHRONOUS. Every diagram whose source we have already SEEN — rendered or failed —
    // is settled right now, in the same turn as the innerHTML swap, before the browser paints and
    // before renderContent restores scrollY. This is the whole reason the caches exist: adding a
    // comment, or a 400 ms flush from the edit window, must not move the page under the reader and
    // must not put a single unchanged diagram back through mermaid.
    //
    // Failures are cached too, and for the same reason. Without it a broken diagram is the one
    // thing that re-enters mermaid on every flush — the diagram most likely to be broken being,
    // of course, the one currently being typed.
    els.forEach(function(el, ordinal) {
        var src = diagramSource(el);

        var hit = rmCache.get(src);
        if (hit) {
            attachDiagram(el, hit.svg, hit.shape, false);
            el.classList.add('rendered');
            rmLastGood[ordinal] = hit;
            return;
        }

        var bad = rmFail.get(src);
        if (bad !== undefined) applyFailure(el, ordinal, bad);
    });

    // Pass 2, async and serial: only sources never seen before. One at a time, each in its own
    // try/catch, so one broken diagram cannot take the others down with it.
    for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.classList.contains('rendered') || el.classList.contains('failed')) continue;
        var src = diagramSource(el);
        if (!src) continue;

        var id = 'rm-mmd-' + (++rmSeq);
        try {
            var out = await mermaid.render(id, src);
            // A newer renderContent has replaced everything we were working on.
            if (!el.isConnected) return;

            var val = {svg: out.svg, shape: svgShape(out.svg)};
            rmCachePut(rmCache, src, val);
            rmLastGood[i] = val;
            attachDiagram(el, val.svg, val.shape, false);
            el.classList.add('rendered');
        } catch (err) {
            if (!el.isConnected) return;

            // suppressErrorRendering keeps mermaid from drawing its own error graphic, but a failed
            // render can still abandon the scratch node it was building in.
            var orphan = document.getElementById(id) || document.getElementById('d' + id);
            if (orphan && !el.contains(orphan)) orphan.remove();

            // Keep the last good picture if this position ever had one: source being typed is
            // broken source most of the time, and a picture that has gone stale beats a diagram
            // flickering to a parse error between keystrokes.
            var message = rmErrorMessage(err);
            rmCachePut(rmFail, src, message);
            applyFailure(el, i, message);
        }
    }
}

// MARK: the expand overlay — a sibling of #content, so no innerHTML swap can touch it.

function openDiagramOverlay(svg) {
    var overlay = document.getElementById('rm-overlay');
    if (!overlay || !svg) return;

    overlay.innerHTML = '';          // rebuilt from scratch every time: no listeners can accumulate

    var viewport = document.createElement('div');
    viewport.className = 'rm-diagram-viewport rm-overlay-viewport';
    var canvas = document.createElement('div');
    canvas.className = 'rm-diagram-canvas';
    canvas.appendChild(svg.cloneNode(true));   // a clone of what is already drawn: mermaid never re-runs
    viewport.appendChild(canvas);
    overlay.appendChild(viewport);

    // Unhide BEFORE makePanZoom: a hidden element measures 0 wide, and fit() would divide into it.
    overlay.hidden = false;

    var pz = makePanZoom(viewport, canvas);
    buildControls(viewport, pz, {close: closeDiagramOverlay});
}

/// Closed by Esc, by ✕, and by anything that rebuilds #content — the clone's gradients and arrow
/// markers are referenced by id into the document a re-render is about to destroy.
function closeDiagramOverlay() {
    var overlay = document.getElementById('rm-overlay');
    if (!overlay || overlay.hidden) return;
    overlay.hidden = true;
    overlay.innerHTML = '';
}

document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') closeDiagramOverlay();
});

// The web view follows the app's appearance, and every cached SVG carries the colours of the theme
// it was drawn under — so a switch throws the lot away and draws again from the source, which is
// exactly what the hidden <pre> was kept for.
rmDark.addEventListener('change', function() {
    closeDiagramOverlay();
    rmCache.clear();
    rmFail.clear();      // or the sync pass would settle a diagram as failed without re-trying it
    rmLastGood = {};
    document.querySelectorAll('.rm-mermaid').forEach(function(el) {
        el.classList.remove('rendered', 'failed', 'has-diagram');
        var vp = el.querySelector('.rm-diagram-viewport');
        if (vp) vp.remove();
        var err = el.querySelector('.rm-diagram-error');
        if (err) err.remove();
    });
    renderDiagrams(document.getElementById('content'));
});

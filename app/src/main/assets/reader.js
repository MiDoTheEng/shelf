(function () {
  if (window.__shelf) return;
  var d = document, r = d.documentElement;
  var S = window.__shelf = {page: 0, pages: 1, f: 0, mode: 'scroll', paged: false, rtl: false, sel: false, last: ''};

  function W() { return window.innerWidth || r.clientWidth || 1; }
  function H() { var b = d.body; return Math.max(r.scrollHeight, b ? b.scrollHeight : 0) - window.innerHeight; }

  function css(c) {
    var base = 'html,body{background:' + c.bg + ' !important;color:' + c.fg + ' !important;}'
      + 'body{margin:0 !important;padding:22px 22px 56px !important;line-height:1.7 !important;overflow-wrap:break-word;}'
      + 'body *{color:inherit !important;background-color:transparent !important;max-width:100% !important;}'
      + 'a,a *{color:' + c.link + ' !important;}'
      + 'img,svg,video{max-width:100% !important;height:auto !important;}'
      + '#__shelfNext{margin:40px 0 8px !important;padding:15px !important;text-align:center !important;'
      + 'border:1px solid ' + c.link + ' !important;border-radius:12px !important;color:' + c.link
      + ' !important;font:600 15px sans-serif !important;}';
    base += '.__shelfHl,.__shelfHl *{background-color:rgba(255,213,74,.42) !important;border-radius:2px;}';
    var w = W();
    // Tablets: a centred column when scrolling, and two pages side by side when wide enough.
    var pad = w >= 600 ? 44 : 22;
    if (c.mode === 'scroll') {
      return base + 'body{max-width:720px !important;margin:0 auto !important;padding:22px ' + pad + 'px 56px !important;}';
    }
    var cols = w >= 840 ? 2 : 1;
    return base
      + 'html{height:100% !important;overflow:hidden !important;}'
      + 'body{box-sizing:border-box !important;width:100vw !important;height:100vh !important;'
      + 'padding:28px ' + pad + 'px 30px !important;'
      + '-webkit-column-width:calc(100vw / ' + cols + ' - ' + (2 * pad) + 'px) !important;'
      + 'column-width:calc(100vw / ' + cols + ' - ' + (2 * pad) + 'px) !important;'
      + '-webkit-column-gap:' + (2 * pad) + 'px !important;column-gap:' + (2 * pad) + 'px !important;'
      + 'column-fill:auto !important;overflow:visible !important;}'
      + 'img,svg,video{max-height:calc(100vh - 90px) !important;object-fit:contain;}'
      + '#__shelfNext{display:none !important;}';
  }

  function rep() {
    if (S.paged) {
      Shelf.onPage(S.pages > 1 ? S.page / (S.pages - 1) : 1, S.page + 1, S.pages);
    } else {
      var h = H();
      S.f = h > 4 ? Math.min(1, window.pageYOffset / h) : 0;
      Shelf.onPage(h > 4 ? S.f : 1, 0, 0);
    }
  }

  function place(anim) {
    var b = d.body;
    if (!b) return;
    b.style.setProperty('transition', anim ? 'transform .2s ease-out' : 'none', 'important');
    b.style.setProperty('transform', 'translateX(' + ((S.rtl ? 1 : -1) * S.page * W()) + 'px)', 'important');
  }

  // Each screenful is one CSS column; a marker at the very end tells how many there are.
  function layout() {
    var b = d.body;
    if (!b) return;
    b.style.setProperty('transition', 'none', 'important');
    b.style.setProperty('transform', 'none', 'important');
    var e = d.getElementById('__shelfEnd');
    if (!e) {
      e = d.createElement('span');
      e.id = '__shelfEnd';
      e.textContent = '​';
      b.appendChild(e);
    }
    S.rtl = getComputedStyle(b).direction === 'rtl';
    var x = e.getBoundingClientRect().left + (S.rtl ? -1 : 1);
    S.pages = Math.abs(Math.floor(x / W())) + 1;
    S.page = Math.max(0, Math.min(S.pages - 1, Math.round(S.f * (S.pages - 1))));
    place(false);
    rep();
  }

  function setPage(p, anim) {
    S.page = p;
    S.f = S.pages > 1 ? p / (S.pages - 1) : 0;
    place(anim);
    rep();
  }

  S.go = function (n) {
    if (!S.paged) return;
    var p = S.page + n;
    if (p < 0 || p >= S.pages) { Shelf.edge(n); return; }
    setPage(p, S.mode !== 'up' && ((S.mode === 'right') === S.rtl));
  };

  S.grab = function (share) {
    var g = window.getSelection();
    var x = String(g || '');
    if (!x.trim()) x = S.last;
    Shelf.grab(x, share);
    if (g) g.removeAllRanges();
  };

  // ---- text highlights, stored as character positions counted from the start of the section

  function position(node, offset) {
    var r = d.createRange();
    r.setStart(d.body, 0);
    r.setEnd(node, offset);
    return r.toString().length;
  }

  function paint(start, end) {
    var w = d.createTreeWalker(d.body, NodeFilter.SHOW_TEXT, null), nodes = [], n;
    while ((n = w.nextNode())) nodes.push(n);
    var pos = 0;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i], len = node.data.length;
      var a = Math.max(start - pos, 0), b = Math.min(end - pos, len);
      pos += len;
      if (a >= b || !node.parentNode) continue;
      var id = node.parentNode.id;
      if (id === '__shelfNext' || id === '__shelfEnd') continue;
      var mid = a > 0 ? node.splitText(a) : node;
      if (b - a < mid.data.length) mid.splitText(b - a);
      if (!mid.data.trim()) continue;
      var span = d.createElement('span');
      span.className = '__shelfHl';
      span.setAttribute('data-s', start);
      mid.parentNode.replaceChild(span, mid);
      span.appendChild(mid);
    }
  }

  S.mark = function () {
    var g = window.getSelection();
    if (!g || !g.rangeCount || !String(g).trim()) return;
    var r = g.getRangeAt(0);
    var a = position(r.startContainer, r.startOffset), b = position(r.endContainer, r.endOffset);
    g.removeAllRanges();
    if (b > a) {
      paint(a, b);
      Shelf.mark(a, b);
    }
  };

  window.__shelfSetup = function (c) {
    if (c.marks && !S.painted && d.body) {
      S.painted = true;
      for (var m = 0; m < c.marks.length; m++) {
        try { paint(c.marks[m][0], c.marks[m][1]); } catch (e) {}
      }
    }
    var s = d.getElementById('__shelfCss');
    if (!s) {
      s = d.createElement('style');
      s.id = '__shelfCss';
      (d.head || r).appendChild(s);
    }
    S.cfg = c;
    s.textContent = css(c);
    var nx = d.getElementById('__shelfNext');
    if (!nx && c.hasNext && d.body) {
      nx = d.createElement('div');
      nx.id = '__shelfNext';
      nx.textContent = 'Next section  ›';
      nx.addEventListener('click', function () { Shelf.next(); }, false);
      d.body.appendChild(nx);
    }
    if (c.f >= 0) S.f = c.f;
    S.mode = c.mode;
    S.paged = c.mode !== 'scroll';
    if (S.paged) {
      window.scrollTo(0, 0);
      layout();
    } else {
      var b = d.body;
      if (b) {
        b.style.removeProperty('transform');
        b.style.removeProperty('transition');
      }
      window.scrollTo(0, S.f > 0 ? Math.round(S.f * H()) : 0);
      setTimeout(rep, 80);
    }
  };

  var t = 0;
  window.addEventListener('scroll', function () {
    if (S.paged) {
      // A link to a spot inside this section scrolls the window; turn that into a page jump.
      var sx = window.pageXOffset, sy = window.pageYOffset;
      if (sx || sy) {
        window.scrollTo(0, 0);
        var k = Math.round(sx / W()) * (S.rtl ? -1 : 1);
        setPage(Math.max(0, Math.min(S.pages - 1, S.page + k)), false);
      }
      return;
    }
    clearTimeout(t);
    t = setTimeout(function () {
      rep();
      if (S.sel) where();
    }, 150);
  }, {passive: true});

  var rt = 0;
  window.addEventListener('resize', function () {
    clearTimeout(rt);
    rt = setTimeout(function () {
      var s = d.getElementById('__shelfCss');
      if (s && S.cfg) s.textContent = css(S.cfg);
      if (S.paged) layout();
    }, 120);
  });
  window.addEventListener('load', function () { if (S.paged) layout(); });

  d.addEventListener('click', function (e) {
    if (S.sel) return;
    var n = e.target;
    while (n && n !== d) {
      var nm = (n.nodeName || '').toLowerCase();
      if (nm === 'a' || n.id === '__shelfNext') return;
      if (n.className === '__shelfHl') { Shelf.markTap(+n.getAttribute('data-s')); return; }
      n = n.parentNode;
    }
    Shelf.tap();
  }, false);

  // Tells the app where the selected text is on screen, so its menu can sit right beside it.
  function where() {
    var g = window.getSelection();
    if (!g || !g.rangeCount) return;
    var r = g.getRangeAt(0).getBoundingClientRect();
    if (r.width || r.height) Shelf.selectionAt(r.left, r.top, r.right, r.bottom);
  }
  S.where = where;

  var st = 0;
  d.addEventListener('selectionchange', function () {
    clearTimeout(st);
    st = setTimeout(function () {
      var x = String(window.getSelection() || '');
      var has = x.trim().length > 0;
      if (has) S.last = x;
      if (has !== S.sel) {
        S.sel = has;
        Shelf.selection(has);
      }
      if (has) where();
    }, 200);
  });
})();

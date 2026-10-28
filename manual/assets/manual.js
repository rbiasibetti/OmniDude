/* Manual do OmniDude: menu, busca, tema, ampliação de imagens, copiar código e índice da página. Sem dependências. */
(function () {
  'use strict';
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var store = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { /* modo privado */ } },
  };

  // ---- tema
  var root = document.documentElement;
  $('.theme-btn').addEventListener('click', function () {
    var cur = root.getAttribute('data-theme') || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
    var next = cur === 'dark' ? 'light' : 'dark';
    root.setAttribute('data-theme', next); store.set('omnidude-manual-theme', next);
  });

  // ---- menu lateral em telas estreitas
  var sidebar = $('#sidebar'), scrim = $('.scrim'), menuBtn = $('.menu-btn');
  function menu(open) {
    sidebar.classList.toggle('open', open); scrim.hidden = !open;
    menuBtn.setAttribute('aria-expanded', String(open));
  }
  menuBtn.addEventListener('click', function () { menu(!sidebar.classList.contains('open')); });
  scrim.addEventListener('click', function () { menu(false); });
  var cur = $('.sidebar a.current'); if (cur && cur.scrollIntoView) cur.scrollIntoView({ block: 'center' });

  // ---- busca
  var input = $('#busca'), box = $('#resultados'), index = window.MANUAL_INDEX || [], sel = -1;
  function norm(s) { return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, ''); }
  var prepared = index.map(function (e) { return { e: e, t: norm(e.t), x: norm(e.x), p: norm(e.p) }; });
  function esc(s) { return s.replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function search(q) {
    var words = norm(q).split(/\s+/).filter(function (w) { return w.length > 1; });
    if (!words.length) return [];
    var hits = [];
    prepared.forEach(function (r) {
      var score = 0;
      for (var i = 0; i < words.length; i++) {
        var w = words[i], s = 0;
        if (r.t.indexOf(w) >= 0) s += 6;
        if (r.p.indexOf(w) >= 0) s += 2;
        if (r.x.indexOf(w) >= 0) s += 1;
        if (!s) return;               // todas as palavras precisam aparecer
        score += s;
      }
      hits.push({ r: r.e, score: score });
    });
    hits.sort(function (a, b) { return b.score - a.score; });
    return hits.slice(0, 8);
  }
  function snippet(text, q) {
    var w = norm(q).split(/\s+/)[0], i = norm(text).indexOf(w);
    var from = Math.max(0, i - 40);
    return (from ? '…' : '') + text.substr(from, 110) + (text.length > from + 110 ? '…' : '');
  }
  function show(list, q) {
    sel = -1;
    if (!q.trim()) { box.hidden = true; return; }
    box.innerHTML = list.length ? list.map(function (h, i) {
      return '<a role="option" href="' + h.r.u + '" data-i="' + i + '"><strong>' + esc(h.r.t) + '</strong><small>' + esc(h.r.p) + '</small><span>' + esc(snippet(h.r.x, q)) + '</span></a>';
    }).join('') : '<p class="none">Nada encontrado para “' + esc(q) + '”.</p>';
    box.hidden = false;
  }
  input.addEventListener('input', function () { show(search(input.value), input.value); });
  input.addEventListener('keydown', function (ev) {
    var items = $$('a', box);
    if (ev.key === 'ArrowDown' || ev.key === 'ArrowUp') {
      ev.preventDefault(); if (!items.length) return;
      sel = (sel + (ev.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
      items.forEach(function (a, i) { a.classList.toggle('sel', i === sel); });
    } else if (ev.key === 'Enter') {
      var t = items[sel >= 0 ? sel : 0]; if (t) location.href = t.getAttribute('href');
    } else if (ev.key === 'Escape') { box.hidden = true; input.blur(); }
  });
  document.addEventListener('click', function (ev) { if (!ev.target.closest('.search')) box.hidden = true; });
  document.addEventListener('keydown', function (ev) {
    if (ev.key === '/' && !/input|textarea|select/i.test(document.activeElement.tagName)) { ev.preventDefault(); input.focus(); input.select(); }
  });

  // ---- ampliar imagens
  var lb = $('.lightbox'), lbImg = $('img', lb), lbCap = $('p', lb);
  function closeLb() { lb.hidden = true; document.body.classList.remove('noscroll'); }
  $$('a.zoom').forEach(function (a) {
    a.addEventListener('click', function (ev) {
      ev.preventDefault();
      var img = $('img', a); lbImg.src = a.getAttribute('href'); lbImg.alt = img.alt;
      lbCap.textContent = img.alt; lb.hidden = false; document.body.classList.add('noscroll');
    });
  });
  lb.addEventListener('click', closeLb);
  document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') { closeLb(); menu(false); } });

  // ---- copiar blocos de código
  $$('pre').forEach(function (pre) {
    if (pre.closest('.no-copy')) return;
    var b = document.createElement('button'); b.type = 'button'; b.className = 'copy'; b.textContent = 'Copiar';
    b.addEventListener('click', function () {
      var text = (pre.querySelector('code') || pre).innerText.replace(/\n$/, '');
      var done = function () { b.textContent = 'Copiado'; setTimeout(function () { b.textContent = 'Copiar'; }, 1500); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, function () { });
      else { var ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select(); try { document.execCommand('copy'); done(); } catch (e) { } ta.remove(); }
    });
    pre.appendChild(b);
  });

  // ---- índice da página: destaca a seção visível
  var links = $$('.toc a');
  if (links.length && 'IntersectionObserver' in window) {
    var map = {}; links.forEach(function (a) { map[a.getAttribute('href').slice(1)] = a; });
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { links.forEach(function (a) { a.classList.remove('on'); }); var a = map[en.target.id]; if (a) a.classList.add('on'); }
      });
    }, { rootMargin: '-70px 0px -70% 0px' });
    $$('.prose h2[id], .prose h3[id]').forEach(function (h) { io.observe(h); });
  }
})();

/* Visão pública de um mapa: só leitura, sem login. Faz GET de um JSON já reduzido pelo servidor e o desenha;
   não há ação possível além de mover e ampliar a vista. */
'use strict';

(function () {
  const REFRESH_MS = 30000;
  const token = location.pathname.split('/').filter(Boolean).pop() || '';
  const el = (id) => document.getElementById(id);
  let cy = null;
  let timer = null;

  function message(text) {
    const m = el('message');
    m.textContent = text || '';
    m.hidden = !text;
  }

  function nodeLabel(n) {
    return n.name || TYPE_LABELS[n.type] || '';
  }

  function nodeColor(n) {
    if (!n.status) return '#64748b';
    return (STATUS[n.status] || STATUS.UNKNOWN).color;
  }

  function statusText(n) {
    return n.status ? '\n' + (STATUS[n.status] || STATUS.UNKNOWN).text : '';
  }

  function init() {
    cy = cytoscape({
      container: el('cy'),
      autoungrabify: true,          // ninguém arrasta nós numa página pública
      boxSelectionEnabled: false,
      minZoom: 0.1,
      maxZoom: 3,
      style: [
        {
          selector: 'node',
          style: {
            width: 54, height: 54, shape: 'round-rectangle', 'background-color': '#1e293b',
            'background-image': (n) => iconUri(n.data('type')), 'background-fit': 'none',
            'background-width': '60%', 'background-height': '60%',
            'border-width': 4, 'border-color': 'data(color)',
            label: 'data(label)', color: '#e2e8f0', 'font-size': 12, 'text-wrap': 'wrap',
            'text-valign': 'bottom', 'text-margin-y': 8, 'text-background-color': '#020617',
            'text-background-opacity': 0.7, 'text-background-padding': 2,
          },
        },
        { selector: 'node[?logical]', style: { 'border-style': 'dashed', 'border-width': 3 } },
        { selector: 'node[status = "DOWN"]', style: { 'border-style': 'double', 'border-width': 6 } },
        {
          selector: 'edge',
          style: { width: 3, 'line-color': '#64748b', 'curve-style': 'bezier', label: 'data(label)', 'font-size': 10,
            color: '#94a3b8', 'text-background-color': '#020617', 'text-background-opacity': 0.8,
            'text-background-padding': 2 },
        },
        { selector: 'edge[kind = "VPN"]', style: { 'line-color': '#14b8a6', 'line-style': 'dashed', 'line-dash-pattern': [10, 3, 2, 3] } },
        { selector: 'edge[kind = "INTERNET"]', style: { 'line-color': '#38bdf8', 'line-style': 'dashed', 'line-dash-pattern': [14, 6] } },
        { selector: 'edge[kind = "WIFI"]', style: { 'line-color': '#a3e635', 'line-style': 'dotted' } },
        { selector: 'edge[?down]', style: { 'line-color': '#ef4444', 'line-style': 'dotted' } },
      ],
    });
    new ResizeObserver(() => cy && cy.resize()).observe(el('cy'));
  }

  const KIND_LABEL = { VPN: 'VPN', INTERNET: 'Internet', WIFI: 'Wi-Fi' };

  function render(map) {
    document.title = map.name;
    el('title').textContent = map.name;
    el('legend').hidden = !map.showStatus;
    const ids = new Set(map.nodes.map((n) => n.id));
    const first = cy.nodes().length === 0;
    cy.batch(() => {
      cy.elements().filter((e) => e.isNode() ? !ids.has(e.id()) : true).remove();
      for (const n of map.nodes) {
        const data = {
          id: n.id, type: n.type, status: n.status, logical: n.logical, color: nodeColor(n),
          label: nodeLabel(n) + statusText(n),
        };
        const found = cy.getElementById(n.id);
        if (found.empty()) cy.add({ group: 'nodes', data, position: { x: n.x, y: n.y } });
        else found.data(data);
      }
      map.links.forEach((l, i) => {
        cy.add({ group: 'edges', data: { id: 'e' + i, source: l.source, target: l.target, kind: l.kind || '', down: l.down,
          label: KIND_LABEL[l.kind] || '' } });
      });
    });
    if (first) cy.fit(undefined, 60);
    message(map.nodes.length ? '' : 'Este mapa ainda não tem dispositivos.');
    el('updated').textContent = 'Atualizado às ' + new Date(map.generatedAt * 1000).toLocaleTimeString('pt-BR');
  }

  async function load() {
    try {
      const res = await fetch('/api/public/maps/' + encodeURIComponent(token), {
        credentials: 'omit', headers: { Accept: 'application/json' }, cache: 'no-store',
      });
      if (res.status === 404) {
        stop();
        if (cy) cy.elements().remove();
        el('legend').hidden = true;
        el('updated').textContent = '';
        message('Mapa não encontrado ou não está mais publicado.');
        return;
      }
      if (res.status === 429) { message('Muitas requisições. Tente novamente em instantes.'); return; }
      if (!res.ok) throw new Error(res.statusText);
      render(await res.json());
    } catch (e) {
      el('updated').textContent = 'Sem conexão: tentando novamente…';
    }
  }

  function stop() {
    if (timer) { clearInterval(timer); timer = null; }
  }

  init();
  load();
  timer = setInterval(() => { if (!document.hidden) load(); }, REFRESH_MS);
})();

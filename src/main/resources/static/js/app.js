/* OmniDude NMS — SPA (Alpine.js + Cytoscape.js + uPlot). cy, EventSource e o gráfico ficam fora do estado reativo. */
'use strict';



// Padrões por tipo de checagem (o servidor valida e aplica os mesmos padrões quando o campo vem vazio).
const CHECK_TYPES = {
  ICMP: { label: 'Ping (ICMP)', port: null, timeout: 2000 },
  TCP: { label: 'Porta TCP', port: 22, timeout: 2000 },
  HTTP: { label: 'HTTP', port: 80, timeout: 5000 },
  HTTPS: { label: 'HTTPS', port: 443, timeout: 5000 },
  DNS: { label: 'DNS', port: 53, timeout: 2000 },
  SNMP: { label: 'SNMP (sysUpTime)', port: 161, timeout: 2000 },
};

function xsrf() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : '';
}

async function api(method, url, body) {
  const opts = { method, headers: { Accept: 'application/json' }, credentials: 'same-origin' };
  if (method !== 'GET') opts.headers['X-XSRF-TOKEN'] = xsrf();
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(url, opts);
  if (res.status === 401) {
    location.href = '/login.html';
    throw new Error('Sessão expirada');
  }
  if (!res.ok) {
    let data = {};
    try { data = await res.json(); } catch (e) { /* corpo vazio */ }
    const err = new Error(data.error || res.statusText);
    err.status = res.status;
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

function loadTrafficRange() {
  try { return localStorage.getItem('omnidude.trafficRange') || '24h'; } catch (e) { return '24h'; }
}

/** 1234567 → "1,23 Mb/s". Unidades decimais (como as velocidades de interface). */
function bpsText(bps) {
  if (bps == null) return '—';
  const units = [[1e9, 'Gb/s'], [1e6, 'Mb/s'], [1e3, 'kb/s']];
  for (const [f, u] of units) {
    if (bps >= f) return (bps / f).toLocaleString('pt-BR', { maximumFractionDigits: bps / f >= 100 ? 0 : (bps / f >= 10 ? 1 : 2) }) + ' ' + u;
  }
  return Math.round(bps) + ' b/s';
}

function loadRange() {
  try { return localStorage.getItem('omnidude.chartRange') || '1h'; } catch (e) { return '1h'; }
}

let cy = null;
let stream = null;

const EVENT_TYPE_LABELS = {
  DEVICE_DOWN: 'Dispositivo caiu', DEVICE_UP: 'Dispositivo voltou', DEVICE_WARNING: 'Dispositivo com atenção',
  CHECK_FAILED: 'Serviço falhou', CHECK_RECOVERED: 'Serviço recuperado', LINK_DOWN: 'Enlace caiu', LINK_UP: 'Enlace voltou',
  THRESHOLD: 'Limite ultrapassado', DISCOVERY: 'Descoberta', SYSTEM: 'Sistema',
};

const LINK_KINDS = { CABLE: 'Cabo', WIFI: 'Wi-Fi', VPN: 'VPN', INTERNET: 'Internet / WAN', OTHER: 'Outro' };

function nodeLabel(n) {
  if (n.virtualNode) return `☁ ${n.name}\n(${n.deduced ? 'sem SNMP · deduzido' : 'nó lógico'})`;
  const s = STATUS[n.status] || STATUS.UNKNOWN;
  const flags = (n.pinned ? '📌 ' : '') + (n.enabled ? '' : '⏸ ');
  return `${flags}${n.name}\n${s.glyph} ${n.enabled ? s.text : 'PAUSADO'}`;
}

function omniApp() {
  return {
    me: null,
    view: 'map',
    maps: [],
    mapId: null,
    map: null,
    devices: [],
    events: [],
    eventList: [],
    eventFilter: { severity: '', unacked: true },
    unacked: 0,
    dash: null,
    profiles: [],
    ifaces: [],
    identity: { macs: [], history: [] },
    newMac: '',
    identityMsg: '',
    locating: false,
    trafficIface: null,
    trafficRange: loadTrafficRange(),
    trafficStats: null,
    trafficEmpty: false,
    ifaceBusy: false,
    linkMode: false,
    linkSource: null,
    linksBusy: false,
    showLink: false,
    lf: {},
    lfIfs: { source: [], target: [] },
    lfError: '',
    disc: {
      step: 'form', jobs: [], job: null, hosts: [], selected: {}, names: {}, types: {}, mapId: '',
      openPortChecks: false, result: null, error: '', busy: false,
      form: { cidr: '', ports: '22, 80, 443, 3389', profileIds: [], confirmLarge: false, authorized: false },
    },
    selected: null,
    pinned: false,
    chartCheckId: null,
    chartRange: loadRange(),
    chartStats: null,
    chartEmpty: false,
    search: '',
    connected: false,
    toast: '',
    showDevice: false,
    df: {},
    dfError: '',
    showCheck: false,
    cf: {},
    cfError: '',
    cfBusy: false,
    showPublish: false,
    pub: { published: false, path: null, showStatus: true, showNames: true },
    pubError: '',
    pubBusy: false,
    pubCopied: false,
    showProfiles: false,
    pf: {},
    pfError: '',
    mustChange: false,
    pwd: { current: '', next: '', confirm: '', error: '' },
    settingsTab: 'notifications',
    channels: [],
    rules: [],
    notifLog: [],
    settingsItems: [],
    settingsDraft: {},
    users: [],
    backups: [],
    backupBusy: false,
    maintenance: false,
    showChannel: false,
    chf: { config: {}, secrets: {} },
    chfError: '',
    chTest: {},
    showRule: false,
    rlf: { eventTypes: [] },
    rlfError: '',
    showUser: false,
    usf: {},
    usfError: '',
    showReset: false,
    rsf: {},
    rsfError: '',
    eventTypeLabels: EVENT_TYPE_LABELS,
    typeLabels: TYPE_LABELS,
    linkKinds: LINK_KINDS,
    checkTypes: CHECK_TYPES,
    navItems: [
      { id: 'dashboard', label: 'Dashboard' },
      { id: 'map', label: 'Mapa' },
      { id: 'devices', label: 'Dispositivos' },
      { id: 'discovery', label: 'Descoberta' },
      { id: 'events', label: 'Eventos' },
      { id: 'settings', label: 'Configurações' },
    ],

    // ---------------------------------------------------------------- ciclo de vida
    async init() {
      try {
        this.me = await api('GET', '/api/me');
      } catch (e) {
        return;
      }
      if (this.me.mustChangePassword) {
        this.mustChange = true;
        return;
      }
      await this.start();
    },

    async start() {
      this.initCy();
      await this.reloadAll();
      this.connectStream();
      setInterval(() => this.tick(), 10000);
    },

    /** Atualização periódica do que não chega por SSE (latências do inspetor e do dashboard). */
    tick() {
      if (document.hidden) return;
      if (this.view === 'dashboard') this.loadDashboard();
      if (this.settingsTab !== 'adjustments' || this.view !== 'settings') this.summaryMaintenance();
      if (this.selected) {
        this.refreshSelected();
        this.loadInterfaces(this.selected.id);       // taxas atuais da lista de interfaces
        if (this.trafficIface && (this._ticks = (this._ticks || 0) + 1) % 6 === 0) this.loadTraffic();   // amostras novas a cada ~60 s
      }
    },

    async reloadAll() {
      const [maps, devices, events, summary, profiles] = await Promise.all([
        api('GET', '/api/maps'), api('GET', '/api/devices'),
        api('GET', '/api/events?limit=50'), api('GET', '/api/summary'), api('GET', '/api/snmp-profiles'),
      ]);
      this.maps = maps;
      this.devices = devices;
      this.events = events;
      this.profiles = profiles;
      this.unacked = summary.unackedEvents;
      this.maintenance = !!summary.maintenance;
      if (!this.mapId || !maps.some((m) => m.id === this.mapId)) this.mapId = maps[0] && maps[0].id;
      await this.loadMap(false);
      if (this.view === 'events') await this.loadEventList();
      if (this.view === 'dashboard') await this.loadDashboard();
      if (this.selected) await this.openDevice(this.selected.id, false);
    },

    // ---------------------------------------------------------------- tempo real (SSE)
    connectStream() {
      stream = new EventSource('/api/stream');
      let first = true;
      stream.addEventListener('hello', () => {
        this.connected = true;
        if (!first) this.reloadAll();   // reconectou: reconcilia o que foi perdido
        first = false;
      });
      stream.onerror = () => { this.connected = false; };
      stream.addEventListener('device-status', (ev) => this.onDeviceStatus(JSON.parse(ev.data)));
      stream.addEventListener('event', (ev) => this.onEvent(JSON.parse(ev.data)));
      stream.addEventListener('devices-changed', () => this.reloadAll());
      stream.addEventListener('discovery-progress', (ev) => this.onDiscoveryProgress(JSON.parse(ev.data)));
      stream.addEventListener('link-traffic', (ev) => this.onLinkTraffic(JSON.parse(ev.data)));
    },

    /** Taxas novas de enlaces (uma coleta de dispositivo): atualiza cor, espessura e rótulo das arestas. */
    onLinkTraffic(updates) {
      if (!this.map || !cy) return;
      cy.batch(() => {
        for (const u of updates) {
          const l = this.linkById(u.linkId);
          if (!l) continue;
          l.traffic = u.traffic;
          const e = cy.getElementById('l' + l.id);
          if (e.nonempty()) e.data({ ...this.trafficData(l), label: this.edgeLabel(l) });
        }
      });
      this.refreshEdges();
    },

    onDeviceStatus(msg) {
      const d = this.devices.find((x) => x.id === msg.deviceId);
      if (d) { d.status = msg.status; d.statusSince = msg.since; }
      if (cy) {
        const n = cy.getElementById('d' + msg.deviceId);
        if (n.nonempty()) {
          n.data('status', msg.status);
          n.data('label', nodeLabel({ ...n.data(), status: msg.status }));
        }
      }
      this.refreshEdges();
      if (this.selected && this.selected.id === msg.deviceId) this.refreshSelected();
      this.scheduleDashboard();
    },

    onEvent(e) {
      this.events.unshift(e);
      if (this.events.length > 100) this.events.pop();
      if (!e.acknowledged) this.unacked++;
      if (this.view === 'events') this.loadEventList();
      if (e.severity !== 'INFO') this.flash(e.message);
      this.scheduleDashboard();
    },

    flash(msg) {
      this.toast = msg;
      clearTimeout(this._toast);
      this._toast = setTimeout(() => { this.toast = ''; }, 4500);
    },

    // ---------------------------------------------------------------- mapa
    initCy() {
      cy = cytoscape({
        container: this.$refs.cy,
        elements: [],
        minZoom: 0.2,
        maxZoom: 3,
        style: [
          {
            selector: 'node',
            style: {
              width: 54, height: 54, shape: 'round-rectangle', 'background-color': '#1e293b',
              'background-image': (n) => iconUri(n.data('type')), 'background-fit': 'none',
              'background-width': '60%', 'background-height': '60%',
              'border-width': 4, 'border-color': STATUS.UNKNOWN.color,
              label: 'data(label)', color: '#e2e8f0', 'font-size': 12, 'text-wrap': 'wrap',
              'text-valign': 'bottom', 'text-margin-y': 8, 'text-background-color': '#020617',
              'text-background-opacity': 0.7, 'text-background-padding': 2,
            },
          },
          { selector: 'node[status = "UP"]', style: { 'border-color': STATUS.UP.color } },
          { selector: 'node[status = "WARNING"]', style: { 'border-color': STATUS.WARNING.color } },
          { selector: 'node[status = "DOWN"]', style: { 'border-color': STATUS.DOWN.color, 'border-style': 'double', 'border-width': 6 } },
          { selector: 'node[?paused]', style: { opacity: 0.5 } },
          { selector: 'node[?virtual]', style: { 'border-style': 'dashed', 'border-color': '#94a3b8', 'border-width': 3 } },
          { selector: 'node:selected', style: { 'overlay-color': '#38bdf8', 'overlay-opacity': 0.25, 'overlay-padding': 8 } },
          { selector: 'node.dim', style: { opacity: 0.2 } },
          {
            selector: 'edge',
            style: {
              width: 3, 'line-color': '#64748b', 'curve-style': 'bezier', label: 'data(label)', 'font-size': 10,
              color: '#94a3b8', 'text-background-color': '#020617', 'text-background-opacity': 0.8,
              'text-background-padding': 2, 'text-rotation': 'autorotate',
            },
          },
          // O estado também usa o traço (não só a cor): sugerido = tracejado, com falha = pontilhado.
          { selector: 'edge[state = "SUGGESTED"]', style: { 'line-color': '#a78bfa', 'line-style': 'dashed', 'line-dash-pattern': [8, 5] } },
          { selector: 'edge[state = "SUGGESTED"][origin = "INFERRED"]', style: { 'line-color': '#64748b', 'line-dash-pattern': [2, 6], opacity: 0.8 } },
          // Natureza do enlace: o traço também diferencia (não só a cor).
          { selector: 'edge[kind = "VPN"]', style: { 'line-color': '#14b8a6', 'line-style': 'dashed', 'line-dash-pattern': [10, 3, 2, 3] } },
          { selector: 'edge[kind = "INTERNET"]', style: { 'line-color': '#38bdf8', 'line-style': 'dashed', 'line-dash-pattern': [14, 6] } },
          { selector: 'edge[kind = "WIFI"]', style: { 'line-color': '#a3e635', 'line-style': 'dotted' } },
          // Utilização: espessura e cor (o rótulo também traz o percentual, para não depender só da cor).
          { selector: 'edge[?hasTraffic]', style: { 'line-color': '#22c55e', width: 'mapData(util, 0, 100, 3, 10)' } },
          { selector: 'edge[util >= 50]', style: { 'line-color': '#f59e0b' } },
          { selector: 'edge[util >= 80]', style: { 'line-color': '#f97316' } },
          { selector: 'edge[?down]', style: { 'line-color': '#ef4444', 'line-style': 'dotted' } },
          { selector: 'edge:selected', style: { 'overlay-color': '#38bdf8', 'overlay-opacity': 0.25, 'overlay-padding': 6 } },
        ],
      });
      // O contêiner muda de largura quando o inspetor abre/fecha: o canvas precisa acompanhar.
      new ResizeObserver(() => cy && cy.resize()).observe(this.$refs.cy);
      cy.on('tap', 'node', (evt) => {
        const id = evt.target.data('deviceId');
        if (this.linkMode) this.pickLinkNode(id); else this.openDevice(id, false);
      });
      cy.on('tap', 'edge', (evt) => this.openLinkForm(this.linkById(evt.target.data('linkId'))));
      cy.on('tap', (evt) => { if (evt.target === cy) this.closeDrawer(); });
      cy.on('dragfree', 'node', (evt) => {
        const n = evt.target;
        const p = n.position();
        api('PUT', `/api/maps/${this.mapId}/nodes/${n.data('deviceId')}`,
          { x: p.x, y: p.y, pinned: !!n.data('pinned') })
          .catch((e) => this.flash('Não foi possível salvar a posição: ' + e.message));
      });
    },

    async loadMap(fitAfter) {
      if (!this.mapId) return;
      this.map = await api('GET', '/api/maps/' + this.mapId);
      const ids = new Set(this.map.nodes.map((n) => 'd' + n.deviceId));
      const hadNodes = cy.nodes().length > 0;
      const addedNodes = [];
      cy.batch(() => {
        cy.nodes().filter((n) => !ids.has(n.id())).remove();
        for (const n of this.map.nodes) {
          const data = {
            id: 'd' + n.deviceId, deviceId: n.deviceId, name: n.name, type: n.type, status: n.status,
            pinned: n.pinned, paused: !n.enabled, enabled: n.enabled, virtual: !!n.virtualNode, deduced: !!n.deduced,
          };
          data.label = nodeLabel({ ...n });
          const el = cy.getElementById(data.id);
          if (el.empty()) {
            const added = cy.add({ group: 'nodes', data, position: { x: n.x, y: n.y } });
            addedNodes.push(added);
            if (n.pinned) added.ungrabify();
          } else {
            el.data(data);
            n.pinned ? el.ungrabify() : el.grabify();
          }
        }
      });
      this.syncEdges();
      // Reenquadra no primeiro carregamento ou quando um nó recém-chegado cai fora da área visível.
      const ext = cy.extent();
      const offscreen = addedNodes.some((el) => {
        const p = el.position();
        return p.x < ext.x1 || p.x > ext.x2 || p.y < ext.y1 || p.y > ext.y2;
      });
      if (fitAfter || !hadNodes || offscreen) this.fit();
      this.applySearch();
    },

    fit() {
      if (!cy || !cy.nodes().length) return;
      cy.fit(cy.nodes(), 60);
      if (cy.zoom() > 1.5) { cy.zoom(1.5); cy.center(cy.nodes()); }   // 1 nó só não deve virar um bloco gigante
    },

    /**
     * Layout automático (força): aproxima o que está ligado. Nós fixados ficam onde estão; as novas posições são
     * gravadas (em lotes, para não abrir centenas de conexões de uma vez).
     */
    async autoLayout() {
      if (!cy || !cy.nodes().length) return;
      if (!confirm('Reorganizar automaticamente os dispositivos não fixados deste mapa? As posições atuais serão substituídas.')) return;
      const pinned = cy.nodes('[?pinned]');
      pinned.lock();
      const layout = cy.layout({
        name: 'cose', animate: false, randomize: true, fit: false, padding: 60,
        nodeRepulsion: () => 14000, idealEdgeLength: () => 160, edgeElasticity: () => 80, gravity: 0.4, numIter: 1200,
      });
      layout.run();
      pinned.unlock();
      this.fit();
      const moved = cy.nodes().filter((n) => !n.data('pinned')).toArray();
      try {
        for (let i = 0; i < moved.length; i += 20) {
          await Promise.all(moved.slice(i, i + 20).map((n) => {
            const p = n.position();
            return api('PUT', `/api/maps/${this.mapId}/nodes/${n.data('deviceId')}`, { x: p.x, y: p.y, pinned: false });
          }));
        }
      } catch (e) { this.flash('Não foi possível salvar o layout: ' + e.message); }
    },

    focusNode(deviceId) {
      const n = cy && cy.getElementById('d' + deviceId);
      if (n && n.nonempty()) {
        cy.nodes(':selected').unselect();
        n.select();
        cy.animate({ center: { eles: n }, zoom: Math.max(cy.zoom(), 1) }, { duration: 250 });
      }
    },

    async newMap() {
      const name = (prompt('Nome do novo mapa:') || '').trim();
      if (!name) return;
      try {
        const m = await api('POST', '/api/maps', { name });
        this.maps.push(m);
        this.mapId = m.id;
        await this.loadMap(true);
      } catch (e) { this.flash(e.message); }
    },

    // ---------------------------------------------------------------- inspetor
    async openDevice(id, switchToMap) {
      if (switchToMap && this.view !== 'map') {
        this.view = 'map';
        await this.$nextTick();
        cy.resize();
      }
      let d;
      try {
        d = await api('GET', '/api/devices/' + id);
      } catch (e) {
        this.selected = null;
        return;
      }
      const switched = !this.selected || this.selected.id !== id;
      this.selected = d;
      const node = cy.getElementById('d' + id);
      this.pinned = node.nonempty() && !!node.data('pinned');
      if (switched || !d.checks.some((c) => c.id === this.chartCheckId)) {
        const main = d.checks.find((c) => c.type === 'ICMP') || d.checks[0];
        this.chartCheckId = main ? main.id : null;
      }
      if (switched) this.closeTraffic();
      await Promise.all([this.loadChart(), this.loadInterfaces(id), this.loadIdentity(id)]);
      if (switchToMap) this.focusNode(id);
    },

    /** Atualiza o inspetor sem mexer na seleção (usado pelo temporizador e por mudanças de status). */
    async refreshSelected() {
      if (!this.selected) return;
      try {
        this.selected = await api('GET', '/api/devices/' + this.selected.id);
        await this.loadChart();
      } catch (e) { /* dispositivo removido: reloadAll cuida */ }
    },

    closeDrawer() {
      this.selected = null;
      this.ifaces = [];
      this.identity = { macs: [], history: [] };
      this.identityMsg = '';
      this.chartCheckId = null;
      LatencyChart.destroy();
      this.closeTraffic();
      if (cy) cy.nodes(':selected').unselect();
    },

    async togglePin() {
      const n = cy.getElementById('d' + this.selected.id);
      if (n.empty()) return;
      const pinned = !n.data('pinned');
      const p = n.position();
      await api('PUT', `/api/maps/${this.mapId}/nodes/${this.selected.id}`, { x: p.x, y: p.y, pinned });
      this.pinned = pinned;
      n.data('pinned', pinned);
      n.data('label', nodeLabel(n.data()));
      pinned ? n.ungrabify() : n.grabify();
    },

    async toggleEnabled() {
      const d = this.selected;
      try {
        await api('PUT', '/api/devices/' + d.id, {
          name: d.name, primaryIp: d.primaryIp, deviceType: d.deviceType, hostname: d.hostname,
          notes: d.notes, snmpProfileId: d.snmpProfileId || null, enabled: !d.enabled,
        });
        await this.reloadAll();
      } catch (e) { this.flash(e.message); }
    },

    async removeSelected() {
      const d = this.selected;
      if (!confirm(`Excluir "${d.name}" (${d.primaryIp})? Histórico e checagens serão removidos.`)) return;
      try {
        await api('DELETE', '/api/devices/' + d.id);
        this.closeDrawer();
        await this.reloadAll();
      } catch (e) { this.flash(e.message); }
    },

    // ---------------------------------------------------------------- gráfico de latência
    chartCheckName() {
      const c = this.selected && (this.selected.checks || []).find((x) => x.id === this.chartCheckId);
      return c ? '· ' + c.name : '';
    },

    async selectCheck(id) {
      this.chartCheckId = id;
      await this.loadChart();
    },

    async setRange(r) {
      this.chartRange = r;
      try { localStorage.setItem('omnidude.chartRange', r); } catch (e) { /* armazenamento indisponível */ }
      await this.loadChart();
    },

    async loadChart() {
      if (!this.chartCheckId) { LatencyChart.destroy(); return; }
      const seq = (this._chartSeq = (this._chartSeq || 0) + 1);
      let series;
      try {
        series = await api('GET', `/api/checks/${this.chartCheckId}/results?range=${this.chartRange}`);
      } catch (e) { return; }
      if (seq !== this._chartSeq) return;          // o usuário já trocou de serviço/período
      this.chartStats = series.stats;
      this.chartEmpty = series.points.length === 0;
      await this.$nextTick();                      // o contêiner precisa estar visível para medir a largura
      if (seq === this._chartSeq) LatencyChart.render(series);
    },

    statsText() {
      const s = this.chartStats;
      if (!s || !s.samples) return '';
      const parts = [`disponibilidade ${s.availabilityPct}%`];
      if (s.avgMs != null) parts.push(`média ${s.avgMs} ms`);
      if (s.maxMs != null) parts.push(`máx ${s.maxMs} ms`);
      parts.push(`${s.samples} amostras`);
      return parts.join(' · ');
    },

    // ---------------------------------------------------------------- dispositivo (novo/editar)
    openDeviceForm(d) {
      this.df = d
        ? { id: d.id, name: d.name, primaryIp: d.primaryIp, deviceType: d.deviceType, hostname: d.hostname || '',
            notes: d.notes || '', snmpProfileId: d.snmpProfileId ? String(d.snmpProfileId) : '', virtualNode: !!d.virtualNode, logical: false }
        : { id: null, name: '', primaryIp: '', deviceType: 'ROUTER', hostname: '', notes: '', snmpProfileId: '', logical: false };
      this.dfError = '';
      this.showDevice = true;
      // O modal só fica visível após o ciclo de renderização; um nextTick não basta após um clique real.
      setTimeout(() => this.$refs.deviceName && this.$refs.deviceName.focus(), 60);
    },

    async saveDevice() {
      const f = this.df;
      const body = {
        name: f.name, primaryIp: f.primaryIp, deviceType: f.deviceType, hostname: f.hostname, notes: f.notes,
        snmpProfileId: f.snmpProfileId ? Number(f.snmpProfileId) : null,
        enabled: f.id ? this.selected.enabled : true, logical: !f.id && f.logical,
      };
      try {
        const d = f.id ? await api('PUT', '/api/devices/' + f.id, body) : await api('POST', '/api/devices', body);
        this.showDevice = false;
        await this.reloadAll();
        if (!f.id) { this.focusNode(d.id); this.openDevice(d.id, false); }
      } catch (e) { this.dfError = e.message; }
    },

    // ---------------------------------------------------------------- serviços (checagens)
    /** Tipos oferecidos ao criar: ICMP só se o dispositivo ainda não tem (é único por dispositivo). */
    createTypes() {
      const hasIcmp = this.selected && (this.selected.checks || []).some((c) => c.type === 'ICMP');
      return Object.keys(CHECK_TYPES).filter((t) => !(t === 'ICMP' && hasIcmp));
    },

    availableCheckTypes() {
      return this.cf.id ? [this.cf.type] : this.createTypes();
    },

    snmpDefaultPort() {
      const p = this.profiles.find((x) => this.selected && x.id === this.selected.snmpProfileId);
      return p ? p.port : 161;
    },

    blankCheck(type) {
      const m = CHECK_TYPES[type];
      return {
        id: null, type, name: '', port: type === 'SNMP' ? this.snmpDefaultPort() : m.port,
        path: '', method: 'GET', expectedStatus: '', contains: '', verifyTls: true, dnsName: '', recordType: 'A',
        intervalS: 30, timeoutMs: m.timeout, warnLatencyMs: '', failThreshold: 3, recoverThreshold: 1, enabled: true,
      };
    },

    openCheckForm(c) {
      this.cfError = '';
      if (c) {
        const p = c.params || {};
        this.cf = {
          id: c.id, type: c.type, name: c.name, port: c.port, path: p.path || '', method: p.method || 'GET',
          expectedStatus: p.expectedStatus || '', contains: p.contains || '', verifyTls: p.verifyTls !== 'false',
          dnsName: p.name || '', recordType: p.recordType || 'A', intervalS: c.intervalS, timeoutMs: c.timeoutMs,
          warnLatencyMs: c.warnLatencyMs == null ? '' : c.warnLatencyMs, failThreshold: c.failThreshold,
          recoverThreshold: c.recoverThreshold, enabled: c.enabled,
        };
      } else {
        const types = this.createTypes();   // não usar availableCheckTypes(): lê o cf anterior, ainda em modo edição
        this.cf = this.blankCheck(types.includes('TCP') ? 'TCP' : types[0]);
      }
      this.showCheck = true;
    },

    onCheckTypeChange() {
      const m = CHECK_TYPES[this.cf.type];
      this.cf.port = this.cf.type === 'SNMP' ? this.snmpDefaultPort() : m.port;
      this.cf.timeoutMs = m.timeout;
    },

    checkRequest() {
      const f = this.cf;
      const params = {};
      if (f.type === 'HTTP' || f.type === 'HTTPS') {
        if (f.path.trim()) params.path = f.path.trim();
        if (f.method !== 'GET') params.method = f.method;
        if (f.expectedStatus.trim()) params.expectedStatus = f.expectedStatus.trim();
        if (f.method === 'GET' && f.contains.trim()) params.contains = f.contains.trim();
        if (f.type === 'HTTPS' && !f.verifyTls) params.verifyTls = 'false';
      }
      if (f.type === 'DNS') {
        if (f.dnsName.trim()) params.name = f.dnsName.trim();
        if (f.recordType !== 'A') params.recordType = f.recordType;
      }
      const num = (v) => (v === '' || v == null ? null : Number(v));
      return {
        type: f.type, name: f.name.trim() || null,
        port: f.type === 'ICMP' ? null : num(f.port), params,
        intervalS: num(f.intervalS), timeoutMs: num(f.timeoutMs), warnLatencyMs: num(f.warnLatencyMs),
        failThreshold: num(f.failThreshold), recoverThreshold: num(f.recoverThreshold), enabled: f.enabled,
      };
    },

    async saveCheck() {
      this.cfBusy = true;
      this.cfError = '';
      try {
        const body = this.checkRequest();
        const c = this.cf.id
          ? await api('PUT', '/api/checks/' + this.cf.id, body)
          : await api('POST', `/api/devices/${this.selected.id}/checks`, body);
        this.showCheck = false;
        this.chartCheckId = c.id;
        await this.refreshSelected();
      } catch (e) { this.cfError = e.message; }
      this.cfBusy = false;
    },

    async runCheckNow(c) {
      c.running = true;
      try {
        const r = await api('POST', `/api/checks/${c.id}/run`);
        this.flash(!r.executed ? r.detail
          : r.success ? `${c.name}: OK${r.responseMs != null ? ' · ' + r.responseMs + ' ms' : ''}`
            : `${c.name}: falhou${r.detail ? ' (' + r.detail + ')' : ''}`);
      } catch (e) { this.flash(e.message); }
      await this.refreshSelected();
      setTimeout(() => this.loadChart(), 1300);   // a amostra só aparece após o lote de 1 s do MetricsWriter
    },

    async removeCheck(c) {
      if (!confirm(`Excluir o serviço "${c.name}"? O histórico dele também será apagado.`)) return;
      try {
        await api('DELETE', '/api/checks/' + c.id);
        await this.refreshSelected();
      } catch (e) { this.flash(e.message); }
    },

    checkLabel(c) {
      return c.type + (c.port ? ':' + c.port : '');
    },

    // ---------------------------------------------------------------- publicação do mapa
    mapPublished() {
      const m = this.maps.find((x) => x.id === this.mapId);
      return !!(m && m.published);
    },

    currentMapName() {
      const m = this.maps.find((x) => x.id === this.mapId);
      return m ? '· ' + m.name : '';
    },

    publicUrl() {
      return this.pub.path ? location.origin + this.pub.path : '';
    },

    isLoopback() {
      return ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
    },

    async openPublish() {
      this.pubError = '';
      this.pubCopied = false;
      try {
        this.pub = await api('GET', `/api/maps/${this.mapId}/publication`);
        this.showPublish = true;
      } catch (e) { this.flash(e.message); }
    },

    async savePublish(patch) {
      this.pubBusy = true;
      this.pubError = '';
      try {
        this.pub = await api('PUT', `/api/maps/${this.mapId}/publication`, patch);
        this.maps = await api('GET', '/api/maps');          // atualiza o selo "Publicado" do botão
      } catch (e) { this.pubError = e.message; }
      this.pubBusy = false;
    },

    togglePublish() {
      if (this.pub.published && !confirm('Despublicar o mapa? O link atual deixa de funcionar.')) return Promise.resolve();
      return this.savePublish({ published: !this.pub.published });
    },

    regeneratePublish() {
      if (!confirm('Gerar um novo link? O link atual deixa de funcionar.')) return Promise.resolve();
      return this.savePublish({ regenerate: true });
    },

    async copyPublicUrl() {
      try {
        await navigator.clipboard.writeText(this.publicUrl());
        this.pubCopied = true;
        setTimeout(() => { this.pubCopied = false; }, 2000);
      } catch (e) { this.pubError = 'Não foi possível copiar: selecione o link e copie manualmente.'; }
    },

    // ---------------------------------------------------------------- perfis SNMP
    async loadProfiles() {
      this.profiles = await api('GET', '/api/snmp-profiles');
    },

    async openProfiles() {
      await this.loadProfiles();
      this.newProfile();
      this.showProfiles = true;
    },

    newProfile() {
      this.pf = { id: null, name: '', version: '2c', community: '', port: 161, retries: 1, username: '',
        securityLevel: 'AUTH_PRIV', authProtocol: 'SHA256', authPassword: '', hasAuthPassword: false,
        privProtocol: 'AES128', privPassword: '', hasPrivPassword: false };
      this.pfError = '';
    },

    editProfile(p) {
      this.pf = { id: p.id, name: p.name, version: p.version, community: '', port: p.port, retries: p.retries,
        username: p.username || '', securityLevel: p.securityLevel || 'AUTH_PRIV', authProtocol: p.authProtocol || 'SHA256',
        authPassword: '', hasAuthPassword: !!p.hasAuthPassword, privProtocol: p.privProtocol || 'AES128', privPassword: '',
        hasPrivPassword: !!p.hasPrivPassword };
      this.pfError = '';
    },

    async saveProfile() {
      const f = this.pf;
      const body = { name: f.name, version: f.version, community: f.community || null, port: f.port, retries: f.retries };
      if (f.version === '3') {
        Object.assign(body, { community: null, username: f.username, securityLevel: f.securityLevel,
          authProtocol: f.authProtocol, authPassword: f.authPassword || null,
          privProtocol: f.privProtocol, privPassword: f.privPassword || null });
      }
      try {
        if (f.id) await api('PUT', '/api/snmp-profiles/' + f.id, body);
        else await api('POST', '/api/snmp-profiles', body);
        await this.loadProfiles();
        this.newProfile();
      } catch (e) { this.pfError = e.message; }
    },

    async removeProfile(p) {
      if (!confirm(`Excluir o perfil "${p.name}"? Dispositivos que o usam ficarão sem perfil SNMP.`)) return;
      try {
        await api('DELETE', '/api/snmp-profiles/' + p.id);
        await this.loadProfiles();
        await this.refreshSelected();
      } catch (e) { this.pfError = e.message; }
    },

    profileSummary(p) {
      const base = p.version === '3'
        ? `v3 · ${p.username} · ${{ NO_AUTH_NO_PRIV: 'sem autenticação', AUTH_NO_PRIV: 'autenticação', AUTH_PRIV: 'autenticação + criptografia' }[p.securityLevel] || p.securityLevel}`
        : 'v' + p.version;
      return `${base} · porta ${p.port} · ${p.retries} retentativa(s)`;
    },

    profileName(id) {
      if (!id) return '(nenhum)';
      const p = this.profiles.find((x) => x.id === id);
      return p ? `${p.name} · v${p.version}` : '#' + id;
    },

    // ---------------------------------------------------------------- dashboard
    async loadDashboard() {
      try { this.dash = await api('GET', '/api/dashboard'); } catch (e) { /* mantém o painel anterior */ }
    },

    scheduleDashboard() {
      if (this.view !== 'dashboard') return;
      clearTimeout(this._dash);
      this._dash = setTimeout(() => this.loadDashboard(), 800);
    },

    // ---------------------------------------------------------------- enlaces
    linkById(id) {
      return this.map && (this.map.links || []).find((l) => l.id === id);
    },

    edgeLabel(l) {
      let text = l.label;
      if (!text && (l.sourceInterface || l.targetInterface)) text = `${l.sourceInterface || '?'} ⇄ ${l.targetInterface || '?'}`;
      const kind = ['VPN', 'INTERNET', 'WIFI'].includes(l.kind) ? LINK_KINDS[l.kind] + (text ? ' · ' : '') : '';
      text = kind + (text || '');
      const t = l.traffic;
      const util = t && t.utilizationPct != null ? ` · ${t.utilizationPct.toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%` : '';
      const tag = l.state !== 'SUGGESTED' ? '' : (l.origin === 'INFERRED' ? ' (inferido)' : ` (sugerido · ${l.origin})`);
      return ((text || '') + util + tag).trim();
    },

    /** Dados da aresta derivados do tráfego do enlace; sem taxa recente a aresta volta ao estilo neutro. */
    trafficData(l) {
      const t = l.traffic;
      const has = !!t && t.utilizationPct != null;
      return { hasTraffic: has, util: has ? Math.min(t.utilizationPct, 100) : null, operDown: !!(t && t.operDown) };
    },

    syncEdges() {
      const links = this.map.links || [];
      const ids = new Set(links.map((l) => 'l' + l.id));
      cy.batch(() => {
        cy.edges().filter((e) => !ids.has(e.id())).remove();
        for (const l of links) {
          const el = cy.getElementById('l' + l.id);
          const data = { linkId: l.id, state: l.state, origin: l.origin, kind: l.kind || null, label: this.edgeLabel(l), ...this.trafficData(l) };
          if (el.empty()) {
            cy.add({ group: 'edges', data: { id: 'l' + l.id, source: 'd' + l.sourceDeviceId, target: 'd' + l.targetDeviceId, ...data } });
          } else {
            el.data(data);          // source/target de uma aresta não mudam (o servidor recusa trocar os dispositivos)
          }
        }
      });
      this.refreshEdges();
    },

    /** Um enlace aparece "com falha" quando qualquer ponta está DOWN ou a interface de uma ponta está down. */
    refreshEdges() {
      if (!cy) return;
      cy.edges().forEach((e) => {
        e.data('down', e.source().data('status') === 'DOWN' || e.target().data('status') === 'DOWN' || !!e.data('operDown'));
      });
    },

    toggleLinkMode() {
      if (this.linkMode) { this.cancelLinkMode(); return; }
      this.closeDrawer();
      this.linkMode = true;
      this.linkSource = null;
      this.flash('Clique no dispositivo de origem e depois no de destino (Esc cancela)');
    },

    cancelLinkMode() {
      this.linkMode = false;
      this.linkSource = null;
      if (cy) cy.nodes(':selected').unselect();
    },

    async pickLinkNode(deviceId) {
      if (!this.isAdmin) return;
      if (!this.linkSource) {
        this.linkSource = deviceId;
        cy.getElementById('d' + deviceId).select();
        return;
      }
      if (this.linkSource === deviceId) { this.linkSource = null; cy.nodes(':selected').unselect(); return; }
      const source = this.linkSource;
      this.cancelLinkMode();
      await this.openLinkForm({ sourceDeviceId: source, targetDeviceId: deviceId, state: null, origin: 'MANUAL' });
    },

    async openLinkForm(link) {
      if (!link) return;
      this.lf = {
        id: link.id || null, sourceDeviceId: link.sourceDeviceId || null, targetDeviceId: link.targetDeviceId || null,
        sourceInterfaceId: link.sourceInterfaceId ? String(link.sourceInterfaceId) : '',
        targetInterfaceId: link.targetInterfaceId ? String(link.targetInterfaceId) : '',
        label: link.label || '', kind: link.kind || '', state: link.state, origin: link.origin,
        evidence: link.evidence || '', traffic: link.traffic || null,
      };
      await this.loadLinkInterfaces();      // as interfaces vêm antes de abrir o modal: o select precisa das opções ao receber o valor
      this.lfError = '';
      this.showLink = true;
    },

    deviceName(id) {
      const d = this.devices.find((x) => x.id === id);
      return d ? d.name : (id ? '#' + id : '…');
    },

    /** Interfaces dos dois lados (um dispositivo lógico ou sem SNMP simplesmente não tem). */
    async loadLinkInterfaces() {
      const get = async (id) => {
        const d = this.devices.find((x) => x.id === id);
        if (!id || (d && d.virtualNode)) return [];
        try { return await api('GET', `/api/devices/${id}/interfaces`); } catch (e) { return []; }
      };
      const [source, target] = await Promise.all([get(this.lf.sourceDeviceId), get(this.lf.targetDeviceId)]);
      this.lfIfs = { source, target };
    },

    async pickLinkDevice(side, value) {
      this.lf[side + 'DeviceId'] = value ? Number(value) : null;
      this.lf[side + 'InterfaceId'] = '';
      await this.loadLinkInterfaces();
    },

    /** "Ligar a…" no inspetor: abre o formulário com este dispositivo como origem e o destino a escolher. */
    startLinkFrom(deviceId) {
      return this.openLinkForm({ sourceDeviceId: deviceId, targetDeviceId: null, state: null, origin: 'MANUAL' });
    },

    async saveLink() {
      const f = this.lf;
      if (!f.sourceDeviceId || !f.targetDeviceId) { this.lfError = 'Escolha os dois dispositivos'; return; }
      const num = (v) => (v === '' || v == null ? null : Number(v));
      const body = { sourceDeviceId: f.sourceDeviceId, targetDeviceId: f.targetDeviceId,
        sourceInterfaceId: num(f.sourceInterfaceId), targetInterfaceId: num(f.targetInterfaceId),
        label: f.label.trim() || null, kind: f.kind || null };
      try {
        if (f.id) await api('PUT', '/api/links/' + f.id, body); else await api('POST', '/api/links', body);
        this.showLink = false;
        await this.loadMap(false);
      } catch (e) { this.lfError = e.message; }
    },

    async confirmLink() {
      try {
        await api('POST', `/api/links/${this.lf.id}/confirm`);
        this.showLink = false;
        await this.loadMap(false);
      } catch (e) { this.lfError = e.message; }
    },

    async deleteLink() {
      const suggested = this.lf.state === 'SUGGESTED';
      const msg = suggested ? 'Descartar esta sugestão? Ela não voltará a ser sugerida.' : 'Excluir este enlace?';
      if (!confirm(msg)) return;
      try {
        await api('DELETE', '/api/links/' + this.lf.id);
        this.showLink = false;
        await this.loadMap(false);
      } catch (e) { this.lfError = e.message; }
    },

    /** Lê o LLDP de todos os equipamentos SNMP e propõe enlaces. */
    async detectLinks() {
      this.linksBusy = true;
      try {
        const r = await api('POST', '/api/topology/refresh');
        await this.loadMap(false);
        await this.reloadAll();
        const inf = r.inference || {};
        const parts = [`${r.devices} equipamento(s) lido(s)`];
        const found = r.created + (inf.fdbLinks || 0) + (inf.inferredLinks || 0) + (inf.gatewayLinks || 0);
        parts.push(`${found} enlace(s) sugerido(s)`);
        if (r.created) parts.push(`${r.created} por vizinho (LLDP/CDP/MNDP)`);
        if (inf.fdbLinks) parts.push(`${inf.fdbLinks} por FDB`);
        if (inf.inferredLinks || inf.gatewayLinks) parts.push(`${(inf.inferredLinks || 0) + (inf.gatewayLinks || 0)} inferido(s)`);
        if (inf.virtualNodes) parts.push(`${inf.virtualNodes} switch/hub sem SNMP deduzido(s)`);
        if (inf.macsFilled) parts.push(`${inf.macsFilled} MAC(s) preenchido(s)`);
        if (inf.staleRemoved) parts.push(`${inf.staleRemoved} sugestão(ões) antiga(s) removida(s)`);
        if (r.rejected) parts.push(`${r.rejected} já rejeitado(s)`);
        if (inf.newHosts && inf.newHosts.length) parts.push(`${inf.newHosts.length} host(s) no ARP fora do inventário (ex.: ${inf.newHosts[0].ip})`);
        if (r.errors.length) parts.push(`${r.errors.length} falha(s): ${r.errors[0]}`);
        (inf.notes || []).forEach((n) => parts.push(n));
        this.flash(parts.join(' · '));
      } catch (e) { this.flash(e.message); }
      this.linksBusy = false;
    },

    suggestedCount() {
      return ((this.map && this.map.links) || []).filter((l) => l.state === 'SUGGESTED').length;
    },

    /** Confirma todas as sugestões do mapa (uma a uma, como se o usuário clicasse em cada). */
    async confirmAllSuggested() {
      const list = ((this.map && this.map.links) || []).filter((l) => l.state === 'SUGGESTED');
      if (!list.length || !confirm(`Confirmar ${list.length} enlace(s) sugerido(s)? Sugestões inferidas (sem evidência direta) também serão confirmadas.`)) return;
      this.linksBusy = true;
      let ok = 0;
      for (const l of list) {
        try { await api('POST', `/api/links/${l.id}/confirm`); ok++; } catch (e) { /* segue com os demais */ }
      }
      await this.loadMap(false);
      this.flash(`${ok} de ${list.length} enlace(s) confirmado(s)`);
      this.linksBusy = false;
    },

    originText(o) {
      return { LLDP: 'LLDP', CDP: 'CDP', MNDP: 'MNDP', FDB: 'tabela de encaminhamento (FDB)', INFERRED: 'inferido', MANUAL: 'manual' }[o] || o;
    },

    // ---------------------------------------------------------------- interfaces (inspetor)
    async loadInterfaces(id) {
      try { this.ifaces = await api('GET', `/api/devices/${id}/interfaces`); } catch (e) { this.ifaces = []; }
      if (this.trafficIface && !this.ifaces.some((i) => i.id === this.trafficIface.id)) this.closeTraffic();
    },

    speedText(bps) {
      if (!bps) return '';
      if (bps >= 1e9) return bps / 1e9 + ' Gbps';
      if (bps >= 1e6) return bps / 1e6 + ' Mbps';
      return Math.round(bps / 1e3) + ' kbps';
    },

    rateText(i) {
      if (i.inBps != null || i.outBps != null) return `↓ ${bpsText(i.inBps)}  ↑ ${bpsText(i.outBps)}`;
      return i.rateTs ? 'aguardando 2ª leitura…' : 'sem coleta ainda';
    },

    linkTrafficText(lf) {
      const t = lf.traffic;
      if (!t) return '';
      const parts = [];
      if (t.txBps != null || t.rxBps != null) {
        parts.push(`${this.deviceName(lf.sourceDeviceId)} → ${this.deviceName(lf.targetDeviceId)}: ${bpsText(t.txBps)}`);
        parts.push(`${this.deviceName(lf.targetDeviceId)} → ${this.deviceName(lf.sourceDeviceId)}: ${bpsText(t.rxBps)}`);
      }
      if (t.utilizationPct != null) parts.push(`utilização ${t.utilizationPct.toLocaleString('pt-BR')}% de ${this.speedText(t.speedBps)}`);
      if (t.operDown) parts.push('interface down');
      return parts.join(' · ');
    },

    // ---------------------------------------------------------------- identidade (MACs, histórico de IP, DHCP)
    async loadIdentity(id) {
      try { this.identity = await api('GET', `/api/devices/${id}/identity`); } catch (e) { this.identity = { macs: [], history: [] }; }
    },

    reasonText(r) {
      return { AUTO: 'automático (pelo MAC)', DISCOVERY: 'confirmado na descoberta', MANUAL: 'edição manual' }[r] || r;
    },

    sourceText(s) {
      return { MANUAL: 'informado', DEVICE: 'cadastro', ARP: 'ARP', IFACE: 'interface', DISCOVERY: 'descoberta' }[s] || s;
    },

    async addMac() {
      const mac = this.newMac.trim();
      if (!mac) return;
      try {
        this.identity.macs = await api('POST', `/api/devices/${this.selected.id}/macs`, { mac });
        this.newMac = '';
        this.identityMsg = '';
      } catch (e) { this.identityMsg = e.message; }
    },

    async removeMac(m) {
      if (!confirm(`Remover o MAC ${m.mac} deste dispositivo?`)) return;
      try {
        this.identity.macs = await api('DELETE', `/api/devices/${this.selected.id}/macs?mac=${encodeURIComponent(m.mac)}`);
      } catch (e) { this.identityMsg = e.message; }
    },

    /** O dispositivo está DOWN e pode ter trocado de IP (DHCP): procura o MAC dele na rede. */
    async locateIp() {
      this.locating = true;
      this.identityMsg = '';
      try {
        const r = await api('POST', `/api/devices/${this.selected.id}/locate`);
        this.identityMsg = r.message;
        if (r.found) { await this.reloadAll(); await this.loadIdentity(this.selected.id); }
      } catch (e) { this.identityMsg = e.message; }
      this.locating = false;
    },

    // ---------------------------------------------------------------- gráfico de tráfego
    async openTraffic(i) {
      this.trafficIface = this.trafficIface && this.trafficIface.id === i.id ? null : { id: i.id, name: i.name, speedBps: i.speedBps };
      if (!this.trafficIface) { TrafficChart.destroy(); return; }
      await this.loadTraffic();
    },

    closeTraffic() {
      this.trafficIface = null;
      TrafficChart.destroy();
    },

    async setTrafficRange(r) {
      this.trafficRange = r;
      try { localStorage.setItem('omnidude.trafficRange', r); } catch (e) { /* armazenamento indisponível */ }
      await this.loadTraffic();
    },

    async loadTraffic() {
      if (!this.trafficIface) return;
      const seq = (this._trafficSeq = (this._trafficSeq || 0) + 1);
      const id = this.trafficIface.id;
      let series;
      try {
        series = await api('GET', `/api/interfaces/${id}/metrics?range=${this.trafficRange}`);
      } catch (e) { this.flash(e.message); return; }
      if (seq !== this._trafficSeq || !this.trafficIface) return;
      this.trafficStats = series.stats;
      this.trafficEmpty = series.points.length === 0;
      await this.$nextTick();
      if (seq === this._trafficSeq && this.trafficIface) TrafficChart.render(series);
    },

    trafficStatsText() {
      const s = this.trafficStats;
      if (!s || !s.samples) return '';
      return `entrada: média ${bpsText(s.inAvg)}, pico ${bpsText(s.inMax)} · saída: média ${bpsText(s.outAvg)}, pico ${bpsText(s.outMax)}`;
    },

    async toggleMonitored(i, checked) {
      try {
        await api('PUT', '/api/interfaces/' + i.id, { monitored: checked });
        i.monitored = checked;
      } catch (e) { this.flash(e.message); await this.loadInterfaces(this.selected.id); }
    },

    async refreshViaSnmp() {
      this.ifaceBusy = true;
      try {
        const r = await api('POST', `/api/devices/${this.selected.id}/refresh`);
        this.flash(`${r.interfaces} interface(s) lida(s), ${r.newInterfaces} nova(s)`);
        await this.reloadAll();
      } catch (e) { this.flash(e.message); }
      this.ifaceBusy = false;
    },

    async readLldp() {
      this.ifaceBusy = true;
      try {
        const r = await api('POST', `/api/devices/${this.selected.id}/lldp`);
        await this.loadMap(false);
        const parts = [`${r.created} enlace(s) sugerido(s)`, `${r.existing + r.updated} já conhecido(s)`];
        if (r.rejected) parts.push(`${r.rejected} rejeitado(s) antes`);
        if (r.unmatched.length) parts.push(`${r.unmatched.length} vizinho(s) fora do inventário`);
        this.flash('LLDP: ' + parts.join(' · '));
      } catch (e) { this.flash(e.message); }
      this.ifaceBusy = false;
    },

    // ---------------------------------------------------------------- descoberta
    jobStatusText(s) {
      return { PENDING: 'na fila', RUNNING: 'em andamento', DONE: 'concluída', CANCELLED: 'cancelada', FAILED: 'falhou' }[s] || s;
    },

    progressPct() {
      const j = this.disc.job;
      return j && j.hostsTotal ? Math.min(100, Math.round((100 * j.hostsScanned) / j.hostsTotal)) : 0;
    },

    /** Estimativa local, só para avisar de varreduras grandes; quem decide é o servidor. */
    estimatedHosts() {
      const m = (this.disc.form.cidr || '').trim().match(/^\d{1,3}(\.\d{1,3}){3}\/(\d{1,2})$/);
      if (!m) return null;
      const p = Number(m[2]);
      if (p > 32) return null;
      return p >= 31 ? (p === 32 ? 1 : 2) : Math.pow(2, 32 - p) - 2;
    },

    parsePorts(text) {
      const parts = (text || '').split(/[,\s]+/).filter(Boolean);
      const ports = parts.map(Number);
      if (ports.some((p) => !Number.isInteger(p) || p < 1 || p > 65535)) throw new Error('Portas inválidas: use números de 1 a 65535 separados por vírgula');
      return ports;
    },

    async openDiscovery() {
      this.disc.mapId = this.disc.mapId || String(this.mapId || '');
      await this.loadJobs();
      const running = this.disc.jobs.find((j) => j.status === 'RUNNING' || j.status === 'PENDING');
      if (running) { this.disc.job = running; this.disc.step = 'progress'; }
    },

    async loadJobs() {
      try { this.disc.jobs = await api('GET', '/api/discovery/jobs'); } catch (e) { /* mantém a lista */ }
    },

    async startDiscovery() {
      this.disc.error = '';
      this.disc.busy = true;
      try {
        const f = this.disc.form;
        const job = await api('POST', '/api/discovery/jobs', {
          cidr: f.cidr.trim(), ports: this.parsePorts(f.ports), snmpProfileIds: f.profileIds,
          confirmLarge: f.confirmLarge, authorized: f.authorized,
        });
        this.disc.job = job;
        this.disc.hosts = [];
        this.disc.result = null;
        this.disc.step = 'progress';
        await this.loadJobs();
      } catch (e) { this.disc.error = e.message; }
      this.disc.busy = false;
    },

    onDiscoveryProgress(job) {
      const i = this.disc.jobs.findIndex((j) => j.id === job.id);
      if (i >= 0) this.disc.jobs[i] = job; else this.disc.jobs.unshift(job);
      if (this.disc.job && this.disc.job.id === job.id) {
        this.disc.job = job;
        if (job.status !== 'RUNNING' && job.status !== 'PENDING' && this.disc.step === 'progress') this.showReview();
      }
    },

    async showReview() {
      await this.loadHosts();
      this.disc.step = 'review';
    },

    async loadHosts() {
      const hosts = await api('GET', `/api/discovery/jobs/${this.disc.job.id}/hosts`);
      this.disc.hosts = hosts;
      for (const h of hosts) {
        if (!(h.id in this.disc.names)) {
          this.disc.names[h.id] = '';
          this.disc.types[h.id] = h.suggestedType || 'OTHER';
        }
      }
    },

    async openJobById(id) {
      const job = this.disc.jobs.find((j) => String(j.id) === String(id));
      if (job) await this.openJob(job);
    },

    async openJob(job) {
      this.disc.job = job;
      this.disc.selected = {};
      this.disc.names = {};
      this.disc.types = {};
      this.disc.result = null;
      this.disc.error = '';
      if (job.status === 'RUNNING' || job.status === 'PENDING') this.disc.step = 'progress';
      else await this.showReview();
    },

    newScan() {
      this.disc.step = 'form';
      this.disc.error = '';
      this.disc.result = null;
    },

    async cancelDiscovery() {
      try { await api('POST', `/api/discovery/jobs/${this.disc.job.id}/cancel`); } catch (e) { this.disc.error = e.message; }
    },

    newHosts() {
      return this.disc.hosts.filter((h) => h.state === 'NEW');
    },

    selectedCount() {
      return this.newHosts().filter((h) => this.disc.selected[h.id]).length;
    },

    selectAllNew() {
      for (const h of this.newHosts()) this.disc.selected[h.id] = !h.matchedDeviceId;   // quem parece um dispositivo já cadastrado fica de fora
    },

    matchByText(by) {
      return { MAC: 'pelo MAC', MAC_RANDOM: 'por um MAC aleatório (menos confiável)', HOSTNAME: 'pelo nome (sugestão)' }[by] || '';
    },

    /** O host é um dispositivo que já temos, com outro IP (DHCP): atualiza o IP em vez de criar um duplicado. */
    async adoptHost(h) {
      if (!confirm(`Atualizar o IP de "${h.matchedDeviceName}" de ${h.matchedOldIp} para ${h.ip}? O histórico, as checagens e os enlaces dele são mantidos.`)) return;
      try {
        const c = await api('POST', `/api/discovery/hosts/${h.id}/adopt`);
        this.flash(`${c.name}: IP atualizado para ${c.newIp}`);
        await this.loadHosts();
        await this.reloadAll();
      } catch (e) { this.flash(e.message); }
    },

    async importSelected() {
      const items = this.newHosts().filter((h) => this.disc.selected[h.id]).map((h) => ({
        hostId: h.id, name: (this.disc.names[h.id] || '').trim() || null, deviceType: this.disc.types[h.id] || null,
        forceNew: !!h.matchedDeviceId,           // marcar à mão um host "reconhecido" é decidir importá-lo como novo
      }));
      if (!items.length) return;
      this.disc.busy = true;
      this.disc.error = '';
      try {
        this.disc.result = await api('POST', `/api/discovery/jobs/${this.disc.job.id}/import`, {
          hosts: items, mapId: this.disc.mapId ? Number(this.disc.mapId) : null, openPortChecks: this.disc.openPortChecks,
        });
        this.disc.selected = {};
        await this.loadHosts();
        await this.reloadAll();
      } catch (e) { this.disc.error = e.message; }
      this.disc.busy = false;
    },

    async ignoreHost(h) {
      try {
        await api('POST', `/api/discovery/hosts/${h.id}/ignore`);
        await this.loadHosts();
      } catch (e) { this.disc.error = e.message; }
    },

    // ---------------------------------------------------------------- eventos
    async loadEventList() {
      const q = new URLSearchParams({ limit: '200', unacked: String(this.eventFilter.unacked) });
      if (this.eventFilter.severity) q.set('severity', this.eventFilter.severity);
      this.eventList = await api('GET', '/api/events?' + q);
    },

    async ack(e) {
      try {
        await api('POST', `/api/events/${e.id}/ack`);
        e.acknowledged = true;
        this.unacked = Math.max(0, this.unacked - 1);
        const same = this.events.find((x) => x.id === e.id);
        if (same) same.acknowledged = true;
        if (this.eventFilter.unacked) this.eventList = this.eventList.filter((x) => x.id !== e.id);
      } catch (err) { this.flash(err.message); }
    },

    // ---------------------------------------------------------------- configurações (só ADMIN)
    get navList() {
      return this.navItems.filter((i) => i.id !== 'settings' || this.isAdmin);
    },

    fmtTime(ts) {
      return ts ? new Date(ts * 1000).toLocaleString('pt-BR') : '—';
    },

    async openSettings(tab) {
      if (tab) this.settingsTab = tab;
      try {
        if (this.settingsTab === 'notifications') {
          [this.channels, this.rules, this.notifLog] = await Promise.all([
            api('GET', '/api/notification-channels'), api('GET', '/api/notification-rules'), api('GET', '/api/notification-log?limit=60')]);
        } else if (this.settingsTab === 'adjustments') {
          await this.loadAdjustments();
        } else if (this.settingsTab === 'users') {
          this.users = await api('GET', '/api/users');
        } else if (this.settingsTab === 'backup') {
          this.backups = await api('GET', '/api/admin/backups');
        }
      } catch (e) { this.flash(e.message); }
    },

    // --- canais
    channelSummary(c) {
      const k = c.config || {};
      if (c.type === 'WEBHOOK') return k.url + (c.secrets.secret ? ' · assinado' : '');
      if (c.type === 'TELEGRAM') return 'chat ' + k.chatId;
      return `${k.host}:${k.port} (${k.security}) → ${k.to}`;
    },

    channelTypeLabel(t) {
      return { WEBHOOK: 'Webhook', TELEGRAM: 'Telegram', EMAIL: 'E-mail' }[t] || t;
    },

    newChannel() {
      this.chf = { id: null, name: '', type: 'WEBHOOK', enabled: true, secrets: {},
        config: { url: '', secret: '', botToken: '', chatId: '', apiBase: '', host: '', port: '', security: 'STARTTLS',
          from: '', to: '', username: '', password: '' } };
      this.chfError = '';
      this.showChannel = true;
    },

    editChannel(c) {
      this.chf = { id: c.id, name: c.name, type: c.type, enabled: c.enabled, secrets: c.secrets,
        config: { url: '', secret: '', botToken: '', chatId: '', apiBase: '', host: '', port: '', security: 'STARTTLS',
          from: '', to: '', username: '', password: '', ...c.config } };
      this.chfError = '';
      this.showChannel = true;
    },

    async saveChannel() {
      const f = this.chf;
      const keys = { WEBHOOK: ['url', 'secret'], TELEGRAM: ['botToken', 'chatId', 'apiBase'],
        EMAIL: ['host', 'port', 'security', 'from', 'to', 'username', 'password'] }[f.type];
      const config = {};
      keys.forEach((k) => { config[k] = (f.config[k] || '').toString().trim(); });
      const body = { name: f.name, type: f.type, enabled: f.enabled, config };
      try {
        if (f.id) await api('PUT', '/api/notification-channels/' + f.id, body);
        else await api('POST', '/api/notification-channels', body);
        this.showChannel = false;
        await this.openSettings();
      } catch (e) { this.chfError = e.message; }
    },

    async toggleChannel(c) {
      try {
        await api('PUT', '/api/notification-channels/' + c.id, { name: c.name, type: c.type, enabled: !c.enabled, config: {} });
        await this.openSettings();
      } catch (e) { this.flash(e.message); }
    },

    async removeChannel(c) {
      if (!confirm(`Excluir o canal "${c.name}" e as suas regras?`)) return;
      try { await api('DELETE', '/api/notification-channels/' + c.id); await this.openSettings(); } catch (e) { this.flash(e.message); }
    },

    async testChannel(c) {
      this.chTest = { ...this.chTest, [c.id]: { busy: true } };
      try {
        const r = await api('POST', `/api/notification-channels/${c.id}/test`);
        this.chTest = { ...this.chTest, [c.id]: { ok: r.ok, error: r.error } };
        this.notifLog = await api('GET', '/api/notification-log?limit=60');
      } catch (e) { this.chTest = { ...this.chTest, [c.id]: { ok: false, error: e.message } }; }
    },

    // --- regras
    ruleText(r) {
      const types = r.eventTypes.length ? r.eventTypes.map((t) => EVENT_TYPE_LABELS[t] || t).join(', ') : 'todos os tipos';
      return `${r.minSeverity === 'INFO' ? 'qualquer gravidade' : r.minSeverity === 'WARNING' ? 'atenção ou pior' : 'só críticos'} · ${types}`
        + ` · ${r.deviceName || 'todos os dispositivos'}${r.includeRecovery ? ' · inclui recuperação' : ''}`;
    },

    newRule() {
      this.rlf = { id: null, channelId: this.channels[0] ? String(this.channels[0].id) : '', minSeverity: 'WARNING',
        eventTypes: [], deviceId: '', enabled: true, includeRecovery: true };
      this.rlfError = '';
      this.showRule = true;
    },

    editRule(r) {
      this.rlf = { id: r.id, channelId: String(r.channelId), minSeverity: r.minSeverity, eventTypes: [...r.eventTypes],
        deviceId: r.deviceId ? String(r.deviceId) : '', enabled: r.enabled, includeRecovery: r.includeRecovery };
      this.rlfError = '';
      this.showRule = true;
    },

    async saveRule() {
      const f = this.rlf;
      if (!f.channelId) { this.rlfError = 'Crie um canal antes'; return; }
      const body = { channelId: Number(f.channelId), minSeverity: f.minSeverity, eventTypes: f.eventTypes,
        deviceId: f.deviceId ? Number(f.deviceId) : null, enabled: f.enabled, includeRecovery: f.includeRecovery };
      try {
        if (f.id) await api('PUT', '/api/notification-rules/' + f.id, body);
        else await api('POST', '/api/notification-rules', body);
        this.showRule = false;
        await this.openSettings();
      } catch (e) { this.rlfError = e.message; }
    },

    async toggleRule(r) {
      try {
        await api('PUT', '/api/notification-rules/' + r.id, { channelId: r.channelId, minSeverity: r.minSeverity,
          eventTypes: r.eventTypes, deviceId: r.deviceId, enabled: !r.enabled, includeRecovery: r.includeRecovery });
        await this.openSettings();
      } catch (e) { this.flash(e.message); }
    },

    async removeRule(r) {
      if (!confirm('Excluir esta regra?')) return;
      try { await api('DELETE', '/api/notification-rules/' + r.id); await this.openSettings(); } catch (e) { this.flash(e.message); }
    },

    logStatusText(s) {
      return { SENT: 'enviada', FAILED: 'falhou', SUPPRESSED: 'suprimida', TEST: 'teste' }[s] || s;
    },

    // --- ajustes e manutenção
    async loadAdjustments() {
      this.settingsItems = await api('GET', '/api/settings');
      this.settingsDraft = {};
      this.settingsItems.forEach((i) => { this.settingsDraft[i.key] = i.type === 'BOOL' ? !!i.value : i.value; });
      this.maintenance = this.maintenanceNow();
    },

    adjustmentGroups() {
      const groups = [];
      this.settingsItems.filter((i) => !i.key.startsWith('maintenance.')).forEach((i) => {
        let g = groups.find((x) => x.name === i.group);
        if (!g) { g = { name: i.group, items: [] }; groups.push(g); }
        g.items.push(i);
      });
      return groups;
    },

    settingsChanged() {
      return this.settingsItems.filter((i) => !i.key.startsWith('maintenance.')
        && (i.type === 'BOOL' ? (this.settingsDraft[i.key] ? 1 : 0) !== i.value : Number(this.settingsDraft[i.key]) !== i.value));
    },

    async saveAdjustments() {
      const body = {};
      this.settingsChanged().forEach((i) => { body[i.key] = i.type === 'BOOL' ? !!this.settingsDraft[i.key] : Number(this.settingsDraft[i.key]); });
      try {
        this.settingsItems = await api('PUT', '/api/settings', body);
        await this.loadAdjustments();
        this.flash('Ajustes salvos');
      } catch (e) { this.flash(e.message); }
    },

    async resetAdjustment(i) {
      try {
        await api('PUT', '/api/settings', { [i.key]: null });
        await this.loadAdjustments();
      } catch (e) { this.flash(e.message); }
    },

    maintenanceItem(key) {
      return (this.settingsItems.find((i) => i.key === key) || {}).value;
    },

    maintenanceNow() {
      const on = this.maintenanceItem('maintenance.enabled');
      const until = this.maintenanceItem('maintenance.until');
      return !!on && (!until || Date.now() / 1000 < until);
    },

    maintenanceText() {
      const until = this.maintenanceItem('maintenance.until');
      return this.maintenanceNow() ? 'Ativo' + (until ? ' até ' + this.fmtTime(until) : ' até você desligar') : 'Desligado';
    },

    async setMaintenance(minutes) {
      const body = minutes === false ? { 'maintenance.enabled': false, 'maintenance.until': null }
        : { 'maintenance.enabled': true, 'maintenance.until': minutes ? Math.floor(Date.now() / 1000) + minutes * 60 : null };
      try {
        this.settingsItems = await api('PUT', '/api/settings', body);
        await this.loadAdjustments();
        this.summaryMaintenance();
      } catch (e) { this.flash(e.message); }
    },

    async summaryMaintenance() {
      try { this.maintenance = (await api('GET', '/api/summary')).maintenance; } catch (e) { /* o aviso some no próximo ciclo */ }
    },

    // --- usuários
    newUser() {
      this.usf = { username: '', password: '', role: 'VIEWER' };
      this.usfError = '';
      this.showUser = true;
    },

    async saveUser() {
      try {
        await api('POST', '/api/users', this.usf);
        this.showUser = false;
        await this.openSettings();
        this.flash('Usuário criado: a senha é provisória e será trocada no primeiro acesso');
      } catch (e) { this.usfError = e.message; }
    },

    isMe(u) { return !!this.me && u.username === this.me.username; },

    async changeUser(u, patch) {
      try { await api('PUT', '/api/users/' + u.id, patch); await this.openSettings(); } catch (e) { this.flash(e.message); await this.openSettings(); }
    },

    resetUser(u) {
      this.rsf = { id: u.id, username: u.username, password: '' };
      this.rsfError = '';
      this.showReset = true;
    },

    async saveReset() {
      try {
        await api('PUT', '/api/users/' + this.rsf.id, { password: this.rsf.password });
        this.showReset = false;
        this.flash('Senha redefinida: o usuário terá de trocá-la no próximo acesso');
        await this.openSettings();
      } catch (e) { this.rsfError = e.message; }
    },

    async removeUser(u) {
      if (!confirm(`Apagar o usuário "${u.username}"?`)) return;
      try { await api('DELETE', '/api/users/' + u.id); await this.openSettings(); } catch (e) { this.flash(e.message); }
    },

    // --- backup
    async makeBackup() {
      this.backupBusy = true;
      try {
        const b = await api('POST', '/api/admin/backup');
        this.flash('Backup criado: ' + b.name);
        await this.openSettings();
      } catch (e) { this.flash(e.message); }
      this.backupBusy = false;
    },

    async removeBackup(b) {
      if (!confirm(`Apagar o backup ${b.name}?`)) return;
      try { await api('DELETE', '/api/admin/backups/' + encodeURIComponent(b.name)); await this.openSettings(); } catch (e) { this.flash(e.message); }
    },

    sizeText(bytes) {
      if (bytes >= 1048576) return (bytes / 1048576).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' MB';
      return Math.max(1, Math.round(bytes / 1024)) + ' kB';
    },

    // ---------------------------------------------------------------- navegação e busca
    async go(id) {
      if (id !== 'map') { this.closeDrawer(); this.cancelLinkMode(); }   // o inspetor pertence ao mapa
      this.view = id;
      if (id === 'events') await this.loadEventList();
      if (id === 'dashboard') await this.loadDashboard();
      if (id === 'discovery') await this.openDiscovery();
      if (id === 'settings') await this.openSettings();
      if (id === 'map') { await this.$nextTick(); cy.resize(); }
    },

    closeModals() {
      if (this.mustChange) return;
      if (this.showReset) this.showReset = false;
      else if (this.showUser) this.showUser = false;
      else if (this.showRule) this.showRule = false;
      else if (this.showChannel) this.showChannel = false;
      else if (this.showPublish) this.showPublish = false;
      else if (this.showProfiles) this.showProfiles = false;       // o mais recente primeiro
      else if (this.showLink) this.showLink = false;
      else if (this.showCheck) this.showCheck = false;
      else if (this.showDevice) this.showDevice = false;
      else if (this.linkMode) this.cancelLinkMode();
    },

    matches(d) {
      const q = this.search.trim().toLowerCase();
      return !q || [d.name, d.primaryIp, d.hostname, d.macAddress, d.macVendor].some((v) => v && v.toLowerCase().includes(q));
    },

    get filteredDevices() {
      return this.devices.filter((d) => this.matches(d));
    },

    applySearch() {
      if (!cy) return;
      const q = this.search.trim();
      cy.nodes().removeClass('dim');
      if (!q) return;
      const ok = new Set(this.devices.filter((d) => this.matches(d)).map((d) => 'd' + d.id));
      cy.nodes().filter((n) => !ok.has(n.id())).addClass('dim');
    },

    focusFirstMatch() {
      const d = this.filteredDevices[0];
      if (d) this.openDevice(d.id, true);
    },

    // ---------------------------------------------------------------- conta
    async changePassword() {
      this.pwd.error = '';
      if (this.pwd.next !== this.pwd.confirm) { this.pwd.error = 'As senhas não coincidem'; return; }
      try {
        await api('POST', '/api/me/password', { currentPassword: this.pwd.current, newPassword: this.pwd.next });
        this.mustChange = false;
        this.me.mustChangePassword = false;
        this.pwd = { current: '', next: '', confirm: '', error: '' };
        await this.start();
      } catch (e) { this.pwd.error = e.message; }
    },

    async logout() {
      if (stream) stream.close();
      await fetch('/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': xsrf() }, credentials: 'same-origin' });
      location.href = '/login.html';
    },

    // ---------------------------------------------------------------- apresentação
    get isAdmin() { return this.me && this.me.role === 'ADMIN'; },

    get counts() {
      const c = { UP: 0, WARNING: 0, DOWN: 0, UNKNOWN: 0 };
      for (const d of this.devices) if (d.enabled) c[d.status]++;
      return c;
    },

    typeLabel(t) { return TYPE_LABELS[t] || t; },
    statusText(s) { return (STATUS[s] || STATUS.UNKNOWN).text; },
    statusGlyph(s) { return (STATUS[s] || STATUS.UNKNOWN).glyph; },
    statusClass(s) { return (STATUS[s] || STATUS.UNKNOWN).cls; },
    sevClass(s) { return { CRITICAL: 'text-down', WARNING: 'text-warn', INFO: 'text-sky-400' }[s]; },
    sevGlyph(s) { return { CRITICAL: '✖', WARNING: '▲', INFO: 'ℹ' }[s]; },
    fmt(ts) { return new Date(ts * 1000).toLocaleString('pt-BR'); },
    clock(ts) { return new Date(ts * 1000).toLocaleTimeString('pt-BR'); },
    since(ts) {
      if (!ts) return '—';
      const s = Math.max(0, Math.floor(Date.now() / 1000 - ts));
      if (s < 60) return 'há ' + s + ' s';
      if (s < 3600) return 'há ' + Math.floor(s / 60) + ' min';
      if (s < 86400) return 'há ' + Math.floor(s / 3600) + ' h';
      return 'há ' + Math.floor(s / 86400) + ' d';
    },
  };
}

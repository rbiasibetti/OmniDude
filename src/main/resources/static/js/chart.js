/* Gráfico de latência (uPlot). Um único gráfico por vez, fora do estado reativo do Alpine. */
'use strict';

const LatencyChart = (() => {
  const HOST_ID = 'chart-host';
  let plot = null;
  let observer = null;

  function destroy() {
    if (observer) { observer.disconnect(); observer = null; }
    if (plot) { plot.destroy(); plot = null; }
  }

  function axisStyle() {
    return { stroke: '#94a3b8', grid: { stroke: 'rgba(148,163,184,0.12)', width: 1 }, ticks: { stroke: 'rgba(148,163,184,0.25)' } };
  }

  /**
   * @param series resposta de /api/checks/{id}/results: {from, to, points:[{ts, ok, total, avgMs, maxMs}]}
   * Barras vermelhas marcam baldes com falhas; a linha tem lacunas onde nenhuma amostra respondeu.
   */
  function render(series) {
    destroy();
    const host = document.getElementById(HOST_ID);
    if (!host || !series.points.length) return;

    const xs = series.points.map((p) => p.ts);
    const avg = series.points.map((p) => p.avgMs);
    const max = series.points.map((p) => p.maxMs);
    const fail = series.points.map((p) => (p.ok < p.total ? 1 : null));

    const opts = {
      width: host.clientWidth || 320,
      height: 170,
      cursor: { drag: { x: false, y: false } },
      legend: { show: true },
      scales: {
        x: { time: true, range: () => [series.from, series.to] },
        y: { range: (u, lo, hi) => [0, Math.max(hi * 1.15, 5)] },
        fail: { range: () => [0, 1] },
      },
      axes: [
        { ...axisStyle() },
        { ...axisStyle(), size: 46, values: (u, vals) => vals.map((v) => v + ' ms') },
        { show: false, scale: 'fail' },
      ],
      series: [
        {},
        { label: 'média', stroke: '#38bdf8', width: 2, points: { show: xs.length < 60 }, value: (u, v) => (v == null ? '—' : v + ' ms') },
        { label: 'máx', stroke: 'rgba(56,189,248,0.5)', width: 1, dash: [4, 3], points: { show: false }, value: (u, v) => (v == null ? '—' : v + ' ms') },
        {
          label: 'falhas', scale: 'fail', stroke: 'rgba(239,68,68,0.9)', fill: 'rgba(239,68,68,0.35)',
          paths: uPlot.paths.bars({ size: [0.8, 14] }), points: { show: false },
          value: (u, v) => (v ? 'sim' : 'não'),
        },
      ],
    };
    plot = new uPlot(opts, [xs, avg, max, fail], host);

    observer = new ResizeObserver(() => {
      if (plot && host.clientWidth > 0) plot.setSize({ width: host.clientWidth, height: 170 });
    });
    observer.observe(host);
  }

  return { render, destroy };
})();

/** Gráfico de tráfego Rx/Tx de uma interface. Linhas cheias = média do balde; tracejadas = pico. */
const TrafficChart = (() => {
  const HOST_ID = 'traffic-chart-host';
  const IN = '#38bdf8';
  const OUT = '#f59e0b';
  let plot = null;
  let observer = null;

  function destroy() {
    if (observer) { observer.disconnect(); observer = null; }
    if (plot) { plot.destroy(); plot = null; }
  }

  /** 1500000 → "1,5 Mb/s" (eixo e legenda). */
  function fmt(bps) {
    if (bps == null) return '—';
    const units = [[1e9, 'Gb/s'], [1e6, 'Mb/s'], [1e3, 'kb/s']];
    for (const [f, u] of units) {
      if (Math.abs(bps) >= f) return (bps / f).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' ' + u;
    }
    return Math.round(bps) + ' b/s';
  }

  function axisStyle() {
    return { stroke: '#94a3b8', grid: { stroke: 'rgba(148,163,184,0.12)', width: 1 }, ticks: { stroke: 'rgba(148,163,184,0.25)' } };
  }

  /** @param series resposta de /api/interfaces/{id}/metrics: {from, to, points:[{ts, inAvg, inMax, outAvg, outMax}]} */
  function render(series) {
    destroy();
    const host = document.getElementById(HOST_ID);
    if (!host || !series.points.length) return;

    const xs = series.points.map((p) => p.ts);
    const col = (k) => series.points.map((p) => (p[k] == null ? null : p[k]));
    const opts = {
      width: host.clientWidth || 320,
      height: 180,
      cursor: { drag: { x: false, y: false } },
      legend: { show: true },
      scales: {
        x: { time: true, range: () => [series.from, series.to] },
        y: { range: (u, lo, hi) => [0, Math.max(hi * 1.15, 1000)] },
      },
      axes: [
        { ...axisStyle() },
        { ...axisStyle(), size: 64, values: (u, vals) => vals.map(fmt) },
      ],
      series: [
        {},
        { label: '↓ entrada', stroke: IN, fill: 'rgba(56,189,248,0.12)', width: 2, points: { show: xs.length < 60 }, value: (u, v) => fmt(v) },
        { label: '↓ pico', stroke: 'rgba(56,189,248,0.6)', width: 1, dash: [4, 3], points: { show: false }, value: (u, v) => fmt(v) },
        { label: '↑ saída', stroke: OUT, fill: 'rgba(245,158,11,0.12)', width: 2, points: { show: xs.length < 60 }, value: (u, v) => fmt(v) },
        { label: '↑ pico', stroke: 'rgba(245,158,11,0.6)', width: 1, dash: [4, 3], points: { show: false }, value: (u, v) => fmt(v) },
      ],
    };
    plot = new uPlot(opts, [xs, col('inAvg'), col('inMax'), col('outAvg'), col('outMax')], host);

    observer = new ResizeObserver(() => {
      if (plot && host.clientWidth > 0) plot.setSize({ width: host.clientWidth, height: 180 });
    });
    observer.observe(host);
  }

  return { render, destroy };
})();

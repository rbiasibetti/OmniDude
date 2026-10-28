/* Constantes e ícones compartilhados pela interface de administração (app.js) e pela página pública (public.js). */
'use strict';

const STATUS = {
  UP: { text: 'UP', glyph: '✔', color: '#22c55e', cls: 'text-up' },
  WARNING: { text: 'ATENÇÃO', glyph: '▲', color: '#f59e0b', cls: 'text-warn' },
  DOWN: { text: 'DOWN', glyph: '✖', color: '#ef4444', cls: 'text-down' },
  UNKNOWN: { text: 'DESCONHECIDO', glyph: '?', color: '#94a3b8', cls: 'text-unknown' },
};

const TYPE_LABELS = {
  ROUTER: 'Roteador', SWITCH: 'Switch', FIREWALL: 'Firewall', ACCESS_POINT: 'Access point', SERVER: 'Servidor',
  WORKSTATION: 'Estação', PRINTER: 'Impressora', STORAGE: 'Storage', CLOUD: 'Nuvem', NETWORK: 'Rede', OTHER: 'Outro',
};

// Ícones 24x24 (traço claro) usados como imagem de fundo dos nós.
const ICON_PATHS = {
  ROUTER: '<rect x="3" y="14" width="18" height="6" rx="1.5"/><path d="M7 17h.01M11 17h.01M12 14V9M8.5 9.5a5 5 0 0 1 7 0M6 6.5a8.5 8.5 0 0 1 12 0"/>',
  SWITCH: '<rect x="2" y="8" width="20" height="9" rx="1.5"/><path d="M6 12.5h.01M10 12.5h.01M14 12.5h.01M18 12.5h.01"/>',
  FIREWALL: '<rect x="3" y="4" width="18" height="16" rx="1"/><path d="M3 9.3h18M3 14.7h18M9 4v5.3M15 9.3v5.4M9 14.7V20"/>',
  ACCESS_POINT: '<path d="M4 9a12 12 0 0 1 16 0M7 12.5a7.5 7.5 0 0 1 10 0M10 16a3 3 0 0 1 4 0"/><circle cx="12" cy="19" r="1"/>',
  SERVER: '<rect x="3" y="3" width="18" height="7" rx="1"/><rect x="3" y="14" width="18" height="7" rx="1"/><path d="M7 6.5h.01M7 17.5h.01"/>',
  WORKSTATION: '<rect x="3" y="4" width="18" height="12" rx="1"/><path d="M8 20h8M12 16v4"/>',
  PRINTER: '<path d="M7 9V3h10v6M7 17H4v-7h16v7h-3"/><rect x="7" y="14" width="10" height="7"/>',
  STORAGE: '<ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5v13c0 1.7 3.6 3 8 3s8-1.3 8-3v-13M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
  CLOUD: '<path d="M7 18.5a4 4 0 0 1-.6-7.95A5.5 5.5 0 0 1 17 9a4.75 4.75 0 0 1 .5 9.5H7z"/>',
  NETWORK: '<circle cx="12" cy="5" r="2"/><circle cx="5" cy="19" r="2"/><circle cx="19" cy="19" r="2"/><path d="M12 7v5M12 12l-6 5.5M12 12l6 5.5M7 19h10"/>',
  OTHER: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 1.7M12 17h.01"/>',
};

function iconUri(type) {
  const body = ICON_PATHS[type] || ICON_PATHS.OTHER;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#e2e8f0" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
  return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
}

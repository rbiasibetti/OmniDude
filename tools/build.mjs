// Copia as libs para static/vendor e compila o Tailwind. Uso: npm install && npm run build
import { copyFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const vendor = '../src/main/resources/static/vendor';
mkdirSync(vendor, { recursive: true });
copyFileSync('node_modules/cytoscape/dist/cytoscape.min.js', `${vendor}/cytoscape.min.js`);
copyFileSync('node_modules/alpinejs/dist/cdn.min.js', `${vendor}/alpine.min.js`);
copyFileSync('node_modules/uplot/dist/uPlot.iife.min.js', `${vendor}/uplot.min.js`);
copyFileSync('node_modules/uplot/dist/uPlot.min.css', `${vendor}/uplot.min.css`);
execFileSync('node_modules/.bin/tailwindcss',
  ['-c', 'tailwind.config.js', '-i', 'input.css', '-o', '../src/main/resources/static/css/app.css', '--minify'],
  { stdio: 'inherit' });
console.log('ok');

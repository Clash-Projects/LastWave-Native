'use strict';
/**
 * Build public/style.css (minified) + public/critical.css (minified, inlined in <head>)
 * from maintainable public/css/* modules.
 * Usage: node tools/build-css.js
 * Order matters: critical -> tokens -> base -> ... -> celestial.
 * 00-critical.css is ALSO inlined into HTML, so it stays first in the bundle too.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const ORDER = [
  '00-critical.css',
  '01-tokens.css',
  '02-base.css',
  '03-chrome.css',
  '04-layout.css',
  '05-panels.css',
  '06-buttons.css',
  '07-forms.css',
  '08-feedback.css',
  '09-data.css',
  '10-overlays.css',
  '11-admin.css',
  '12-responsive.css',
  '13-beauty.css',
  '14-celestial.css',
  '15-perf.css',
];

/* Safe CSS minifier: strips block comments, collapses whitespace, trims
 * around structural punctuation only. Never touches + - * / (calc-safe),
 * string contents, or url() payloads. */
function minifyCss(src) {
  let s = String(src).replace(/\/\*[\s\S]*?\*\//g, '');
  s = s.replace(/\s+/g, ' ');
  s = s.replace(/\s*([{};:,>~])\s*/g, '$1');
  s = s.replace(/;}/g, '}');
  return s.trim();
}

function build() {
  const parts = [];
  for (const file of ORDER) {
    const p = path.join(ROOT, 'public', 'css', file);
    if (!fs.existsSync(p)) throw new Error('missing module: ' + file);
    parts.push(fs.readFileSync(p, 'utf8'));
  }
  const raw = parts.join('\n');
  const out = minifyCss(raw) + '\n';
  fs.writeFileSync(path.join(ROOT, 'public', 'style.css'), out);

  const criticalRaw = fs.readFileSync(path.join(ROOT, 'public', 'css', '00-critical.css'), 'utf8');
  const critical = minifyCss(criticalRaw);
  fs.writeFileSync(path.join(ROOT, 'public', 'critical.css'), critical + '\n');

  return {
    bytes: Buffer.byteLength(out),
    rawBytes: Buffer.byteLength(raw),
    criticalBytes: Buffer.byteLength(critical),
    modules: ORDER.length,
  };
}

if (require.main === module) {
  const r = build();
  console.log(`built public/style.css (${r.bytes} bytes, minified from ${r.rawBytes}) + public/critical.css (${r.criticalBytes} bytes) from ${r.modules} modules`);
}

module.exports = { build, minifyCss };

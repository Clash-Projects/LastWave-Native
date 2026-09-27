'use strict';
/* Client JS modules — edit public/js/src/*.js then run: node tools/build-js.js
 * Bundled into /public/app.js (single request, deferred). No deps.
 * Production output is minified: block comments stripped, full-line //
 * comments and blank lines dropped, lines trimmed. Semantics preserved:
 * no token-level rewrites, newlines kept as ASI-safe separators. */
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const ORDER = [
  '00-header.js',
  '01-toast.js',
  '02-nav.js',
  '03-copy.js',
  '04-download.js',
  '05-saved.js',
  '06-modal.js',
  '07-poll.js',
  '08-filter.js',
  '09-quota.js',
  '10-otp.js',
  '11-qr.js',
  '12-celestial.js',
  '13-tilt.js',
  '14-fx.js',
  '99-footer.js',
];

/* Conservative JS minifier: comment/blank-line removal + trim only.
 * Client sources are newline-separated ES5-style statements with no
 * template literals or line continuations, so joining trimmed lines
 * with \n preserves ASI behavior exactly. */
function minifyJs(src) {
  const noBlocks = String(src).replace(/\/\*[\s\S]*?\*\//g, '');
  const out = [];
  for (const line of noBlocks.split('\n')) {
    const t = line.trim();
    if (!t) continue;
    if (t.startsWith('//')) continue;
    out.push(t);
  }
  return out.join('\n');
}

function build() {
  const parts = [];
  let rawBytes = 0;
  for (const f of ORDER) {
    const p = path.join(ROOT, 'public', 'js', 'src', f);
    if (!fs.existsSync(p)) throw new Error('missing JS module: ' + f);
    const src = fs.readFileSync(p, 'utf8');
    rawBytes += Buffer.byteLength(src);
    parts.push(minifyJs(src));
  }
  const out = parts.join('\n') + '\n';
  fs.writeFileSync(path.join(ROOT, 'public', 'app.js'), out);
  return { bytes: Buffer.byteLength(out), rawBytes, modules: ORDER.length };
}
if (require.main === module) {
  const r = build();
  console.log(`built public/app.js (${r.bytes} bytes, minified from ${r.rawBytes}) from ${r.modules} modules`);
}
module.exports = { build, minifyJs };

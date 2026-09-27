'use strict';
/**
 * App shell layout: topbar, page, footer, mobile tabbar, toasts, modal + QR modal.
 * Admin console stays direct-URL only — no public links here.
 * Assets versioned via ASSET_VERSION so CSS/JS splits bust correctly.
 */
const fs = require('fs');
const path = require('path');
const { esc } = require('./esc');
const { ICON, FAVICON } = require('./icons');

const ASSET_VERSION = 16;

/* Above-the-fold critical CSS, inlined for <50ms FCP with zero render-blocking
 * roundtrips. Built artifact (public/critical.css); empty fallback keeps the
 * async full stylesheet as the safety net. Loaded once at boot. */
let CRITICAL_CSS = '';
try {
  CRITICAL_CSS = fs.readFileSync(path.join(__dirname, '..', '..', 'public', 'critical.css'), 'utf8').trim();
} catch (e) {
  CRITICAL_CSS = '';
}

function publicShell(req) {
  return { account: !!req.siteAccount, verified: !!req.user };
}

function layout({ title, body, head = '', account = false, verified = false }) {
  const accountLink = !account
    ? `<a class="nav-btn" href="/login" data-nav="account">Sign in</a>`
    : verified
      ? `<a class="nav-btn active-state" href="/dashboard" data-nav="account">${ICON.music}<span>Dashboard</span></a>`
      : `<a class="nav-btn active-state" href="/verify" data-nav="account">${ICON.shield}<span>Verify</span></a>`;

  const accountTab = !account
    ? `<a href="/login" data-nav="account">${ICON.user}<span>Sign in</span></a>`
    : verified
      ? `<a href="/dashboard" data-nav="account">${ICON.music}<span>Dashboard</span></a>`
      : `<a href="/verify" data-nav="account">${ICON.shield}<span>Verify</span></a>`;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
<meta name="theme-color" content="#000000" />
<meta name="description" content="LastWave Addons — High fidelity audio streaming infrastructure for LastWave." />
<title>${esc(title)} · LastWave Addons</title>
<link rel="icon" href="${FAVICON}" />
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600;700&display=swap" rel="stylesheet" media="print" onload="this.media='all'" />
${CRITICAL_CSS ? `<style>${CRITICAL_CSS}</style>` : ''}
<link rel="preload" href="/public/style.css?v=${ASSET_VERSION}" as="style" onload="this.onload=null;this.rel='stylesheet'" />
<noscript><link rel="stylesheet" href="/public/style.css?v=${ASSET_VERSION}" /></noscript>
${head}
</head>
<body data-account="${account ? 'in' : 'out'}">
<canvas id="celestial-canvas" aria-hidden="true"></canvas>
<header class="topbar">
  <div class="topbar-in">
    <a class="brand" href="/" aria-label="LastWave Addons home">
      <span class="mark">${ICON.mark}</span>
      <span class="wordmark">LastWave</span>
      <span class="brand-badge">Addons</span>
    </a>
    <nav class="topnav" aria-label="Primary">
      <a class="nav-link" href="/" data-nav="home">Home</a>
      ${accountLink}
    </nav>
  </div>
</header>
<main class="page">${body}</main>
<footer class="footer">
  <div class="footer-in">
    <div class="footer-brand">
      <span class="footer-logo">${ICON.waveform}</span>
      <span>LastWave Addons Engine</span>
    </div>
    <div class="footer-meta">
      <span class="status-indicator"><span class="pulse-dot" aria-hidden="true"></span>Operational</span>
      <span class="sep" aria-hidden="true">·</span>
      <a href="https://github.com/Clash-Projects/LastWave-Native" target="_blank" rel="noopener">GitHub</a>
    </div>
  </div>
</footer>
<nav class="tabbar" aria-label="Primary mobile">
  <a href="/" data-nav="home"><svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg><span>Home</span></a>
  ${accountTab}
</nav>
<div class="toasts" id="toasts" aria-live="polite" aria-atomic="false"></div>
<div class="modal-backdrop" id="modal-backdrop" hidden>
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" aria-describedby="modal-desc">
    <h2 id="modal-title"></h2>
    <p id="modal-desc"></p>
    <div class="modal-actions">
      <button class="btn secondary" id="modal-cancel" type="button">Cancel</button>
      <button class="btn danger-solid" id="modal-confirm" type="button">Confirm</button>
    </div>
  </div>
</div>
<div class="modal-backdrop" id="qr-backdrop" hidden>
  <div class="modal modal-qr" role="dialog" aria-modal="true" aria-labelledby="qr-title">
    <h2 id="qr-title">Pair mobile app</h2>
    <p id="qr-desc">Scan with your phone camera to open this addon URL instantly.</p>
    <div class="qr-big"><img id="qr-img" alt="Addon URL QR code" width="224" height="224" /></div>
    <code class="mono small" id="qr-url" style="overflow-wrap:anywhere;display:block;margin-bottom:16px"></code>
    <div class="modal-actions">
      <button class="btn secondary" id="qr-close" type="button">Close</button>
      <button class="btn primary" id="qr-copy" type="button">Copy link</button>
    </div>
  </div>
</div>
<script src="/public/app.js?v=${ASSET_VERSION}" defer></script>
</body>
</html>`;
}

module.exports = { layout, publicShell, ASSET_VERSION };

'use strict';

/**
 * LastWave Addons — entry point.
 *
 * Per-account addon service for LastWave:
 *  - Web: / (home), /login, /verify/*, /dashboard, /admin
 *  - Addon (per token): /a/:token/{manifest.json,search,stream/:id,media/:file}
 */

const path = require('path');
const express = require('express');
const cookieParser = require('cookie-parser');
const config = require('./config');
const { attachUser } = require('./auth');
const { gzipCompress, staticCacheHeaders } = require('./compress');

const webRoutes = require('./routes/web');
const verifyRoutes = require('./routes/verify');
const passwordRoutes = require('./routes/password');
const addonRoutes = require('./routes/addon');
const adminRoutes = require('./routes/admin');
const githubRoutes = require('./routes/github');
const { startTelegramBot } = require('./bots/telegram');
const { startDiscordBot } = require('./bots/discord');

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);

app.use(cookieParser(config.sessionSecret));
app.use(express.urlencoded({ extended: false }));

// Universal CORS for all clients, WebViews, and native apps
app.use((req, res, next) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'GET, HEAD, POST, OPTIONS');
  res.set('Access-Control-Allow-Headers', '*');
  // Fail-closed hardening headers (no effect on addon JSON clients).
  res.set('X-Content-Type-Options', 'nosniff');
  res.set('Referrer-Policy', 'no-referrer');
  res.set('X-Frame-Options', 'DENY');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  const start = Date.now();
  res.on('finish', () => {
    console.log(`[TRAFFIC] ${req.method} ${req.originalUrl} -> ${res.statusCode} (${Date.now() - start}ms) [UA: ${req.get('user-agent') || 'none'}]`);
  });
  next();
});

// Zero-dep gzip for text responses + immutable caching for version-busted bundles.
app.use(gzipCompress());
app.use('/public', express.static(path.join(__dirname, '..', 'public'), {
  maxAge: '1h',
  setHeaders: staticCacheHeaders,
}));
app.use(attachUser);

app.get('/health', (_req, res) => res.json({ ok: true, time: new Date().toISOString() }));

app.use('/', webRoutes);
app.use('/', verifyRoutes);
app.use('/', passwordRoutes);
app.use('/', addonRoutes);
app.use('/', adminRoutes);
app.use('/', githubRoutes);

// 404 fallback (JSON for /a/* and /admin/api/*, HTML otherwise)
app.use((req, res) => {
  if (req.path.startsWith('/a/') || req.path.startsWith('/admin/api/')) {
    return res.status(404).json({ error: 'Not found.' });
  }
  res.status(404).send('Not found. <a href="/">Home</a>');
});

// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  console.error('[error]', err && err.message ? err.message : err);
  res.status(500).json({ error: 'Internal error.' });
});

const server = app.listen(config.port, () => {
  console.log(`[lastwave-addon] listening on :${config.port}`);
  console.log(`[lastwave-addon] base URL: ${config.baseUrl}`);
  console.log(`[lastwave-addon] upstream: ${config.upstreamBaseUrl}`);
  console.log(`[lastwave-addon] daily limit: ${config.dailySongLimit}/day per account`);
  if (!config.telegramBotToken) console.log('[lastwave-addon] Telegram login NOT configured.');
  if (!config.discordClientId) console.log('[lastwave-addon] Discord OAuth NOT configured.');
  if (require('./clientauth').lockEnabled()) {
    console.log('[lastwave-addon] client lock ON — addon URLs require the app key signature.');
  } else {
    console.log('[lastwave-addon] WARNING: ADDON_CLIENT_SECRET unset — addon URLs work in any player.');
  }
  // Channel-gated verification bots (best-effort: web still serves if bots are down).
  startTelegramBot().catch((e) => console.error('[telegram-bot] failed to start:', e.message));
  startDiscordBot().catch((e) => console.error('[discord-bot] failed to start:', e.message));
  // GitHub star-bonus re-verification (unstar/revoke clears the bonus).
  // Daily cadence + one delayed pass shortly after boot (catches downtime).
  try {
    const gh = require('./github');
    if (gh.bonusEnabled()) {
      const run = () => gh.recheckStarBonuses().catch((e) => console.error('[github-stars] recheck failed:', e.message));
      setTimeout(run, 60 * 1000);
      setInterval(run, 24 * 60 * 60 * 1000);
    } else {
      console.log('[github-stars] GitHub OAuth NOT configured (GITHUB_CLIENT_ID/SECRET). Star bonus disabled.');
    }
  } catch (e) {
    console.error('[github-stars] scheduler failed to start:', e.message);
  }
});

module.exports = { app, server };

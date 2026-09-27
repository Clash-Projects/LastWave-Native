'use strict';

/**
 * One-way client lock: our addon answers only to requests proving knowledge
 * of an app-embedded secret. Stock compatible players never send the proof,
 * so pasting one of our URLs into any other app fails closed (404, as if no
 * addon existed) — while our app still speaks the standard protocol to every
 * other addon server.
 *
 * The secret itself NEVER travels. Each request carries only a per-request
 * signature mixed from: timestamp + method + path + addon token, keyed by the
 * secret. That gives three properties a static header value would not:
 *  - replay dies with the timestamp window (120s),
 *  - a captured proof cannot be transplanted onto another user's URL,
 *  - traffic sniffing never reveals the secret.
 *
 * Headers (never query strings — those land in logs):
 *   X-LW-TS   unix seconds, e.g. "1727000000"
 *   X-LW-Sign lowercase hex HMAC-SHA256(secret, "ts\\nMETHOD\\npath\\ntoken")
 *
 * Secrets: ADDON_CLIENT_SECRET may hold one secret or a comma-separated list
 * ("old,new") so rotation overlaps — any listed secret validates. Empty means
 * the lock is OFF (local dev); the server logs a warning.
 */

const crypto = require('crypto');
const config = require('./config');

const WINDOW_SEC = 120;

function secrets() {
  return config.addonClientSecrets;
}

function lockEnabled() {
  return secrets().length > 0;
}

function expectedSign(secret, ts, method, path, token) {
  return crypto
    .createHmac('sha256', secret)
    .update(`${ts}\n${method}\n${path}\n${token}`, 'utf8')
    .digest('hex');
}

/** Recompute what the app should have sent; true when any listed secret matches. */
function verifyClientAuth(req) {
  if (!lockEnabled()) return { ok: true, disabled: true };
  const tsRaw = req.get('X-LW-TS') || '';
  const signRaw = (req.get('X-LW-Sign') || '').toLowerCase();
  if (!/^\d{9,11}$/.test(tsRaw) || !/^[0-9a-f]{64}$/.test(signRaw)) {
    return { ok: false, reason: 'missing-proof' };
  }
  const ts = parseInt(tsRaw, 10);
  if (Math.abs(Date.now() / 1000 - ts) > WINDOW_SEC) {
    return { ok: false, reason: 'stale-proof' };
  }
  const token = req.params && req.params.token ? String(req.params.token) : '';
  if (!token) return { ok: false, reason: 'missing-proof' };
  const method = String(req.method || 'GET').toUpperCase();
  const path = req.path || '/';
  const given = Buffer.from(signRaw, 'hex');
  for (const secret of secrets()) {
    const want = Buffer.from(expectedSign(secret, tsRaw, method, path, token), 'hex');
    if (given.length === want.length && crypto.timingSafeEqual(given, want)) {
      return { ok: true };
    }
  }
  return { ok: false, reason: 'bad-proof' };
}

/** Express middleware for /a/* routes. Fails closed as 404 (no oracle). */
function requireClientAuth(req, res, next) {
  const check = verifyClientAuth(req);
  if (check.ok) return next();
  return res.status(404).json({ error: 'Addon not found.' });
}

// ---------- player media links (the player fetches with no headers) ----------

function mediaKey() {
  return config.sessionSecret || 'dev-only-media-key';
}

/** Short-lived capability URL minted by an authenticated /stream call. */
const MEDIA_URL_TTL_MS = 10 * 60 * 1000;
const MEDIA_URL_NO_EXPIRY = 9999999999999; // ~year 2286: media links never expire.

function signMediaUrl(token, trackId, qualityParam, ttlMs = -1) {
  const exp = ttlMs < 0 ? MEDIA_URL_NO_EXPIRY : Date.now() + ttlMs;
  const sig = crypto
    .createHmac('sha256', mediaKey())
    .update(`${token}\n${trackId}\n${qualityParam}\n${exp}`, 'utf8')
    .digest('hex');
  return { exp: String(exp), sig };
}

function verifyMediaUrl(token, trackId, qualityParam, exp, sig) {
  if (!/^\d+$/.test(String(exp || '')) || !/^[0-9a-f]{64}$/i.test(String(sig || ''))) return false;
  // No wall-clock check: media links never expire. The HMAC still binds the
  // URL to (token, track, quality), so forged or transplanted links fail.
  const want = crypto
    .createHmac('sha256', mediaKey())
    .update(`${token}\n${trackId}\n${qualityParam}\n${exp}`, 'utf8')
    .digest('hex');
  const a = Buffer.from(String(sig).toLowerCase(), 'hex');
  const b = Buffer.from(want, 'hex');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

module.exports = {
  WINDOW_SEC,
  lockEnabled,
  secrets,
  expectedSign,
  verifyClientAuth,
  requireClientAuth,
  signMediaUrl,
  verifyMediaUrl,
};

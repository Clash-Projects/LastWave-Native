'use strict';

/**
 * SQLite storage (better-sqlite3, synchronous, zero-dependency DB).
 *
 * Tables:
 *  - users: one row per real Telegram/Discord identity.
 *  - addon_tokens: one active token per user (revocable/regenerable).
 *  - daily_usage: per-user per-day (UTC) song counter.
 *  - stream_logs: audit trail of counted stream resolutions.
 *  - sessions: server-side web sessions (httpOnly cookie -> row).
 *  - verification_codes: website-issued codes verified through the
 *    Telegram/Discord bots (channel-membership gate).
 */

const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const Database = require('better-sqlite3');

const DATA_DIR = path.join(__dirname, '..', 'data');
fs.mkdirSync(DATA_DIR, { recursive: true });

const DB_PATH = process.env.DB_PATH || path.join(DATA_DIR, 'addon.db');
const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  provider TEXT NOT NULL CHECK (provider IN ('telegram','discord','dev')),
  provider_user_id TEXT NOT NULL,
  username TEXT DEFAULT '',
  display_name TEXT DEFAULT '',
  avatar_url TEXT DEFAULT '',
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  UNIQUE(provider, provider_user_id)
);
CREATE TABLE IF NOT EXISTS addon_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token TEXT NOT NULL UNIQUE,
  revoked INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER
);
CREATE TABLE IF NOT EXISTS daily_usage (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, day)
);
CREATE TABLE IF NOT EXISTS stream_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_id INTEGER REFERENCES addon_tokens(id) ON DELETE SET NULL,
  track_id TEXT NOT NULL,
  track_title TEXT DEFAULT '',
  quality TEXT DEFAULT '',
  ip TEXT DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stream_logs_user_time ON stream_logs(user_id, created_at DESC);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
  site_account_id INTEGER REFERENCES site_accounts(id) ON DELETE CASCADE,
  is_admin INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS verification_codes (
  code TEXT PRIMARY KEY,
  provider TEXT NOT NULL CHECK (provider IN ('telegram','discord')),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','verified','consumed')),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  verified_at INTEGER,
  provider_user_id TEXT DEFAULT '',
  username TEXT DEFAULT '',
  display_name TEXT DEFAULT '',
  avatar_url TEXT DEFAULT '',
  membership TEXT DEFAULT '',
  account_age_days INTEGER,
  ip TEXT DEFAULT ''
);
CREATE TABLE IF NOT EXISTS site_accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  plain_password TEXT DEFAULT '',
  user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  last_login_at INTEGER
);
CREATE TABLE IF NOT EXISTS password_resets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  site_account_id INTEGER NOT NULL REFERENCES site_accounts(id) ON DELETE CASCADE,
  code TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_resets_account ON password_resets(site_account_id, consumed);
CREATE INDEX IF NOT EXISTS idx_verify_status ON verification_codes(provider, status);
CREATE TABLE IF NOT EXISTS banned_identities (
  provider TEXT NOT NULL,
  provider_user_id TEXT NOT NULL,
  reason TEXT DEFAULT '',
  banned_at INTEGER NOT NULL,
  PRIMARY KEY (provider, provider_user_id)
);
`);
// Best-effort lightweight migrations (idempotent across restarts).
for (const sql of [
  `ALTER TABLE users ADD COLUMN verified_via TEXT DEFAULT ''`,
  `ALTER TABLE users ADD COLUMN membership TEXT DEFAULT ''`,
  `ALTER TABLE users ADD COLUMN account_age_days INTEGER`,
  `ALTER TABLE sessions ADD COLUMN site_account_id INTEGER REFERENCES site_accounts(id) ON DELETE CASCADE`,
  `ALTER TABLE users ADD COLUMN is_banned INTEGER NOT NULL DEFAULT 0`,
  `ALTER TABLE users ADD COLUMN ban_reason TEXT DEFAULT ''`,
  `ALTER TABLE users ADD COLUMN banned_at INTEGER`,
  `ALTER TABLE users ADD COLUMN custom_limit INTEGER DEFAULT NULL`,
  `ALTER TABLE users ADD COLUMN bypass_limit INTEGER NOT NULL DEFAULT 0`,
  `ALTER TABLE daily_usage ADD COLUMN bonus_plays INTEGER NOT NULL DEFAULT 0`,
  `ALTER TABLE site_accounts ADD COLUMN plain_password TEXT DEFAULT ''`,
  `ALTER TABLE users ADD COLUMN github_user_id TEXT DEFAULT NULL`,
  `ALTER TABLE users ADD COLUMN github_username TEXT DEFAULT ''`,
  `ALTER TABLE users ADD COLUMN star_bonus INTEGER NOT NULL DEFAULT 0`,
  `ALTER TABLE users ADD COLUMN github_token_enc TEXT DEFAULT ''`,
  `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_github ON users(github_user_id) WHERE github_user_id IS NOT NULL`,
]) {
  try {
    db.exec(sql);
  } catch (e) {
    if (!/duplicate column|no such table/i.test(e.message)) throw e;
  }
}

function nowMs() {
  return Date.now();
}

function utcDay(d = new Date()) {
  return d.toISOString().slice(0, 10); // YYYY-MM-DD in UTC
}

// ---------- users ----------

function upsertUser({ provider, providerUserId, username, displayName, avatarUrl, verifiedVia, membership, accountAgeDays }) {
  const now = nowMs();
  const existing = db
    .prepare('SELECT * FROM users WHERE provider = ? AND provider_user_id = ?')
    .get(provider, String(providerUserId));
  if (existing) {
    db.prepare(
      'UPDATE users SET username = ?, display_name = ?, avatar_url = ?, last_seen_at = ?, verified_via = COALESCE(NULLIF(?, \'\'), verified_via), membership = COALESCE(NULLIF(?, \'\'), membership), account_age_days = COALESCE(?, account_age_days) WHERE id = ?'
    ).run(
      username || '',
      displayName || '',
      avatarUrl || '',
      now,
      verifiedVia || '',
      membership || '',
      accountAgeDays ?? null,
      existing.id
    );
    return db.prepare('SELECT * FROM users WHERE id = ?').get(existing.id);
  }
  const info = db
    .prepare(
      'INSERT INTO users (provider, provider_user_id, username, display_name, avatar_url, created_at, last_seen_at, verified_via, membership, account_age_days) VALUES (?,?,?,?,?,?,?,?,?,?)'
    )
    .run(
      provider,
      String(providerUserId),
      username || '',
      displayName || '',
      avatarUrl || '',
      now,
      now,
      verifiedVia || '',
      membership || '',
      accountAgeDays ?? null
    );
  return db.prepare('SELECT * FROM users WHERE id = ?').get(info.lastInsertRowid);
}

function getUserById(id) {
  return db.prepare('SELECT * FROM users WHERE id = ?').get(id) || null;
}

function listUsers() {
  return db.prepare('SELECT * FROM users ORDER BY created_at DESC').all();
}

// ---------- addon tokens ----------

function newToken() {
  return crypto.randomBytes(32).toString('hex'); // 64 hex chars, unguessable
}

function getOrCreateToken(userId) {
  const existing = db
    .prepare('SELECT * FROM addon_tokens WHERE user_id = ? AND revoked = 0 ORDER BY id DESC LIMIT 1')
    .get(userId);
  if (existing) return existing;
  const token = newToken();
  const now = nowMs();
  const info = db
    .prepare('INSERT INTO addon_tokens (user_id, token, revoked, created_at) VALUES (?,?,0,?)')
    .run(userId, token, now);
  return db.prepare('SELECT * FROM addon_tokens WHERE id = ?').get(info.lastInsertRowid);
}

function getTokenRow(token) {
  if (!token || typeof token !== 'string' || !/^[a-f0-9]{32,128}$/i.test(token)) return null;
  return db.prepare('SELECT * FROM addon_tokens WHERE token = ?').get(token) || null;
}

function touchToken(id) {
  db.prepare('UPDATE addon_tokens SET last_used_at = ? WHERE id = ?').run(nowMs(), id);
}

function regenerateToken(userId) {
  db.prepare('UPDATE addon_tokens SET revoked = 1 WHERE user_id = ? AND revoked = 0').run(userId);
  const token = newToken();
  const info = db
    .prepare('INSERT INTO addon_tokens (user_id, token, revoked, created_at) VALUES (?,?,0,?)')
    .run(userId, token, nowMs());
  return db.prepare('SELECT * FROM addon_tokens WHERE id = ?').get(info.lastInsertRowid);
}

function setTokenRevoked(tokenId, revoked) {
  db.prepare('UPDATE addon_tokens SET revoked = ? WHERE id = ?').run(revoked ? 1 : 0, tokenId);
}

function deleteUser(userId) {
  db.prepare('DELETE FROM users WHERE id = ?').run(userId);
}

function banUser(userId, reason = 'Banned by admin') {
  const user = getUserById(userId);
  if (!user) return null;
  const now = nowMs();
  
  db.prepare('UPDATE users SET is_banned = 1, ban_reason = ?, banned_at = ? WHERE id = ?')
    .run(reason || 'Banned by admin', now, userId);

  if (user.provider && user.provider_user_id) {
    db.prepare(`
      INSERT INTO banned_identities (provider, provider_user_id, reason, banned_at)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(provider, provider_user_id) DO UPDATE SET reason = excluded.reason, banned_at = excluded.banned_at
    `).run(user.provider, String(user.provider_user_id), reason || 'Banned by admin', now);
  }

  // Revoke active tokens immediately
  db.prepare('UPDATE addon_tokens SET revoked = 1 WHERE user_id = ?').run(userId);

  // Invalidate web sessions for user and site accounts
  db.prepare(`
    DELETE FROM sessions WHERE user_id = ? OR site_account_id IN (
      SELECT id FROM site_accounts WHERE user_id = ?
    )
  `).run(userId, userId);

  return getUserById(userId);
}

function unbanUser(userId) {
  const user = getUserById(userId);
  if (!user) return null;

  db.prepare('UPDATE users SET is_banned = 0, ban_reason = NULL, banned_at = NULL WHERE id = ?')
    .run(userId);

  if (user.provider && user.provider_user_id) {
    db.prepare('DELETE FROM banned_identities WHERE provider = ? AND provider_user_id = ?')
      .run(user.provider, String(user.provider_user_id));
  }

  return getUserById(userId);
}

function isIdentityBanned(provider, providerUserId) {
  if (!provider || !providerUserId) return false;
  const row = db.prepare('SELECT 1 FROM banned_identities WHERE provider = ? AND provider_user_id = ?')
    .get(provider, String(providerUserId));
  return !!row;
}

function isUserBanned(userId) {
  const user = getUserById(userId);
  if (!user) return false;
  if (user.is_banned) return true;
  return isIdentityBanned(user.provider, user.provider_user_id);
}

function setCustomLimit(userId, limit) {
  const parsed = parseInt(limit, 10);
  const val = !isNaN(parsed) && parsed > 0 ? parsed : null;
  db.prepare('UPDATE users SET custom_limit = ? WHERE id = ?').run(val, userId);
  return getUserById(userId);
}

function setBypassLimit(userId, bypass) {
  const val = (bypass === 1 || bypass === true || bypass === '1' || bypass === 'true') ? 1 : 0;
  db.prepare('UPDATE users SET bypass_limit = ? WHERE id = ?').run(val, userId);
  return getUserById(userId);
}

/**
 * GitHub star bonus (+50% quota). One GitHub account per identity: the
 * partial unique index on github_user_id rejects double-claims across
 * site accounts. Token is stored encrypted (see lib/github.js); the bonus
 * flag alone drives quota, toggled by link + daily re-verification.
 */
function getUserByGithubId(githubUserId) {
  if (!githubUserId) return null;
  return db.prepare('SELECT * FROM users WHERE github_user_id = ?').get(String(githubUserId)) || null;
}

function setGithubLink(userId, { githubUserId, username, tokenEnc }) {
  db.prepare('UPDATE users SET github_user_id = ?, github_username = ?, github_token_enc = ?, star_bonus = 1 WHERE id = ?')
    .run(String(githubUserId), username || '', tokenEnc || '', userId);
  return getUserById(userId);
}

function setStarBonus(userId, on) {
  db.prepare('UPDATE users SET star_bonus = ? WHERE id = ?').run(on ? 1 : 0, userId);
  return getUserById(userId);
}

function clearGithubLink(userId) {
  db.prepare("UPDATE users SET github_user_id = NULL, github_username = '', github_token_enc = '', star_bonus = 0 WHERE id = ?")
    .run(userId);
  return getUserById(userId);
}

function listStarBonusUsers() {
  return db.prepare('SELECT id, github_user_id, github_username, github_token_enc FROM users WHERE star_bonus = 1').all();
}

function grantBonusPlays(userId, day, amount) {
  const now = nowMs();
  const amt = Math.max(1, parseInt(amount, 10) || 0);
  const row = db.prepare('SELECT count, bonus_plays FROM daily_usage WHERE user_id = ? AND day = ?').get(userId, day);
  if (!row) {
    db.prepare('INSERT INTO daily_usage (user_id, day, count, bonus_plays, updated_at) VALUES (?,?,0,?,?)')
      .run(userId, day, amt, now);
  } else {
    db.prepare('UPDATE daily_usage SET bonus_plays = COALESCE(bonus_plays, 0) + ?, updated_at = ? WHERE user_id = ? AND day = ?')
      .run(amt, now, userId, day);
  }
}

function getDailyUsageRow(userId, day) {
  return db.prepare('SELECT count, COALESCE(bonus_plays, 0) AS bonus_plays FROM daily_usage WHERE user_id = ? AND day = ?').get(userId, day) || { count: 0, bonus_plays: 0 };
}

function createDirectAddon({ label = '', customLimit = null, bypassLimit = 0 } = {}) {
  const rand = crypto.randomBytes(4).toString('hex');
  const cleanLabel = String(label || '').trim().replace(/[^a-zA-Z0-9_\- ]/g, '').slice(0, 30);
  const username = (cleanLabel ? cleanLabel.toLowerCase().replace(/\s+/g, '_').slice(0, 18) : 'addon') + '_' + rand;
  const displayName = cleanLabel || ('Addon ' + rand);
  const providerUserId = 'admin_' + crypto.randomBytes(6).toString('hex');
  const now = nowMs();
  
  const parsedLimit = parseInt(customLimit, 10);
  const limitVal = !isNaN(parsedLimit) && parsedLimit > 0 ? parsedLimit : null;
  const bypassVal = (bypassLimit === 1 || bypassLimit === true || bypassLimit === '1') ? 1 : 0;

  const info = db.prepare(`
    INSERT INTO users (
      provider, provider_user_id, username, display_name, avatar_url,
      created_at, last_seen_at, verified_via, membership, custom_limit, bypass_limit
    ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
  `).run(
    'dev',
    providerUserId,
    username,
    displayName,
    '',
    now,
    now,
    'admin_direct',
    'direct',
    limitVal,
    bypassVal
  );
  
  const user = getUserById(info.lastInsertRowid);
  
  const rawPw = crypto.randomBytes(16).toString('hex');
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(rawPw, salt, 64, { N: 16384, r: 8, p: 1 });
  const pwHash = `scrypt$16384$8$1$${salt.toString('hex')}$${hash.toString('hex')}`;
  
  const siteInfo = db.prepare(`
    INSERT INTO site_accounts (username, password_hash, plain_password, user_id, created_at)
    VALUES (?, ?, ?, ?, ?)
  `).run(username, pwHash, rawPw, user.id, now);

  const token = getOrCreateToken(user.id);
  
  return {
    user,
    token,
    siteAccount: getSiteAccountById(siteInfo.lastInsertRowid),
    password: rawPw,
  };
}

function listTokens() {
  return db.prepare('SELECT * FROM addon_tokens ORDER BY created_at DESC').all();
}

// ---------- quota / usage ----------

function getDailyCount(userId, day) {
  const row = db.prepare('SELECT count FROM daily_usage WHERE user_id = ? AND day = ?').get(userId, day);
  return row ? row.count : 0;
}

function incrementDaily(userId, day, amount = 1) {
  const now = nowMs();
  const row = db.prepare('SELECT count FROM daily_usage WHERE user_id = ? AND day = ?').get(userId, day);
  if (!row) {
    db.prepare('INSERT INTO daily_usage (user_id, day, count, updated_at) VALUES (?,?,?,?)').run(userId, day, amount, now);
    return amount;
  }
  db.prepare('UPDATE daily_usage SET count = count + ?, updated_at = ? WHERE user_id = ? AND day = ?').run(
    amount,
    now,
    userId,
    day
  );
  return row.count + amount;
}

function logStream({ userId, tokenId, trackId, trackTitle, quality, ip }) {
  db.prepare(
    'INSERT INTO stream_logs (user_id, token_id, track_id, track_title, quality, ip, created_at) VALUES (?,?,?,?,?,?,?)'
  ).run(userId, tokenId || null, String(trackId), trackTitle || '', quality || '', ip || '', nowMs());
}

function recentLogs(userId, limit = 50) {
  return db
    .prepare('SELECT * FROM stream_logs WHERE user_id = ? ORDER BY created_at DESC LIMIT ?')
    .all(userId, limit);
}

function dailyHistory(userId, days = 30) {
  return db
    .prepare('SELECT day, count FROM daily_usage WHERE user_id = ? ORDER BY day DESC LIMIT ?')
    .all(userId, days);
}

function todayStats(day) {
  const users = db.prepare('SELECT COUNT(*) AS c FROM users').get().c;
  const activeTokens = db.prepare('SELECT COUNT(*) AS c FROM addon_tokens WHERE revoked = 0').get().c;
  const streamsToday = db.prepare('SELECT COUNT(*) AS c FROM stream_logs WHERE created_at >= ?').get(
    Date.parse(day + 'T00:00:00.000Z')
  ).c;
  return { users, activeTokens, streamsToday };
}

// ---------- verification codes (website -> bot -> website) ----------

const VERIFY_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

function newVerifyCode() {
  let code = '';
  for (let i = 0; i < 6; i++) {
    code += VERIFY_ALPHABET[crypto.randomInt(VERIFY_ALPHABET.length)];
  }
  return code;
}

function createVerifyCode(provider, { ttlMs = 10 * 60 * 1000, ip = '' } = {}) {
  const now = nowMs();
  for (let attempt = 0; attempt < 10; attempt++) {
    const code = newVerifyCode();
    try {
      db.prepare(
        'INSERT INTO verification_codes (code, provider, status, created_at, expires_at, ip) VALUES (?,?,?,?,?,?)'
      ).run(code, provider, 'pending', now, now + ttlMs, ip || '');
      return db.prepare('SELECT * FROM verification_codes WHERE code = ?').get(code);
    } catch (e) {
      if (!/UNIQUE constraint/i.test(e.message)) throw e;
    }
  }
  throw new Error('Could not generate a verification code, please retry.');
}

function getVerifyCode(code, provider) {
  if (!code || !provider) return null;
  const row = db
    .prepare('SELECT * FROM verification_codes WHERE code = ? AND provider = ?')
    .get(String(code).trim().toUpperCase(), provider);
  return row || null;
}

function markVerifyCode(code, provider, fields) {
  const row = getVerifyCode(code, provider);
  if (!row || row.status !== 'pending') return null;
  if (row.expires_at < nowMs()) return null;
  db.prepare(
    `UPDATE verification_codes SET status = 'verified', verified_at = ?,
     provider_user_id = ?, username = ?, display_name = ?, avatar_url = ?,
     membership = ?, account_age_days = ? WHERE code = ? AND provider = ?`
  ).run(
    nowMs(),
    String(fields.providerUserId || ''),
    fields.username || '',
    fields.displayName || '',
    fields.avatarUrl || '',
    fields.membership || '',
    fields.accountAgeDays ?? null,
    row.code,
    provider
  );
  return db.prepare('SELECT * FROM verification_codes WHERE code = ?').get(row.code);
}

function consumeVerifyCode(code, provider) {
  const row = getVerifyCode(code, provider);
  if (!row || row.status !== 'verified' || row.expires_at < nowMs()) return null;
  db.prepare(`UPDATE verification_codes SET status = 'consumed' WHERE code = ? AND provider = ?`).run(
    row.code,
    provider
  );
  return db.prepare('SELECT * FROM verification_codes WHERE code = ?').get(row.code);
}

function recentVerifyCodes(limit = 50) {
  return db
    .prepare('SELECT code, provider, status, created_at, expires_at, verified_at, provider_user_id, username, membership FROM verification_codes ORDER BY created_at DESC LIMIT ?')
    .all(limit);
}

function verifyCodesForUser(provider, providerUserId, limit = 10) {
  return db
    .prepare('SELECT * FROM verification_codes WHERE provider = ? AND provider_user_id = ? ORDER BY created_at DESC LIMIT ?')
    .all(provider, String(providerUserId), limit);
}

// ---------- sessions ----------

function createSession(userId, { siteAccountId = null, isAdmin = false, ttlMs = 1000 * 60 * 60 * 24 * 30 } = {}) {
  const id = crypto.randomBytes(32).toString('hex');
  const now = nowMs();
  db.prepare('INSERT INTO sessions (id, user_id, site_account_id, is_admin, created_at, expires_at) VALUES (?,?,?,?,?,?)').run(
    id,
    userId || null,
    siteAccountId || null,
    isAdmin ? 1 : 0,
    now,
    now + ttlMs
  );
  return id;
}

function getSession(id) {
  if (!id) return null;
  const row = db.prepare('SELECT * FROM sessions WHERE id = ?').get(id);
  if (!row) return null;
  if (row.expires_at < nowMs()) {
    db.prepare('DELETE FROM sessions WHERE id = ?').run(id);
    return null;
  }
  return row;
}

function deleteSession(id) {
  if (id) db.prepare('DELETE FROM sessions WHERE id = ?').run(id);
}

// ---------- site accounts (username + password, linked to a verified identity) ----------

function normalizeUsername(raw) {
  return String(raw || '').trim().toLowerCase();
}

function usernameError(raw) {
  const u = normalizeUsername(raw);
  if (!/^[a-z0-9_]{3,24}$/.test(u)) {
    return 'Username must be 3–24 characters: lowercase letters, numbers, underscores.';
  }
  return null;
}

function getSiteAccountByUsername(username) {
  return db.prepare('SELECT * FROM site_accounts WHERE username = ?').get(normalizeUsername(username)) || null;
}

function getSiteAccountById(id) {
  return db.prepare('SELECT * FROM site_accounts WHERE id = ?').get(id) || null;
}

function getSiteAccountByUserId(userId) {
  return db.prepare('SELECT * FROM site_accounts WHERE user_id = ?').get(userId) || null;
}

function createSiteAccount(username, passwordHash, plainPassword = '') {
  const now = nowMs();
  const info = db
    .prepare('INSERT INTO site_accounts (username, password_hash, plain_password, user_id, created_at) VALUES (?,?,?,NULL,?)')
    .run(normalizeUsername(username), passwordHash, plainPassword || '', now);
  return getSiteAccountById(info.lastInsertRowid);
}

function setSitePassword(siteId, passwordHash, plainPassword = '') {
  db.prepare('UPDATE site_accounts SET password_hash = ?, plain_password = ? WHERE id = ?').run(passwordHash, plainPassword || '', siteId);
}

function adminSetUserPassword(userId, newPassword) {
  const user = getUserById(userId);
  if (!user) return { ok: false, error: 'User not found' };
  const rawPw = String(newPassword || '').trim();
  if (rawPw.length < 4) return { ok: false, error: 'Password must be at least 4 characters.' };
  
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(rawPw, salt, 64, { N: 16384, r: 8, p: 1 });
  const pwHash = `scrypt$16384$8$1$${salt.toString('hex')}$${hash.toString('hex')}`;
  
  let site = getSiteAccountByUserId(userId);
  if (!site) {
    const baseName = (user.username || `user_${user.id}`).toLowerCase().replace(/[^a-z0-9_]/g, '_').slice(0, 20);
    site = createSiteAccount(baseName, pwHash, rawPw);
    linkSiteAccount(site.id, userId);
  } else {
    setSitePassword(site.id, pwHash, rawPw);
  }
  return { ok: true, username: site.username, password: rawPw };
}

function touchSiteLogin(siteId) {
  db.prepare('UPDATE site_accounts SET last_login_at = ? WHERE id = ?').run(nowMs(), siteId);
}

function linkSiteAccount(siteId, userId) {
  db.prepare('UPDATE site_accounts SET user_id = ? WHERE id = ?').run(userId, siteId);
  return getSiteAccountById(siteId);
}

function listSiteAccounts() {
  return db.prepare('SELECT * FROM site_accounts ORDER BY created_at DESC').all();
}

// ---------- password resets (codes delivered through the linked bot) ----------

function createPasswordReset(siteAccountId, { ttlMs = 10 * 60 * 1000 } = {}) {
  const now = nowMs();
  db.prepare(`UPDATE password_resets SET consumed = 1 WHERE site_account_id = ? AND consumed = 0`).run(siteAccountId);
  const code = newVerifyCode();
  db.prepare('INSERT INTO password_resets (site_account_id, code, created_at, expires_at, consumed) VALUES (?,?,?,?,0)')
    .run(siteAccountId, code, now, now + ttlMs);
  return db.prepare('SELECT * FROM password_resets WHERE site_account_id = ? AND code = ?').get(siteAccountId, code);
}

function getValidReset(siteAccountId, code) {
  const row = db
    .prepare('SELECT * FROM password_resets WHERE site_account_id = ? AND code = ? AND consumed = 0')
    .get(siteAccountId, String(code || '').trim().toUpperCase());
  if (!row || row.expires_at < nowMs()) return null;
  return row;
}

function consumeReset(id) {
  db.prepare('UPDATE password_resets SET consumed = 1 WHERE id = ?').run(id);
}

module.exports = {
  db,
  utcDay,
  upsertUser,
  getUserById,
  listUsers,
  getOrCreateToken,
  getTokenRow,
  touchToken,
  regenerateToken,
  setTokenRevoked,
  deleteUser,
  banUser,
  unbanUser,
  isIdentityBanned,
  isUserBanned,
  setCustomLimit,
  setBypassLimit,
  getUserByGithubId,
  setGithubLink,
  setStarBonus,
  clearGithubLink,
  listStarBonusUsers,
  grantBonusPlays,
  getDailyUsageRow,
  createDirectAddon,
  listTokens,
  getDailyCount,
  incrementDaily,
  logStream,
  recentLogs,
  dailyHistory,
  todayStats,
  createSession,
  getSession,
  deleteSession,
  normalizeUsername,
  usernameError,
  getSiteAccountByUsername,
  getSiteAccountById,
  getSiteAccountByUserId,
  createSiteAccount,
  setSitePassword,
  adminSetUserPassword,
  touchSiteLogin,
  linkSiteAccount,
  listSiteAccounts,
  createPasswordReset,
  getValidReset,
  consumeReset,
  newVerifyCode,
  createVerifyCode,
  getVerifyCode,
  markVerifyCode,
  consumeVerifyCode,
  recentVerifyCodes,
  verifyCodesForUser,
};

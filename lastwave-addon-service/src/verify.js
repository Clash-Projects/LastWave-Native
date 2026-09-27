'use strict';

/**
 * Shared verification helpers (pure, unit-testable).
 *
 * Flow: website issues a short code -> user sends it to the Telegram bot
 * (DM) or runs /verify in Discord -> bot checks channel/server membership
 * (-> Discord: 30-day account age) -> marks code verified -> user types the
 * same code back on the website to finish sign-in.
 */

const DISCORD_EPOCH_MS = 1420070400000n; // 2015-01-01T00:00:00Z

function normalizeCode(raw) {
  return String(raw || '').trim().toUpperCase();
}

function isCodeShape(raw) {
  return /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/.test(normalizeCode(raw));
}

/** Discord snowflake -> account creation timestamp (ms). Null when invalid. */
function discordCreatedAtMs(userId) {
  try {
    const id = BigInt(String(userId));
    if (id <= 0n) return null;
    return Number((id >> 22n) + DISCORD_EPOCH_MS);
  } catch {
    return null;
  }
}

function discordAccountAgeDays(userId, nowMs = Date.now()) {
  const created = discordCreatedAtMs(userId);
  if (created == null) return null;
  return (nowMs - created) / (1000 * 60 * 60 * 24);
}

function discordAgeOk(userId, minDays, nowMs = Date.now()) {
  const age = discordAccountAgeDays(userId, nowMs);
  if (age == null) return { ok: false, ageDays: null, reason: 'invalid-id' };
  if (age < minDays) return { ok: false, ageDays: age, reason: 'too-young' };
  return { ok: true, ageDays: age, reason: null };
}

/** Telegram getChatMember statuses that count as "joined". */
function telegramStatusIsMember(status, isMember) {
  const s = String(status || '').toLowerCase();
  if (['creator', 'administrator', 'member'].includes(s)) return true;
  if (s === 'restricted') return isMember !== false;
  return false; // left, kicked, unknown
}

function telegramInviteFor(channel, configuredUrl) {
  if (configuredUrl) return configuredUrl;
  const c = String(channel || '').trim();
  if (c.startsWith('@') && c.length > 1) return `https://t.me/${c.slice(1)}`;
  if (c && !c.startsWith('-')) return `https://t.me/${c}`;
  return '';
}

module.exports = {
  normalizeCode,
  isCodeShape,
  discordCreatedAtMs,
  discordAccountAgeDays,
  discordAgeOk,
  telegramStatusIsMember,
  telegramInviteFor,
};

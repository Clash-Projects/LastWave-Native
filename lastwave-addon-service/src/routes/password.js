'use strict';

/**
 * Forgot password: the reset code travels through whichever bot
 * (Telegram or Discord) the username verified with — never email.
 *
 *  GET  /forgot-password            -> username form
 *  POST /forgot-password            -> look up the linked identity, DM the code
 *  GET  /reset-password?username=   -> code + new password form
 *  POST /reset-password             -> verify code, set new password
 */

const express = require('express');
const config = require('../config');
const store = require('../db');
const { passwordError, hashPassword } = require('../auth');
const { normalizeCode, isCodeShape } = require('../verify');
const { esc, layout } = require('../html');
const { renderForgot, renderReset } = require('../views/password');
const telegramBot = require('../bots/telegram');
const discordBot = require('../bots/discord');

const router = express.Router();
const RESET_TTL_MS = 10 * 60 * 1000;

// Tiny per-IP rate limits (reset codes are 6 chars, 10-min life, single-use).
const buckets = new Map();
function hitRate(key, max, windowMs) {
  const now = Date.now();
  if (buckets.size > 1000) {
    for (const [k, v] of buckets) {
      if (v.reset < now) buckets.delete(k);
    }
  }
  const cur = buckets.get(key);
  if (!cur || cur.reset < now) {
    buckets.set(key, { count: 1, reset: now + windowMs });
    return true;
  }
  if (cur.count >= max) return false;
  cur.count += 1;
  return true;
}

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd) return fwd.split(',')[0].trim().slice(0, 64);
  return (req.ip || '').slice(0, 64);
}

function channelLabel(provider) {
  return provider === 'telegram' ? 'Telegram' : provider === 'discord' ? 'Discord' : null;
}

async function deliverReset(user, code) {
  const text = [
    `Your LastWave password reset code: ${code}`,
    'Valid for 10 minutes. Type it on the reset page with your new password.',
    "If you didn't ask for this, ignore it — your password stays as-is.",
  ].join('\n');
  if (user.provider === 'telegram') {
    await telegramBot.sendDirect(user.provider_user_id, text);
  } else if (user.provider === 'discord') {
    await discordBot.sendDirect(user.provider_user_id, text);
  } else {
    throw new Error('This account has no messaging channel linked.');
  }
}

router.get('/forgot-password', (req, res) => {
  res.send(layout({ title: 'Forgot password', body: renderForgot() }));
});

router.post('/forgot-password', express.urlencoded({ extended: false }), async (req, res) => {
  if (!hitRate(`forgot:${clientIp(req)}`, 5, 60 * 60 * 1000)) {
    return res.status(429).send(layout({
      title: 'Slow down',
      body: `<div class="page-narrow"><div class="page-head"><h1>Too many requests.</h1><p class="lede">Wait an hour and try again.</p></div></div>`,
    }));
  }
  const account = store.getSiteAccountByUsername(req.body.username);
  const user = account && account.user_id ? store.getUserById(account.user_id) : null;
  // No oracle: unknown usernames and unverified accounts get the same page.
  if (!account || !user || !channelLabel(user.provider)) {
    return res.send(layout({
      title: 'Check your DMs',
      body: `<div class="page-narrow"><div class="page-head"><h1>If that account exists, a code is on its way.</h1>
      <p class="lede">Verified accounts get the code by DM within a minute. Then <a href="/reset-password?username=${esc(store.normalizeUsername(req.body.username))}">enter it here</a>.</p></div></div>`,
    }));
  }
  try {
    const reset = store.createPasswordReset(account.id, { ttlMs: RESET_TTL_MS });
    await deliverReset(user, reset.code);
  } catch (e) {
    return res.status(502).send(layout({
      title: 'Could not send',
      body: `<div class="page-narrow"><div class="page-head"><h1>Couldn't reach you.</h1><p class="lede">${esc(e.message)}</p></div><div class="actions-row"><a class="btn primary" href="/forgot-password">Try again</a></div></div>`,
    }));
  }
  res.send(layout({
    title: 'Check your DMs',
    body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow">Code sent via ${esc(channelLabel(user.provider))}</p><h1>Check your ${esc(channelLabel(user.provider))} DMs.</h1>
    <p class="lede">A 10-minute reset code is waiting for <strong class="mono">@${esc(account.username)}</strong>. Nothing arrived? Make sure you can receive DMs from our bot, then request again.</p></div>
    <div class="actions-row"><a class="btn primary" href="/reset-password?username=${esc(account.username)}">Enter reset code</a></div></div>`,
  }));
});

router.get('/reset-password', (req, res) => {
  const username = store.normalizeUsername(req.query.username || '');
  res.send(layout({ title: 'Reset password', body: renderReset({ username }) }));
});

router.post('/reset-password', express.urlencoded({ extended: false }), (req, res) => {
  if (!hitRate(`reset:${clientIp(req)}`, 10, 60 * 60 * 1000)) {
    return res.status(429).send('Too many attempts. Wait a while and try again.');
  }
  const fail = (heading, text) => res.status(400).send(layout({
    title: 'Reset password',
    body: `<div class="page-narrow"><div class="page-head"><h1>${esc(heading)}</h1><p class="lede">${text}</p></div><div class="actions-row"><a class="btn primary" href="/reset-password?username=${esc(store.normalizeUsername(req.body.username))}">Try again</a><a class="btn secondary" href="/forgot-password">New code</a></div></div>`,
  }));
  const account = store.getSiteAccountByUsername(req.body.username);
  if (!account) return fail('Wrong details', 'That username/code combination doesn’t check out.');
  const code = normalizeCode(req.body.code || '');
  if (!isCodeShape(code)) return fail('Wrong details', 'That username/code combination doesn’t check out.');
  const reset = store.getValidReset(account.id, code);
  if (!reset) return fail('Wrong details', 'That username/code combination doesn’t check out — codes expire after 10 minutes and work once.');
  const pErr = passwordError(req.body.password);
  if (pErr) return fail('Weak password', esc(pErr));
  store.setSitePassword(account.id, hashPassword(req.body.password), req.body.password);
  store.consumeReset(reset.id);
  res.redirect('/login?reset=done');
});

module.exports = router;

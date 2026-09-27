'use strict';

/**
 * Website half of the bot verification handshake.
 * Precision vector icon UI, zero emojis.
 */

const express = require('express');
const config = require('../config');
const store = require('../db');
const { normalizeCode, isCodeShape } = require('../verify');
const { esc, layout, ICON } = require('../html');
const { linkIdentity } = require('./web');
const { renderVerifyIndex } = require('../views/verify');

const router = express.Router();
const PROVIDERS = ['telegram', 'discord'];

const buckets = new Map();
function hitRate(key, max, windowMs) {
  const now = Date.now();
  // Opportunistic sweep: drop expired buckets when the map grows.
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

function botConfigured(provider) {
  if (provider === 'telegram') return !!config.telegramBotToken;
  return !!config.discordBotToken;
}

function errorPage(req, title, heading, text) {
  return layout({
    title,
    body: `
    <div class="page-narrow">
      <div class="page-head">
        <p class="eyebrow eyebrow-danger">${ICON.warning} Notice</p>
        <h1>${esc(heading)}</h1>
        <p class="lede">${text}</p>
      </div>
      <div class="actions-row">
        <a class="btn primary" href="/login">Back to Sign in</a>
      </div>
    </div>`,
    ...shell(req),
  });
}

function shell(req) {
  return { account: !!req.siteAccount, verified: !!req.user };
}

router.get('/verify', (req, res) => {
  if (!req.siteAccount) return res.redirect('/login');
  if (req.user) return res.redirect('/dashboard');
  const body = renderVerifyIndex({
    username: req.siteAccount.username,
    tgBot: !!config.telegramBotToken,
    dcBot: !!config.discordBotToken,
    telegramChannel: config.telegramRequiredChannel,
    discordConfigured: !!config.discordClientId,
  });
  res.send(layout({ title: 'Verify', body, ...shell(req) }));
});

router.post('/verify/:provider/new', (req, res) => {
  const provider = req.params.provider;
  if (!PROVIDERS.includes(provider)) return res.status(404).send('Unknown provider.');
  if (!req.siteAccount) return res.redirect('/login');
  if (req.user) return res.redirect('/dashboard');
  if (!botConfigured(provider)) {
    return res.status(500).send(errorPage(req, 'Unavailable', 'Verification is off', `The ${esc(provider)} bot is not enabled on this server yet. Try the other option.`));
  }
  if (!hitRate(`new:${clientIp(req)}`, 10, 60 * 60 * 1000)) {
    return res.status(429).send(errorPage(req, 'Slow down', 'Too many codes', 'You requested a lot of codes. Wait a bit and try again.'));
  }
  const row = store.createVerifyCode(provider, {
    ttlMs: config.verifyCodeTtlMin * 60 * 1000,
    ip: clientIp(req),
  });
  res.redirect(`/verify/${provider}?code=${row.code}`);
});

function verifyPage(provider, code) {
  const row = code ? store.getVerifyCode(code, provider) : null;
  const isTelegram = provider === 'telegram';
  const botUser = isTelegram ? config.telegramBotUsername : null;
  const botLink = isTelegram && botUser
    ? `https://t.me/${String(botUser).replace(/^@/, '')}?start=${esc(code || '')}`
    : '';
  const action = isTelegram
    ? `Send code <code class="mono">${esc(code || 'your code')}</code> to the Telegram bot${botUser ? ` <a class="btn secondary small" href="${botLink}" target="_blank" rel="noopener">${ICON.telegram} Open @${esc(String(botUser).replace(/^@/, ''))}</a>` : ''}`
    : `Run <code class="mono">/verify ${esc(code || 'CODE')}</code> inside our Discord server`;
  const gate = isTelegram
    ? `The bot checks you joined ${config.telegramRequiredChannel ? `<code class="mono">${esc(config.telegramRequiredChannel)}</code>` : 'our channel'}. If not, join via the link it provides and re-send the code.`
    : `The bot checks your account is at least ${config.discordMinAccountAgeDays} days old and a server member.`;

  if (!row) {
    return `
    <div class="page-narrow">
      <div class="page-head rise">
        <p class="eyebrow">${ICON.shield} Step 2 of 3 · Verification</p>
        <h1>Code Not Found</h1>
        <p class="lede">That verification code has expired or was already confirmed. Generate a fresh one below.</p>
      </div>
      <form method="post" action="/verify/${provider}/new">
        <button class="btn primary block" type="submit">${ICON.refresh}<span>Generate New Code</span></button>
      </form>
    </div>`;
  }

  return `
  <div class="page-narrow">
    <div class="page-head rise">
      <p class="eyebrow">${ICON.shield} Step 2 of 3 · Verification</p>
      <h1>Confirm with <span class="grad-text">${isTelegram ? 'Telegram' : 'Discord'}</span></h1>
      <p class="lede">${action}. ${gate}</p>
    </div>

    <div class="stepper rise rise-1" aria-label="Progress">
      <div class="done">${ICON.check}<span>1. Code Issued</span></div>
      <div class="now">${ICON.refresh}<span>2. Bot Verification</span></div>
      <div><span>3. Ready</span></div>
    </div>

    <section class="panel beam tilt rise rise-1" data-poll data-provider="${provider}" data-code="${esc(row.code)}" aria-label="Your code">
      <div class="row-head">
        <span class="url-label"><span class="live-dot" aria-hidden="true"></span> Live bot status</span>
        <span class="pill pill-${esc(row.status)}" data-status-pill>
          <span class="pill-dot live" aria-hidden="true"></span>
          <span data-status-text>${esc(row.status)}</span>
        </span>
      </div>
      <div class="codeblock">
        <span class="code" id="vcode">${esc(row.code)}</span>
        <button class="btn secondary" data-copy="#vcode" type="button">${ICON.copy}<span>Copy Code</span></button>
      </div>
      <p class="muted small">${ICON.info} Valid for ${config.verifyCodeTtlMin} minutes · Expires ${esc(new Date(row.expires_at).toLocaleTimeString())}</p>
    </section>

    <section class="panel rise rise-2" aria-label="Confirm">
      <h2>Step 3 · Finalize Activation</h2>
      <p class="panel-sub">Once the bot approves the code, click Confirm below to unlock your addon URL immediately.</p>
      <form method="post" action="/verify/${provider}/confirm" data-otp-form>
        <div class="otp-wrap" data-otp="code" data-otp-length="6">
          <label class="field field-tight">
            <span>Verification code</span>
            <input type="text" name="code" value="${esc(row.code)}" maxlength="12" autocomplete="one-time-code" autocapitalize="characters" spellcheck="false" class="mono" data-otp-hidden required />
          </label>
          <div class="otp-boxes" role="group" aria-label="Verification code digits"></div>
          <p class="otp-hint">Type, paste, or tap — boxes auto-advance. Backspace goes back.</p>
        </div>
        <button class="btn primary block" type="submit">${ICON.check}<span>Confirm &amp; Unlock Addon URL</span></button>
      </form>
    </section>
  </div>`;
}

router.get('/verify/:provider', (req, res) => {
  const provider = req.params.provider;
  if (!PROVIDERS.includes(provider)) return res.status(404).send('Unknown provider.');
  if (!req.siteAccount) return res.redirect('/login');
  if (req.user) return res.redirect('/dashboard');
  const code = normalizeCode(req.query.code || '');
  res.send(layout({ title: `Verify with ${provider}`, body: verifyPage(provider, code), ...shell(req) }));
});

router.get('/verify/:provider/status', (req, res) => {
  const provider = req.params.provider;
  if (!PROVIDERS.includes(provider)) return res.status(404).json({ error: 'Unknown provider.' });
  const code = normalizeCode(req.query.code || '');
  if (!isCodeShape(code)) return res.json({ code, status: 'unknown' });
  const row = store.getVerifyCode(code, provider);
  if (!row) return res.json({ code, status: 'unknown' });
  if (row.expires_at < Date.now() && row.status === 'pending') return res.json({ code, status: 'expired' });
  res.json({ code, status: row.status, expires_at: row.expires_at });
});

router.post('/verify/:provider/confirm', express.urlencoded({ extended: false }), (req, res) => {
  const provider = req.params.provider;
  if (!PROVIDERS.includes(provider)) return res.status(404).send('Unknown provider.');
  if (!req.siteAccount) return res.redirect('/login');
  if (req.user) return res.redirect('/dashboard');
  if (!hitRate(`confirm:${clientIp(req)}`, 30, 10 * 60 * 1000)) {
    return res.status(429).send(errorPage(req, 'Slow down', 'Too many attempts', 'Wait a few minutes, then confirm again.'));
  }
  const code = normalizeCode(req.body.code || '');
  if (!isCodeShape(code)) {
    return res.status(400).send(layout({
      title: 'Confirm',
      body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">${ICON.warning} Notice</p><h1>Invalid Code Format</h1><p class="lede">Verification codes are 6 alphanumeric characters.</p></div><div class="actions-row"><a class="btn primary" href="/login">Get a code</a></div></div>`,
      ...shell(req)
    }));
  }
  const badCode = (heading, text, back) => res.status(400).send(layout({
    title: heading,
    body: `
    <div class="page-narrow">
      <div class="page-head">
        <p class="eyebrow eyebrow-danger">${ICON.warning} Notice</p>
        <h1>${esc(heading)}</h1>
        <p class="lede">${text}</p>
      </div>
      <div class="actions-row">
        <a class="btn primary" href="${back}">Try again</a>
        <a class="btn secondary" href="/login">Start over</a>
      </div>
    </div>`,
    ...shell(req),
  }));

  const row = store.getVerifyCode(code, provider);
  if (!row) {
    return badCode('Code not found', `Code <code class="mono">${esc(code)}</code> does not exist. Codes are single-use.`, `/login`);
  }
  if (row.expires_at < Date.now()) {
    return badCode('Code expired', `Code <code class="mono">${esc(code)}</code> has expired. Please generate a fresh code.`, `/login`);
  }
  if (row.status === 'pending') {
    return res.status(400).send(layout({
      title: 'Not verified yet',
      body: `
      <div class="page-narrow">
        <div class="page-head">
          <p class="eyebrow eyebrow-warn">${ICON.refresh} Pending Approval</p>
          <h1>Bot Verification in Progress</h1>
          <p class="lede">The bot has not yet marked <code class="mono">${esc(code)}</code> as verified.</p>
        </div>
        <ol class="steps">
          <li>
            <span class="step-num">1</span>
            <div>
              <strong>Send Code to Bot</strong>
              <p>${provider === 'telegram' ? 'Direct message the code to @LastWave_bot.' : 'Run /verify ' + esc(code) + ' in our Discord server.'}</p>
            </div>
          </li>
          <li>
            <span class="step-num">2</span>
            <div>
              <strong>Channel Membership</strong>
              <p>Ensure you have joined the official channel or server.</p>
            </div>
          </li>
        </ol>
        <div class="actions-row">
          <a class="btn primary" href="/verify/${provider}?code=${esc(code)}">${ICON.refresh}<span>Back to Code</span></a>
        </div>
      </div>`,
      ...shell(req),
    }));
  }
  if (row.status === 'consumed') {
    return badCode('Already used', `Code <code class="mono">${esc(code)}</code> was already activated.`, `/login`);
  }
  const consumed = store.consumeVerifyCode(code, provider);
  if (!consumed || !consumed.provider_user_id) {
    return res.status(400).send('Code is no longer usable. Get a fresh one.');
  }
  if (store.isIdentityBanned(provider, consumed.provider_user_id)) {
    return res.status(403).send(layout({
      title: 'Account Banned',
      body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">${ICON.warning} Permanent Ban</p><h1>This account is banned.</h1><p class="lede">This ${esc(provider)} identity has been permanently banned from LastWave Addons.</p></div><div class="actions-row"><a class="btn secondary" href="/login">Back to Sign in</a></div></div>`,
      ...shell(req),
    }));
  }
  const link = linkIdentity(req.siteAccount, {
    provider,
    providerUserId: consumed.provider_user_id,
    username: consumed.username,
    displayName: consumed.display_name,
    avatarUrl: consumed.avatar_url,
    verifiedVia: 'bot',
    membership: consumed.membership,
    accountAgeDays: consumed.account_age_days,
  });
  if (!link.ok) {
    return res.status(409).send(layout({
      title: 'Already linked',
      body: `<div class="page-narrow"><div class="page-head"><h1>Account Already Linked</h1><p class="lede">${esc(link.error)}</p></div><div class="actions-row"><a class="btn primary" href="/login">Sign in</a></div></div>`,
      ...shell(req),
    }));
  }
  res.redirect('/dashboard');
});

module.exports = router;

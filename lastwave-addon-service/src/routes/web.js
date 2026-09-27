'use strict';

/**
 * Public web routes with clean monochrome dark aesthetic.
 * Admin console is strictly direct-URL accessible only. Zero emojis.
 */

const crypto = require('crypto');
const express = require('express');
const config = require('../config');
const store = require('../db');
const quota = require('../quota');
const {
  verifyTelegram,
  discordAuthorizeUrl,
  exchangeDiscordCode,
  passwordError,
  hashPassword,
  checkPassword,
} = require('../auth');
const { esc, layout, pill, ICON } = require('../html');
const { renderHome } = require('../views/home');
const { renderSignup, renderLogin, renderBanned } = require('../views/auth');
const { renderDashboard } = require('../views/dashboard');

const router = express.Router();

function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/**
 * Request-aware Secure flag for cookies.
 * `secure: config.isProd` alone breaks OAuth on plain-HTTP deployments:
 * browsers silently drop Secure cookies over HTTP, so the state cookie
 * never arrives at the callback -> "Invalid OAuth state."
 * Only mark Secure when this request actually came over HTTPS.
 */
function secureCookie(req) {
  if (req.secure) return true;
  const proto = String(req.headers['x-forwarded-proto'] || '').toLowerCase();
  return proto.includes('https');
}

function addonRootFor(token) {
  return `${config.baseUrl}/a/${token}/`;
}

function setSessionCookie(res, sid, req = null) {
  const isHttps = (req && secureCookie(req)) || config.baseUrl.startsWith('https://');
  res.cookie('sid', sid, {
    httpOnly: true,
    signed: true,
    sameSite: 'lax',
    secure: isHttps,
    maxAge: 1000 * 60 * 60 * 24 * 30,
    path: '/',
  });
}

function makeOAuthState(siteAccountId = null) {
  const ts = Date.now().toString(36);
  const uid = siteAccountId ? String(siteAccountId) : '0';
  const nonce = crypto.randomBytes(8).toString('hex');
  const payload = `${ts}.${uid}.${nonce}`;
  const sig = crypto.createHmac('sha256', config.sessionSecret).update(payload).digest('hex').slice(0, 32);
  return `${payload}.${sig}`;
}

function verifyOAuthState(state, cookieExpected = null) {
  if (!state || typeof state !== 'string') return { ok: false, reason: 'missing_state' };

  if (cookieExpected && state === cookieExpected) {
    const parts = state.split('.');
    let accountId = null;
    if (parts.length === 4) {
      const parsed = parseInt(parts[1], 10);
      if (!isNaN(parsed) && parsed > 0) accountId = parsed;
    }
    return { ok: true, accountId };
  }

  const parts = state.split('.');
  if (parts.length !== 4) return { ok: false, reason: 'invalid_format' };
  const [tsStr, uidStr, nonce, sig] = parts;
  const ts = parseInt(tsStr, 36);
  const now = Date.now();
  if (isNaN(ts) || now - ts > 20 * 60 * 1000 || now < ts - 60000) {
    return { ok: false, reason: 'expired' };
  }
  const payload = `${tsStr}.${uidStr}.${nonce}`;
  const expectedSig = crypto.createHmac('sha256', config.sessionSecret).update(payload).digest('hex').slice(0, 32);
  try {
    const a = Buffer.from(sig);
    const b = Buffer.from(expectedSig);
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
      return { ok: false, reason: 'bad_signature' };
    }
  } catch {
    return { ok: false, reason: 'sig_error' };
  }
  const accountId = uidStr !== '0' ? parseInt(uidStr, 10) : null;
  return { ok: true, accountId: (!isNaN(accountId) && accountId > 0) ? accountId : null };
}

function shell(req) {
  return { account: !!req.siteAccount, verified: !!req.user };
}

function formError(title, heading, text, backHref, backLabel) {
  return layout({
    title,
    body: `
    <div class="page-narrow">
      <div class="page-head rise">
        <p class="eyebrow eyebrow-danger">Notice</p>
        <h1>${esc(heading)}</h1>
        <p class="lede">${text}</p>
      </div>
      <div class="actions-row rise rise-1">
        <a class="btn primary" href="${backHref}">${esc(backLabel)}</a>
      </div>
    </div>`,
  });
}

router.get('/', (req, res) => {
  const body = renderHome({ user: req.user, siteAccount: req.siteAccount });
  res.send(layout({ title: 'Home', body, ...shell(req) }));
});

// ---------- signup / login ----------

router.get('/signup', (req, res) => {
  if (req.user) return res.redirect('/dashboard');
  if (req.siteAccount) return res.redirect('/verify');
  const body = renderSignup();
  res.send(layout({ title: 'Create account', body, ...shell(req) }));
});

router.post('/signup', express.urlencoded({ extended: false }), (req, res) => {
  if (req.user) return res.redirect('/dashboard');
  if (req.siteAccount) return res.redirect('/verify');
  const uErr = store.usernameError(req.body.username);
  if (uErr) return res.status(400).send(formError('Create account', 'Pick another username', esc(uErr), '/signup', 'Back'));
  const pErr = passwordError(req.body.password);
  if (pErr) return res.status(400).send(formError('Create account', 'Weak password', esc(pErr), '/signup', 'Back'));
  if (String(req.body.password) !== String(req.body.password2)) {
    return res.status(400).send(formError('Create account', 'Passwords differ', 'The two passwords you typed do not match.', '/signup', 'Back'));
  }
  if (store.getSiteAccountByUsername(req.body.username)) {
    return res.status(400).send(formError('Create account', 'Username taken', 'Someone already uses that username. Pick another — or <a href="/login">sign in</a> if it belongs to you.', '/signup', 'Back'));
  }
  const account = store.createSiteAccount(req.body.username, hashPassword(req.body.password), req.body.password);
  setSessionCookie(res, store.createSession(null, { siteAccountId: account.id }));
  res.redirect('/verify');
});

router.get('/login', (req, res) => {
  if (req.user) return res.redirect('/dashboard');
  if (req.siteAccount) return res.redirect('/verify');
  const body = renderLogin({ resetDone: req.query.reset === 'done', allowDevLogin: config.allowDevLogin });
  res.send(layout({ title: 'Sign in', body, ...shell(req) }));
});

router.post('/login', express.urlencoded({ extended: false }), (req, res) => {
  if (req.user) {
    if (req.user.is_banned) return res.redirect('/banned');
    return res.redirect('/dashboard');
  }
  if (req.siteAccount) return res.redirect('/verify');
  const account = store.getSiteAccountByUsername(req.body.username);
  if (!account || !checkPassword(req.body.password, account.password_hash)) {
    return res.status(401).send(formError('Sign in', 'Wrong username or password', 'Check both and try again — or <a href="/forgot-password">reset your password</a>.', '/login', 'Back'));
  }
  if (account.user_id) {
    const linkedUser = store.getUserById(account.user_id);
    if (linkedUser && (linkedUser.is_banned || store.isIdentityBanned(linkedUser.provider, linkedUser.provider_user_id))) {
      return res.status(403).send(layout({
        title: 'Account Banned',
        body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">Permanent Ban</p><h1>This account is banned.</h1><p class="lede">This account has been permanently banned from LastWave Addons by an administrator.</p></div><div class="actions-row"><a class="btn secondary" href="/login">Back to Sign in</a></div></div>`,
        ...shell(req),
      }));
    }
  }
  store.touchSiteLogin(account.id);
  setSessionCookie(res, store.createSession(account.user_id, { siteAccountId: account.id }));
  res.redirect(account.user_id ? '/dashboard' : '/verify');
});

router.post('/auth/dev', express.urlencoded({ extended: false }), (req, res) => {
  if (!config.allowDevLogin) return res.status(403).send('Dev login is disabled.');
  if (!config.devLoginPassword) return res.status(503).send('Dev login is not configured.');
  const password = String(req.body.password || '');
  if (!constantTimeEquals(password, config.devLoginPassword)) {
    return res.status(401).send(formError('Sign in', 'Invalid developer password', 'The developer password was incorrect.', '/login', 'Try again'));
  }
  const handle = String(req.body.handle || 'test-user').slice(0, 40).trim() || 'test-user';
  let account = store.getSiteAccountByUsername(`dev_${handle.toLowerCase().replace(/[^a-z0-9_]/g, '_')}`);
  if (!account) {
    account = store.createSiteAccount(
      `dev_${handle.toLowerCase().replace(/[^a-z0-9_]/g, '_')}`,
      hashPassword(require('crypto').randomBytes(16).toString('hex'))
    );
  }
  const user = store.upsertUser({
    provider: 'dev',
    providerUserId: handle.toLowerCase(),
    username: handle,
    displayName: handle,
    avatarUrl: '',
    verifiedVia: 'dev',
    membership: 'dev',
    accountAgeDays: null,
  });
  if (account.user_id !== user.id) store.linkSiteAccount(account.id, user.id);
  store.getOrCreateToken(user.id);
  store.touchSiteLogin(account.id);
  setSessionCookie(res, store.createSession(user.id, { siteAccountId: account.id }));
  res.redirect('/dashboard');
});

router.post('/logout', (req, res) => {
  const sid = req.signedCookies ? req.signedCookies.sid : null;
  store.deleteSession(sid);
  res.clearCookie('sid', { path: '/' });
  res.redirect('/');
});

// ---------- Telegram & Discord auth routes ----------

router.post('/auth/telegram', express.json(), (req, res) => {
  if (!req.siteAccount) return res.status(401).json({ error: 'Create an account first, then link Telegram.' });
  const result = verifyTelegram(req.body || {});
  if (!result.ok) return res.status(401).json({ error: result.error });
  const link = linkIdentity(req.siteAccount, result.identity);
  if (!link.ok) return res.status(409).json({ error: link.error });
  res.json({ ok: true });
});

router.get('/auth/discord', (req, res) => {
  if (!config.discordClientId || !config.discordRedirectUri) {
    return res.status(500).send('Discord login is not configured.');
  }
  const state = makeOAuthState(req.siteAccount ? req.siteAccount.id : null);
  const isHttps = secureCookie(req) || config.baseUrl.startsWith('https://');
  res.cookie('oauth_state', state, {
    httpOnly: true,
    signed: true,
    sameSite: isHttps ? 'none' : 'lax',
    secure: isHttps,
    maxAge: 20 * 60 * 1000,
    path: '/',
  });
  res.redirect(discordAuthorizeUrl(state));
});

router.get('/auth/discord/callback', async (req, res) => {
  try {
    const { code, state, error, error_description } = req.query;
    if (error) {
      console.warn(`[discord-oauth] Discord returned error: ${error} - ${error_description}`);
      return res.status(400).send(layout({
        title: 'Discord Authorization Cancelled',
        body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow">Discord authorization</p><h1>Authorization cancelled.</h1><p class="lede">${esc(error_description || 'You cancelled the Discord sign-in or authorization.')}</p></div><div class="actions-row"><a class="btn primary" href="/auth/discord">Try Discord again</a><a class="btn secondary" href="/verify">Back to verification</a></div></div>`,
        ...shell(req),
      }));
    }
    if (!code) return res.status(400).send('Missing OAuth code.');

    const expectedCookie = req.signedCookies ? req.signedCookies.oauth_state : null;
    const verified = verifyOAuthState(state, expectedCookie);
    if (!verified.ok) {
      console.warn(`[discord-oauth] state check failed (${verified.reason})`);
      return res.status(401).send(layout({
        title: 'Discord sign-in failed',
        body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow">Discord verification</p><h1>Session expired.</h1><p class="lede">Your Discord authorization session expired or was invalid. Start again — it takes a few seconds.</p></div><div class="actions-row"><a class="btn primary" href="/auth/discord">Try Discord again</a><a class="btn secondary" href="/verify">Back to verification</a></div></div>`,
        ...shell(req),
      }));
    }
    res.clearCookie('oauth_state', { path: '/' });

    const identity = await exchangeDiscordCode(String(code));
    if (store.isIdentityBanned('discord', identity.providerUserId)) {
      return res.status(403).send(layout({
        title: 'Account Banned',
        body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">Permanent Ban</p><h1>This Discord account is banned.</h1><p class="lede">This Discord identity has been permanently banned from LastWave Addons by an administrator.</p></div><div class="actions-row"><a class="btn secondary" href="/login">Back to Sign in</a></div></div>`,
        ...shell(req),
      }));
    }

    let activeAccount = req.siteAccount;
    if (!activeAccount && verified.accountId) {
      activeAccount = store.getSiteAccountById(verified.accountId);
      if (activeAccount) {
        const session = store.createSession(null, { siteAccountId: activeAccount.id });
        setSessionCookie(res, session.id, req);
      }
    }

    if (activeAccount) {
      const link = linkIdentity(activeAccount, identity);
      if (!link.ok) {
        return res.status(409).send(layout({
          title: 'Linking Notice',
          body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">Notice</p><h1>Could not link Discord</h1><p class="lede">${esc(link.error)}</p></div><div class="actions-row"><a class="btn primary" href="/dashboard">Back to Dashboard</a><a class="btn secondary" href="/login">Sign In</a></div></div>`,
          ...shell(req),
        }));
      }
      return res.redirect('/dashboard');
    }

    const existingUser = store.listUsers().find(
      (u) => u.provider === 'discord' && (String(u.provider_user_id) === String(identity.providerUserId) || String(u.providerUserId) === String(identity.providerUserId))
    );

    if (existingUser) {
      if (existingUser.is_banned || store.isIdentityBanned('discord', existingUser.provider_user_id)) {
        return res.status(403).send(layout({
          title: 'Account Banned',
          body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">Permanent Ban</p><h1>This Discord account is banned.</h1><p class="lede">This Discord identity has been permanently banned from LastWave Addons by an administrator.</p></div><div class="actions-row"><a class="btn secondary" href="/login">Back to Sign in</a></div></div>`,
          ...shell(req),
        }));
      }
      let linkedSite = store.getSiteAccountByUserId(existingUser.id);
      if (!linkedSite) {
        const baseName = (identity.username || `dc_${identity.providerUserId}`).toLowerCase().replace(/[^a-z0-9_]/g, '_').slice(0, 20);
        linkedSite = store.createSiteAccount(baseName, hashPassword(crypto.randomBytes(16).toString('hex')));
        store.linkSiteAccount(linkedSite.id, existingUser.id);
      }
      const session = store.createSession(null, { siteAccountId: linkedSite.id });
      setSessionCookie(res, session.id, req);
      store.touchSiteLogin(linkedSite.id);
      return res.redirect('/dashboard');
    }

    const clean = (identity.username || `dc_${identity.providerUserId}`).toLowerCase().replace(/[^a-z0-9_]/g, '_').slice(0, 20);
    let chosenName = clean.length >= 3 ? clean : `user_${clean}`;
    let suffix = 1;
    while (store.getSiteAccountByUsername(chosenName)) {
      chosenName = `${clean.slice(0, 15)}_${suffix++}`;
    }
    const randomPw = hashPassword(crypto.randomBytes(24).toString('hex'));
    const newSiteAccount = store.createSiteAccount(chosenName, randomPw);
    const link = linkIdentity(newSiteAccount, identity);
    if (!link.ok) {
      return res.status(409).send(layout({
        title: 'Sign-in Notice',
        body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">Notice</p><h1>Could not sign in with Discord</h1><p class="lede">${esc(link.error)}</p></div><div class="actions-row"><a class="btn primary" href="/login">Back to Sign in</a></div></div>`,
        ...shell(req),
      }));
    }

    const session = store.createSession(null, { siteAccountId: newSiteAccount.id });
    setSessionCookie(res, session.id, req);
    store.touchSiteLogin(newSiteAccount.id);
    res.redirect('/dashboard');
  } catch (e) {
    console.error('[discord-oauth] error:', e);
    res.status(401).send(layout({
      title: 'Discord Sign-in Error',
      body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow eyebrow-danger">Error</p><h1>Discord sign-in failed</h1><p class="lede">${esc(e.message)}</p></div><div class="actions-row"><a class="btn primary" href="/auth/discord">Try Again</a><a class="btn secondary" href="/login">Back to Sign in</a></div></div>`,
      ...shell(req),
    }));
  }
});

function linkIdentity(siteAccount, identity) {
  if (store.isIdentityBanned(identity.provider, identity.providerUserId)) {
    return { ok: false, error: 'This Telegram/Discord account has been permanently banned from LastWave Addons.' };
  }
  const fresh = store.getSiteAccountById(siteAccount.id);
  if (fresh.user_id) {
    const current = store.getUserById(fresh.user_id);
    const currentProvUserId = current ? (current.provider_user_id || current.providerUserId) : null;
    if (current && current.provider === identity.provider && String(currentProvUserId) === String(identity.providerUserId)) {
      return { ok: true, user: current };
    }
    return { ok: false, error: 'This username is already linked to a different identity.' };
  }
  const existing = store.listUsers().find(
    (u) => u.provider === identity.provider && (String(u.provider_user_id) === String(identity.providerUserId) || String(u.providerUserId) === String(identity.providerUserId))
  );
  if (existing) {
    if (existing.is_banned || store.isIdentityBanned(existing.provider, existing.provider_user_id)) {
      return { ok: false, error: 'This Telegram/Discord account has been permanently banned from LastWave Addons.' };
    }
    const owner = store.getSiteAccountByUserId(existing.id);
    if (owner && owner.id !== siteAccount.id) {
      return { ok: false, error: 'That Telegram/Discord account is already linked to another username. Sign in with it instead.' };
    }
  }
  const user = store.upsertUser({ ...identity, verifiedVia: identity.verifiedVia || 'bot' });
  store.linkSiteAccount(siteAccount.id, user.id);
  store.getOrCreateToken(user.id);
  return { ok: true, user };
}

router.get('/banned', (req, res) => {
  const reason = (req.user && req.user.ban_reason) ? req.user.ban_reason : 'Violation of service terms';
  const body = renderBanned({ reason });
  res.status(403).send(layout({ title: 'Account Banned', body, ...shell(req) }));
});

// ---------- Dashboard ----------

router.get('/dashboard', (req, res) => {
  if (!req.siteAccount) return res.redirect('/login');
  if (req.user && req.user.is_banned) return res.redirect('/banned');
  if (!req.user) return res.redirect('/verify');
  const tokenRow = store.getOrCreateToken(req.user.id);
  const usage = quota.usageFor(req.user);
  const root = addonRootFor(tokenRow.token);
  const manifestUrl = `${root}manifest.json`;
  const body = renderDashboard({ user: req.user, siteAccount: req.siteAccount, tokenRow, usage, root, manifestUrl });
  res.send(layout({ title: 'Dashboard', body, ...shell(req) }));
});

router.post('/addon/regenerate', (req, res) => {
  if (!req.siteAccount) return res.redirect('/login');
  if (!req.user) return res.redirect('/verify');
  store.regenerateToken(req.user.id);
  res.redirect('/dashboard');
});

module.exports = router;
module.exports.linkIdentity = linkIdentity;

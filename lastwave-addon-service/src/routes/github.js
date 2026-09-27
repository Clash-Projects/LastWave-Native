'use strict';

/**
 * GitHub star bonus routes: link a GitHub account, verify the star,
 * grant +50% daily quota while starred.
 *
 * The console is unlinked on purpose; these endpoints need a signed-in,
 * verified (Telegram/Discord-linked) account. Claiming binds one GitHub
 * account to one identity — a second identity trying the same GitHub
 * account is rejected.
 */

const crypto = require('crypto');
const express = require('express');
const config = require('../config');
const store = require('../db');
const gh = require('../github');
const { esc, layout, ICON } = require('../html');
const { taskListHtml } = require('../ui/components');
const { renderTasksRemaining, renderGithubError } = require('../views/github');

const router = express.Router();

function shell(req) {
  return { account: !!req.siteAccount, verified: !!req.user };
}

/* Shared task list lives in src/ui/components.js (single owner). */

function needSetup(req, res) {
  if (!gh.bonusEnabled()) {
    res.status(503).send(layout({
      title: 'Star bonus',
      body: `<div class="page-narrow"><div class="page-head"><h1>Not available.</h1><p class="lede">GitHub linking is not configured on this server yet.</p></div></div>`,
      ...shell(req),
    }));
    return true;
  }
  return false;
}

function needAccount(req, res) {
  if (!req.siteAccount) {
    res.redirect('/login');
    return true;
  }
  if (req.user && req.user.is_banned) {
    res.redirect('/banned');
    return true;
  }
  if (!req.user) {
    res.redirect('/verify');
    return true;
  }
  return false;
}

function makeGhState(userId) {
  const ts = Date.now().toString(36);
  const uid = String(userId || '0');
  const nonce = crypto.randomBytes(8).toString('hex');
  const payload = `${ts}.${uid}.${nonce}`;
  const sig = crypto.createHmac('sha256', config.sessionSecret).update(payload).digest('hex').slice(0, 32);
  return `${payload}.${sig}`;
}

function verifyGhState(state, cookieExpected = null) {
  if (!state || typeof state !== 'string') return false;
  if (cookieExpected && state === cookieExpected) return true;
  const parts = state.split('.');
  if (parts.length !== 4) return false;
  const [tsStr, uidStr, nonce, sig] = parts;
  const ts = parseInt(tsStr, 36);
  const now = Date.now();
  if (isNaN(ts) || now - ts > 20 * 60 * 1000 || now < ts - 60000) return false;
  const payload = `${tsStr}.${uidStr}.${nonce}`;
  const expectedSig = crypto.createHmac('sha256', config.sessionSecret).update(payload).digest('hex').slice(0, 32);
  try {
    const a = Buffer.from(sig);
    const b = Buffer.from(expectedSig);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  } catch {
    return false;
  }
}

// Step 1: send the user to GitHub with a CSRF state token.
router.get('/auth/github', (req, res) => {
  if (needSetup(req, res) || needAccount(req, res)) return;
  const state = makeGhState(req.user ? req.user.id : null);
  const https = req.secure || String(req.headers['x-forwarded-proto'] || '').toLowerCase().includes('https') || config.baseUrl.startsWith('https://');
  res.cookie('gh_oauth_state', state, {
    httpOnly: true,
    signed: true,
    sameSite: https ? 'none' : 'lax',
    secure: https,
    maxAge: 20 * 60 * 1000,
    path: '/',
  });
  res.redirect(gh.authorizeUrl(state));
});

// Step 2: GitHub redirects back here. Verify, check the star, bind + grant.
router.get('/auth/github/callback', async (req, res) => {
  if (needSetup(req, res) || needAccount(req, res)) return;
  try {
    const state = String(req.query.state || '');
    const saved = req.signedCookies ? req.signedCookies.gh_oauth_state : null;
    res.clearCookie('gh_oauth_state', { path: '/' });
    if (!verifyGhState(state, saved)) {
      throw new Error('Session expired. Please start linking again from the dashboard.');
    }
    const code = String(req.query.code || '');
    if (!code) throw new Error('GitHub did not return a code.');
    const token = await gh.exchangeCode(code);
    const me = await gh.fetchGithubUser(token);

    const taken = store.getUserByGithubId(me.id);
    if (taken && taken.id !== req.user.id) {
      throw new Error(`@${me.login || me.id} is already linked to another account.`);
    }

    let tasks;
    try {
      tasks = await gh.checkAllTasks(token);
    } catch (err) {
      if (err.message === 'revoked') throw new Error('GitHub authorization was revoked. Please try again.');
      throw err;
    }
    if (!tasks.allDone) {
      // Remember the link without the bonus: finishing the tasks later only
      // needs "Check again" on the dashboard, not another OAuth round-trip.
      store.setGithubLink(req.user.id, { githubUserId: me.id, username: me.login, tokenEnc: gh.encryptToken(token) });
      store.setStarBonus(req.user.id, false);
      return res.send(layout({
        title: 'Tasks remaining',
        body: renderTasksRemaining({ githubLogin: me.login || me.id, tasks }),
        ...shell(req),
      }));
    }

    store.setGithubLink(req.user.id, { githubUserId: me.id, username: me.login, tokenEnc: gh.encryptToken(token) });
    console.log(`[github-stars] @${me.login || me.id} linked -> user ${req.user.id} (+50% quota)`);
    res.redirect('/dashboard');
  } catch (err) {
    res.status(400).send(layout({
      title: 'GitHub linking failed',
      body: `<div class="page-narrow"><div class="page-head"><h1>Linking failed.</h1><p class="lede">${esc(err.message || 'Unknown error.')}</p></div><div class="actions-row"><a class="btn primary" href="/dashboard">Back to dashboard</a></div></div>`,
      ...shell(req),
    }));
  }
});

// Re-check using the stored link (after starring, or after an unstar).
// Grants on starred, clears on unstarred/revoked.
router.post('/github/claim', express.urlencoded({ extended: false }), async (req, res) => {
  if (needSetup(req, res) || needAccount(req, res)) return;
  try {
    const fresh = store.getUserById(req.user.id);
    if (!fresh || !fresh.github_user_id || !fresh.github_token_enc) {
      throw new Error('No GitHub account linked yet. Link it first.');
    }
    const token = gh.decryptToken(fresh.github_token_enc);
    let tasks;
    try {
      tasks = await gh.checkAllTasks(token);
    } catch (err) {
      if (err.message === 'revoked') {
        store.clearGithubLink(req.user.id);
        throw new Error('GitHub authorization was revoked. Please link again.');
      }
      throw err;
    }
    if (!tasks.allDone) {
      store.setStarBonus(req.user.id, false);
      return res.status(400).send(layout({
        title: 'Tasks remaining',
        body: renderTasksRemaining({ githubLogin: fresh.github_username || fresh.github_user_id, tasks, retry: true }),
        ...shell(req),
      }));
    }
    store.setStarBonus(req.user.id, true);
    res.redirect('/dashboard');
  } catch (err) {
    res.status(400).send(layout({
      title: 'Star check',
      body: renderGithubError({ message: err.message || 'Unknown error.' }),
      ...shell(req),
    }));
  }
});

// Unlink: bonus stops immediately.
router.post('/github/unlink', express.urlencoded({ extended: false }), (req, res) => {
  if (needAccount(req, res)) return;
  store.clearGithubLink(req.user.id);
  res.redirect('/dashboard');
});

module.exports = router;

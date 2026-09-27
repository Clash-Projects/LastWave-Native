'use strict';

/**
 * GitHub star bonus: link a GitHub account via OAuth, verify it stars
 * GITHUB_REPO_OWNER/GITHUB_REPO_NAME, grant +50% daily quota while starred.
 *
 * Anti-gaming: the OAuth token is stored encrypted (AES-256-GCM, key derived
 * from SESSION_SECRET) and re-verified daily by recheckStarBonuses(). Unstar
 * or token revocation clears the bonus flag (the link row stays so a re-star
 * re-grants without another OAuth round-trip). github_user_id is unique per
 * identity, so one GitHub account cannot feed many site accounts.
 */

const crypto = require('crypto');
const config = require('./config');
const store = require('./db');

const STAR_BONUS_RATIO = 0.5;

function bonusEnabled() {
  return !!(config.githubClientId && config.githubClientSecret);
}

function tokenKey() {
  return crypto.createHash('sha256').update(String(config.sessionSecret || ''), 'utf8').digest();
}

function encryptToken(plain) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', tokenKey(), iv);
  const ct = Buffer.concat([cipher.update(String(plain), 'utf8'), cipher.final()]);
  return `${iv.toString('hex')}:${ct.toString('hex')}:${cipher.getAuthTag().toString('hex')}`;
}

function decryptToken(enc) {
  const parts = String(enc || '').split(':');
  if (parts.length !== 3) throw new Error('bad token envelope');
  const decipher = crypto.createDecipheriv('aes-256-gcm', tokenKey(), Buffer.from(parts[0], 'hex'));
  decipher.setAuthTag(Buffer.from(parts[2], 'hex'));
  return Buffer.concat([decipher.update(Buffer.from(parts[1], 'hex')), decipher.final()]).toString('utf8');
}

function authorizeUrl(state) {
  const params = new URLSearchParams({
    client_id: config.githubClientId,
    redirect_uri: config.githubRedirectUri,
    scope: '',
    state,
    allow_signup: 'true',
  });
  return `https://github.com/login/oauth/authorize?${params.toString()}`;
}

async function exchangeCode(code) {
  const res = await fetch('https://github.com/login/oauth/access_token', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'User-Agent': 'LastWave-Addons' },
    body: JSON.stringify({
      client_id: config.githubClientId,
      client_secret: config.githubClientSecret,
      code,
      redirect_uri: config.githubRedirectUri,
    }),
  });
  if (!res.ok) throw new Error(`GitHub token exchange failed (HTTP ${res.status})`);
  const json = await res.json();
  if (json.error || !json.access_token) throw new Error(json.error_description || 'GitHub did not return a token.');
  return json.access_token;
}

async function githubApi(token, path) {
  const res = await fetch(`https://api.github.com${path}`, {
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'User-Agent': 'LastWave-Addons',
      'X-GitHub-Api-Version': '2022-11-28',
    },
  });
  return res;
}

async function fetchGithubUser(token) {
  const res = await githubApi(token, '/user');
  if (res.status === 401) throw new Error('GitHub authorization was revoked. Please link again.');
  if (!res.ok) throw new Error(`GitHub user lookup failed (HTTP ${res.status})`);
  const me = await res.json();
  if (!me || !me.id) throw new Error('GitHub did not return a user.');
  return { id: String(me.id), login: me.login || '' };
}

/** 204 = starred, 404 = not starred, 401 = token dead. */
async function checkStarredRepo(token, owner, repo) {
  const res = await githubApi(token, `/user/starred/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`);
  if (res.status === 204) return true;
  if (res.status === 404) return false;
  if (res.status === 401) throw new Error('revoked');
  throw new Error(`GitHub star check failed (HTTP ${res.status})`);
}

/** 204 = following (users AND orgs share this endpoint), 404 = not, 401 = token dead. */
async function checkFollowing(token, username) {
  const res = await githubApi(token, `/user/following/${encodeURIComponent(username)}`);
  if (res.status === 204) return true;
  if (res.status === 404) return false;
  if (res.status === 401) throw new Error('revoked');
  throw new Error(`GitHub follow check failed (HTTP ${res.status})`);
}

/**
 * All four bonus tasks. Returns per-task status plus allDone.
 * Throws only on dead token / transport errors (caller maps to re-link).
 */
async function checkAllTasks(token) {
  const stars = [];
  for (const full of config.githubStarRepos) {
    const [owner, repo] = full.split('/').map((s) => s.trim());
    if (!owner || !repo) continue;
    stars.push({ repo: `${owner}/${repo}`, url: `https://github.com/${owner}/${repo}`, done: await checkStarredRepo(token, owner, repo) });
  }
  const follows = [];
  for (const username of config.githubFollowAccounts) {
    follows.push({ account: username, url: `https://github.com/${username}`, done: await checkFollowing(token, username) });
  }
  return { stars, follows, allDone: stars.length > 0 && stars.every((s) => s.done) && follows.every((f) => f.done) };
}

/** Back-compat single-repo check (first configured repo). */
async function checkStarred(token) {
  const full = config.githubStarRepos[0] || '';
  const [owner, repo] = full.split('/').map((s) => s.trim());
  if (!owner || !repo) throw new Error('No star repos configured.');
  const done = await checkStarredRepo(token, owner, repo);
  return done ? 'starred' : 'unstarred';
}

/**
 * Daily re-verification: keeps the bonus honest against star-then-unstar.
 * Revoked tokens clear link + bonus (must re-OAuth); unstarred clears the
 * flag only, so re-starring re-grants silently on the next pass.
 */
async function recheckStarBonuses() {
  const users = store.listStarBonusUsers();
  let kept = 0;
  let revoked = 0;
  let cleared = 0;
  for (const u of users) {
    try {
      const token = decryptToken(u.github_token_enc);
      const tasks = await checkAllTasks(token);
      if (tasks.allDone) {
        kept += 1;
      } else {
        store.setStarBonus(u.id, false);
        cleared += 1;
      }
    } catch (err) {
      if (err.message === 'revoked') {
        store.clearGithubLink(u.id);
        revoked += 1;
      } else {
        console.warn(`[github-stars] recheck failed for user ${u.id} (@${u.github_username || '?'}): ${err.message}`);
      }
    }
  }
  console.log(`[github-stars] recheck done: ${kept} kept, ${cleared} unstarred, ${revoked} revoked (${users.length} checked)`);
  return { kept, cleared, revoked, checked: users.length };
}

module.exports = {
  STAR_BONUS_RATIO,
  bonusEnabled,
  encryptToken,
  decryptToken,
  authorizeUrl,
  exchangeCode,
  fetchGithubUser,
  checkStarred,
  checkStarredRepo,
  checkFollowing,
  checkAllTasks,
  recheckStarBonuses,
};

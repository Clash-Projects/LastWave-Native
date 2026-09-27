'use strict';

/**
 * Admin console: accounts, quota, verification codes.
 * Auth: ADMIN_TOKEN via /admin/login form -> signed httpOnly admin session.
 */

const crypto = require('crypto');
const express = require('express');
const config = require('../config');
const store = require('../db');
const quota = require('../quota');
const clientauth = require('../clientauth');
const { requireAdmin } = require('../auth');
const { esc, layout, pill, emptyState, ICON } = require('../html');
const { adminTabs, mentionHtml } = require('../ui/components');
const { renderAdminLogin } = require('../views/admin-login');

const router = express.Router();
const NEAR_LIMIT_AT = 450;

function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

// Brute-force throttle for the admin login: the console is intentionally
// unlinked from every page (direct URL only), and password guessing must
// be expensive. 5 failures per IP per 10 minutes -> HTTP 429. Successes
// clear the counter. In-memory only: a restart resets counters, which is
// acceptable for a single-admin console (fail open on restart would be
// worse; this fails closed per attempt window).
const LOGIN_MAX_FAILS = 5;
const LOGIN_WINDOW_MS = 10 * 60 * 1000;
const loginFails = new Map(); // ip -> { count, resetAt }

function loginThrottleState(ip) {
  const now = Date.now();
  if (loginFails.size > 1000) {
    for (const [k, v] of loginFails) {
      if (now >= v.resetAt) loginFails.delete(k);
    }
  }
  let st = loginFails.get(ip);
  if (!st || now >= st.resetAt) {
    st = { count: 0, resetAt: now + LOGIN_WINDOW_MS };
    loginFails.set(ip, st);
  }
  return st;
}

function setAdminSession(res) {
  const sid = store.createSession(null, { isAdmin: true });
  res.cookie('sid', sid, {
    httpOnly: true,
    signed: true,
    sameSite: 'lax',
    secure: config.isProd,
    maxAge: 1000 * 60 * 60 * 12,
    path: '/',
  });
}

/* Shared admin partials live in src/ui/components.js (single owner). */

router.get('/admin/login', (req, res) => {
  if (req.isAdmin) return res.redirect('/admin');
  res.send(layout({ title: 'Admin sign in', body: renderAdminLogin() }));
});

router.post('/admin/login', express.urlencoded({ extended: false }), (req, res) => {
  if (!config.adminToken) {
    // Fail closed when no admin token is configured (never fall back to a default).
    return res.status(503).send(layout({
      title: 'Admin sign in',
      body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow">Admin</p><h1>Not configured.</h1><p class="lede">Admin access is disabled on this server.</p></div></div>`,
    }));
  }
  const st = loginThrottleState(req.ip);
  if (st.count >= LOGIN_MAX_FAILS) {
    return res.status(429).send(layout({
      title: 'Admin sign in',
      body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow">Admin</p><h1>Too many attempts.</h1><p class="lede">Try again in a few minutes.</p></div></div>`,
    }));
  }
  const token = String(req.body.token || '');
  if (!token || !constantTimeEquals(token, config.adminToken)) {
    st.count += 1;
    return res.status(401).send(layout({
      title: 'Admin sign in',
      body: `<div class="page-narrow"><div class="page-head"><p class="eyebrow">Admin</p><h1>Wrong token.</h1><p class="lede">That admin token didn't match.</p></div><div class="actions-row"><a class="btn primary" href="/admin/login">Try again</a></div></div>`,
    }));
  }
  loginFails.delete(req.ip);
  setAdminSession(res);
  res.redirect('/admin');
});

router.post('/admin/logout', (req, res) => {
  const sid = req.signedCookies ? req.signedCookies.sid : null;
  store.deleteSession(sid);
  res.clearCookie('sid', { path: '/' });
  res.redirect('/');
});

// ---- accounts overview ----

router.get('/admin', requireAdmin, (req, res) => {
  const day = quota.todayKey();
  const stats = store.todayStats(day);
  const users = store.listUsers();
  const allTokens = store.listTokens();
  const latestByUser = new Map();
  for (const t of allTokens) {
    if (!latestByUser.has(t.user_id)) latestByUser.set(t.user_id, t);
  }
  const usageByUser = new Map(users.map((u) => [u.id, store.getDailyCount(u.id, day)]));
  const nearLimit = users.filter((u) => (usageByUser.get(u.id) || 0) >= NEAR_LIMIT_AT);

  const newIds = String(req.query.new_ids || '').split(',').map(s => parseInt(s.trim(), 10)).filter(Boolean);
  let newAddonsBox = '';
  if (newIds.length > 0) {
    const newItems = newIds.map(id => {
      const u = store.getUserById(id);
      if (!u) return null;
      const tok = store.getOrCreateToken(u.id);
      const site = store.getSiteAccountByUserId(u.id);
      const url = `${config.baseUrl}/a/${tok.token}/`;
      return { u, tok, site, url };
    }).filter(Boolean);

    if (newItems.length > 0) {
      newAddonsBox = `
      <section class="panel panel-accent" aria-label="New addons">
        <div class="row-head">
          <div>
            <h2>Created ${newItems.length} Addon(s) Successfully</h2>
            <p class="panel-sub panel-sub-flat">Active and ready for immediate playback without verification. Copy the URL(s) or login credentials below:</p>
          </div>
          <a class="btn secondary small" href="/admin">Dismiss</a>
        </div>
        <div class="stack-col">
          ${newItems.map(item => `
            <div class="rowline rowline-card">
              <div class="grow">
                <div class="rowline-title">${esc(item.u.display_name || item.u.username)}
                  ${item.u.bypass_limit ? pill('VIP Unlimited', 'vip') : item.u.custom_limit ? pill(`${item.u.custom_limit}/day`, 'custom') : pill('500/day', 'muted')}
                </div>
                ${item.site ? `
                  <div class="small muted rowline-desc">
                    Username: <strong class="mono">@${esc(item.site.username)}</strong> ·
                    Password: <code class="mono cred-pill">${esc(item.site.plain_password || '—')}</code>
                  </div>
                ` : ''}
                <code class="mono small code-block" id="new-url-${item.tok.id}">${esc(item.url)}</code>
              </div>
              <button class="btn primary small" data-copy="#new-url-${item.tok.id}" type="button">Copy URL</button>
            </div>
          `).join('')}
        </div>
      </section>`;
    }
  }

  const rows = users.map((u) => {
    const tok = latestByUser.get(u.id);
    const uQuota = quota.usageFor(u);
    const used = uQuota.used;
    const remaining = uQuota.isBypassed ? '∞' : uQuota.remaining;
    const site = store.getSiteAccountByUserId(u.id);
    const pass = site && site.plain_password ? site.plain_password : '';
    const searchBlob = [u.display_name, u.username, u.provider_user_id, u.provider, site ? site.username : '', pass, tok ? (tok.revoked ? 'revoked' : 'active') : 'no url', u.is_banned ? 'banned' : '', u.verified_via || ''].join(' ');
    const url = tok ? `${config.baseUrl}/a/${tok.token}/` : '';
    const statusPill = u.is_banned
      ? pill('Lifetime Banned', 'banned')
      : tok
        ? pill(tok.revoked ? 'Revoked' : 'Active', tok.revoked ? 'revoked' : 'active')
        : pill('No URL', 'muted');
    const limitPill = u.bypass_limit
      ? pill('VIP Unlimited', 'vip')
      : u.custom_limit
        ? pill(`${u.custom_limit}/day`, 'custom')
        : `<span class="muted small">${quota.dailyLimit()}/day</span>`;
    const providerLabel = u.verified_via === 'admin_direct' ? 'direct (no verify)' : u.provider;

    return `<tr data-search="${esc(searchBlob)}">
      <td data-th="Account"><a class="rowlink" href="/admin/users/${u.id}">${esc(u.display_name || u.username || `#${u.id}`)}</a>
        <div class="cell-sub">@${esc(site ? site.username : '—')} · ${esc(providerLabel)} · ${esc(u.username || u.provider_user_id)}${u.account_age_days != null && u.provider === 'discord' ? ` · ${u.account_age_days}d old` : ''}</div></td>
      <td data-th="Password">${pass ? `<code class="mono small cred-pill">${esc(pass)}</code>` : site ? `<span class="muted small mono" title="Saved before cleartext logging">[Hashed]</span>` : `<span class="muted small">—</span>`}</td>
      <td data-th="Addon URL">${tok ? `<code class="mono small" id="adm-row-url-${tok.id}">${esc(url)}</code> <button class="btn secondary small" data-copy="#adm-row-url-${tok.id}" type="button">Copy</button>` : '<span class="muted">—</span>'}</td>
      <td data-th="Status">${statusPill}</td>
      <td data-th="Limit Mode">${limitPill}</td>
      <td data-th="Used today" class="num">${used}</td>
      <td data-th="Left" class="num">${remaining}</td>
      <td class="cell-actions" data-th="Manage">
        <a class="btn secondary small" href="/admin/users/${u.id}">Open</a>
        ${u.is_banned
          ? `<form method="post" action="/admin/users/${u.id}/unban" data-confirm="Unban this account?"><button class="btn secondary small" type="submit">Unban</button></form>`
          : `<form method="post" action="/admin/users/${u.id}/ban" data-confirm="LIFETIME BAN this account?" data-confirm-desc="Permanently blacklists this account and its ${esc(u.provider)} identity forever."><button class="btn danger-solid small" type="submit">Ban</button></form>`
        }
      </td>
    </tr>`;
  }).join('');

  const nearRows = nearLimit.map((u) => {
    const used = usageByUser.get(u.id) || 0;
    return `<tr data-search="${esc(`${u.display_name} ${u.username} ${u.provider}`)}">
      <td data-th="Account"><a class="rowlink" href="/admin/users/${u.id}">${esc(u.display_name || u.username || `#${u.id}`)}</a><div class="cell-sub">${esc(u.provider)}</div></td>
      <td data-th="Used today" class="num">${used} / ${quota.dailyLimit()}</td>
    </tr>`;
  }).join('');

  const pending = store.listSiteAccounts().filter((s) => !s.user_id);
  const pendingRows = pending.map((s) => `<tr>
    <td data-th="Username" class="mono">@${esc(s.username)}</td>
    <td data-th="Password">${s.plain_password ? `<code class="mono small cred-pill">${esc(s.plain_password)}</code>` : `<span class="muted small mono">[Hashed]</span>`}</td>
    <td data-th="Signed up" class="small muted num">${esc(new Date(s.created_at).toLocaleString())}</td>
  </tr>`).join('');

  const body = `
  <div class="page-head rise">
    <p class="eyebrow"><span class="live-dot" aria-hidden="true"></span> Admin console · Live</p>
    <h1>Accounts<span class="grad-text">.</span></h1>
    <div class="stat-grid" aria-label="Today">
      <div class="stat-card"><div class="s-label"><span class="live-dot"></span>Accounts</div><div class="s-value num" data-count="${stats.users}">${stats.users}</div><div class="s-sub">total identities</div></div>
      <div class="stat-card accent-cyan"><div class="s-label"><span class="live-dot"></span>Active URLs</div><div class="s-value num" data-count="${stats.activeTokens}">${stats.activeTokens}</div><div class="s-sub">streaming endpoints</div></div>
      <div class="stat-card accent-emerald"><div class="s-label"><span class="live-dot"></span>Plays today</div><div class="s-value num" data-count="${stats.streamsToday}">${stats.streamsToday}</div><div class="s-sub">${esc(day)} UTC</div></div>
      <div class="stat-card accent-violet"><div class="s-label">Near limit</div><div class="s-value num" data-count="${nearLimit.length}">${nearLimit.length}</div><div class="s-sub">${NEAR_LIMIT_AT}+ plays</div></div>
    </div>
  </div>
  ${adminTabs('accounts')}
  ${clientauth.lockEnabled()
    ? `<div class="notice"><strong>Client lock is ON.</strong> Addon URLs answer only to requests signed with the app key — pasting them into any other player fails as “no addon”. Media links never expire; quota and revoke remain the abuse controls.</div>`
    : `<div class="notice warn"><strong>Client lock is OFF.</strong> Set <code>ADDON_CLIENT_SECRET</code> and restart — right now these URLs work in any compatible player.</div>`}
  ${nearLimit.length ? `
  <section class="panel panel-flush" aria-label="Near the daily limit">
    <div class="panel-h"><h2>Approaching 500</h2><p class="panel-sub">Played ${NEAR_LIMIT_AT}+ songs today (${esc(day)} UTC).</p></div>
    <div class="table-wrap"><table class="grid"><thead><tr><th>Account</th><th>Used today</th></tr></thead><tbody>${nearRows}</tbody></table></div>
  </section>` : ''}
  ${pending.length ? `
  <section class="panel panel-flush" aria-label="Waiting on verification">
    <div class="panel-h"><h2>Waiting on verification</h2><p class="panel-sub">Usernames created but not yet linked to Telegram/Discord — no addon URL, no quota.</p></div>
    <div class="table-wrap"><table class="grid"><thead><tr><th>Username</th><th>Password</th><th>Signed up</th></tr></thead><tbody>${pendingRows}</tbody></table></div>
  </section>` : ''}
  ${newAddonsBox}
  <section class="panel" aria-label="Create Addon without verification">
    <h2>Create Addon (No Verification)</h2>
    <p class="panel-sub">Instantly generate ready-to-use addon URLs for clients, testers, or friends without requiring Telegram or Discord verification.</p>
    <form method="post" action="/admin/create-addon" class="form-grid">
      <label class="field field-flat">
        <span>Label / Client Name</span>
        <input type="text" name="label" placeholder="e.g. VIP-Friend, Tester" maxlength="30" />
      </label>
      <label class="field field-flat">
        <span>Daily Limit (plays/day)</span>
        <input type="number" name="custom_limit" placeholder="Default (500)" min="0" max="100000" />
      </label>
      <label class="field field-flat">
        <span>Quantity</span>
        <select name="count">
          <option value="1">1 Addon</option>
          <option value="2">2 Addons</option>
          <option value="3">3 Addons</option>
          <option value="5">5 Addons</option>
          <option value="10">10 Addons</option>
        </select>
      </label>
      <div class="stack-col">
        <label class="switch switch-dense">
          <input type="checkbox" name="bypass" value="1" />
          <span class="track" aria-hidden="true"></span>
          <span>VIP Unlimited (bypass limit)</span>
        </label>
        <button class="btn primary small block" type="submit">Generate Addon(s)</button>
      </div>
    </form>
  </section>
  <section class="panel panel-flush" aria-label="All accounts">
    <div class="panel-h">
      <h2>All accounts</h2>
      <div class="toolbar"><input type="search" placeholder="Filter by name, handle, provider…" aria-label="Filter accounts" data-filter-table="tbody#accounts-body tr" /></div>
    </div>
    <div class="table-wrap"><table class="grid">
      <thead><tr><th>Account</th><th>Password</th><th>Addon URL</th><th>Status</th><th>Limit Mode</th><th>Used today</th><th>Left</th><th class="row-manage">Manage</th></tr></thead>
      <tbody id="accounts-body">${rows || ''}</tbody>
    </table></div>
    ${users.length ? `<div class="empty" data-filter-empty hidden><p>No accounts match that filter.</p></div>` : `<div class="empty"><p>No accounts yet. They appear here after the first verification.</p></div>`}
  </section>
  <section class="panel" aria-label="Danger zone">
    <h2>Revoke everything</h2>
    <p class="panel-sub">Stops playback for <strong class="num">${stats.activeTokens} active URLs</strong> at once. Every account keeps its identity and quota history, but all URLs go dead until individually unrevoked or regenerated.</p>
    <form method="post" action="/admin/revoke-all" data-confirm="Revoke EVERY active URL?" data-confirm-desc="All ${stats.activeTokens} active URLs stop working immediately."><button class="btn danger" type="submit" ${stats.activeTokens ? '' : 'disabled'}>Revoke all URLs</button></form>
  </section>
  <div class="admin-signout"><form method="post" action="/admin/logout"><button class="btn secondary small" type="submit">Sign out of admin</button></form></div>
  `;
  res.send(layout({ title: 'Admin · Accounts', body }));
});

// ---- verification codes ----

router.get('/admin/codes', requireAdmin, (req, res) => {
  const codes = store.recentVerifyCodes(50);
  const rows = codes.map((c) => `<tr data-search="${esc(`${c.code} ${c.provider} ${c.status} ${c.username} ${c.provider_user_id}`)}">
    <td data-th="Code" class="mono">${esc(c.code)}</td>
    <td data-th="Channel">${esc(c.provider)}</td>
    <td data-th="Status">${pill(c.status, c.status === 'verified' ? 'verified' : c.status === 'pending' ? 'pending' : 'muted')}</td>
    <td data-th="Account">${esc(c.username || c.provider_user_id || '—')}${c.membership ? `<div class="cell-sub">${esc(c.membership)}${c.account_age_days != null ? ` · ${c.account_age_days}d old` : ''}</div>` : ''}</td>
    <td data-th="Issued" class="small muted num">${esc(new Date(c.created_at).toLocaleString())}</td>
  </tr>`).join('');
  const body = `
  <div class="page-head"><p class="eyebrow">Admin console</p><h1>Verification codes.</h1>
  <p class="lede">Latest 50 codes. Pending codes are waiting on the bot; verified codes were approved after the channel check.</p></div>
  ${adminTabs('codes')}
  <section class="panel panel-flush">
    <div class="panel-h"><div class="toolbar"><input type="search" placeholder="Filter by code, user, status…" aria-label="Filter codes" data-filter-table="tbody#codes-body tr" /></div></div>
    <div class="table-wrap"><table class="grid">
      <thead><tr><th>Code</th><th>Channel</th><th>Status</th><th>Account</th><th>Issued</th></tr></thead>
      <tbody id="codes-body">${rows || ''}</tbody>
    </table></div>
    ${codes.length ? `<div class="empty" data-filter-empty hidden><p>No codes match that filter.</p></div>` : `<div class="empty"><p>No codes issued yet.</p></div>`}
  </section>`;
  res.send(layout({ title: 'Admin · Codes', body }));
});

// ---- account detail ----

router.get('/admin/users/:id', requireAdmin, (req, res) => {
  const user = store.getUserById(req.params.id);
  if (!user) return res.status(404).send('Account not found.');
  const site = store.getSiteAccountByUserId(user.id);
  const tokens = store.listTokens().filter((t) => String(t.user_id) === String(user.id));
  const active = tokens.find((t) => !t.revoked) || tokens[0];
  const usage = quota.usageFor(user);
  const history = store.dailyHistory(user.id, 30);
  const logs = store.recentLogs(user.id, 50);
  const codes = store.verifyCodesForUser(user.provider, user.provider_user_id, 10);
  const initials = (user.display_name || user.username || '?').trim().charAt(0).toUpperCase();
  const pct = usage.isBypassed ? 0 : Math.min(100, Math.round((usage.used / usage.limit) * 100));

  const tokenRows = tokens.map((t) => `
    <div class="rowline rowline-top"><span class="k mono small" id="adm-url-${t.id}">${esc(`${config.baseUrl}/a/${t.token}/`)}</span>
    <span class="v rowline-end">${pill(t.revoked ? 'Revoked' : 'Active', t.revoked ? 'revoked' : 'active')}<button class="btn secondary small" data-copy="#adm-url-${t.id}" type="button">Copy</button></span></div>`).join('');
  const histRows = history.map((h) => `<tr><td data-th="Day (UTC)" class="mono">${esc(h.day)}</td><td data-th="Songs" class="num">${h.count}</td></tr>`).join('');
  const logRows = logs.map((l) => `<tr><td data-th="Time" class="mono small">${esc(new Date(l.created_at).toISOString())}</td><td data-th="Track" class="mono">${esc(l.track_id)}</td><td data-th="Quality">${esc(l.quality || '')}</td></tr>`).join('');
  const codeRows = codes.map((c) => `<tr><td data-th="Code" class="mono">${esc(c.code)}</td><td data-th="Status">${pill(c.status, c.status === 'verified' ? 'verified' : c.status === 'pending' ? 'pending' : 'muted')}</td><td data-th="Issued" class="small muted num">${esc(new Date(c.created_at).toLocaleString())}</td></tr>`).join('');

  const body = `
  <p class="small back-link"><a href="/admin">← All accounts</a></p>
  ${user.is_banned ? `
  <div class="notice notice-danger">
    <strong>LIFETIME BANNED:</strong> This account and its linked ${esc(user.provider)} identity (<span class="mono">${esc(user.provider_user_id)}</span>) are permanently banned. Playback, web login, and channel verification are blocked forever.
    ${user.ban_reason ? `<div class="small muted rowline-desc">Reason: ${esc(user.ban_reason)}</div>` : ''}
    ${user.banned_at ? `<div class="small muted">Banned at: ${new Date(user.banned_at).toUTCString()}</div>` : ''}
  </div>` : ''}
  <div class="page-head">
    <div class="identity">
      ${user.avatar_url ? `<img class="avatar" src="${esc(user.avatar_url)}" alt="" />` : `<span class="avatar avatar-fallback" aria-hidden="true">${esc(initials)}</span>`}
      <div><h1 class="detail-title">${esc(user.display_name || user.username || `#${user.id}`)}</h1>
      <div class="sub muted small">@${esc(site ? site.username : '—')} · ${esc(user.provider)} · id <span class="mono">${esc(user.provider_user_id)}</span>${user.verified_via ? ` · verified via ${esc(user.verified_via)}` : ''}${user.membership ? ` · ${esc(user.membership)}` : ''}${user.account_age_days != null ? ` · ${user.account_age_days}d old` : ''}</div>
      <div class="pills">
        ${user.is_banned ? pill('Lifetime Banned', 'banned') : ''}
        ${user.bypass_limit ? pill('VIP Unlimited', 'vip') : ''}
        ${user.custom_limit ? pill(`Custom: ${user.custom_limit}/day`, 'custom') : ''}
      </div>
      <div class="mention-row">${mentionHtml(user)}</div></div>
    </div>
  </div>
  <div class="split">
    <div>
      <section class="panel" aria-label="Quota">
        <h2>Today · ${esc(usage.day)} UTC</h2>
        <div class="quota-figures">
          ${usage.isBypassed
            ? `<span class="big vip-display">VIP Unlimited</span><span class="of num">${usage.used} songs played today</span>`
            : `<span class="big num">${usage.used}</span><span class="of num">/ ${usage.limit} played · ${usage.remaining} left</span>`}
        </div>
        ${usage.isBypassed
          ? `<div class="notice notice-success"><strong>VIP Bypass Active.</strong> Unlimited plays, quota check is bypassed.</div>`
          : `<div class="meter" role="progressbar" aria-valuenow="${usage.used}" aria-valuemin="0" aria-valuemax="${usage.limit}"><i style="width:${pct}%"></i></div>
             <div class="sub muted small" style="margin-top:8px">
               Base limit: <strong class="num">${usage.baseLimit}</strong>/day${usage.isCustom ? ' (custom)' : ' (default)'}
               ${usage.bonusPlays > 0 ? ` · Bonus today: <strong class="num">+${usage.bonusPlays}</strong> plays` : ''}
             </div>`}
      </section>
      <section class="panel" aria-label="Quota & Limit Maintenance">
        <h2>Quota & Limit Controls</h2>
        <p class="panel-sub">Maintain daily limits, bypass limits for VIP users, or grant extra plays today.</p>
        
        <div class="rowline rowline-top">
          <div class="grow">
            <strong>VIP Limit Bypass</strong>
            <p class="muted small rowline-desc">Bypass the daily quota check entirely for unlimited playback.</p>
          </div>
          <form method="post" action="/admin/users/${user.id}/bypass">
            <input type="hidden" name="bypass" value="${user.bypass_limit ? '0' : '1'}" />
            <button class="btn ${user.bypass_limit ? 'secondary' : 'primary'} small" type="submit">
              ${user.bypass_limit ? 'Disable VIP Bypass' : 'Enable VIP Bypass (Unlimited)'}
            </button>
          </form>
        </div>

        <div class="rowline rowline-top">
          <div class="grow">
            <strong>Maintain Daily Limit</strong>
            <p class="muted small rowline-desc">Set custom limit (standard is 500). Enter 0 or leave blank to reset.</p>
          </div>
          <form method="post" action="/admin/users/${user.id}/limit" class="grant-row">
            <input type="number" name="limit" value="${user.custom_limit || ''}" placeholder="500" min="0" max="100000" class="input-xs" />
            <button class="btn secondary small" type="submit">Save</button>
          </form>
        </div>

        <div class="rowline rowline-top">
          <div class="grow">
            <strong>Grant Extra Plays Today</strong>
            <p class="muted small rowline-desc">Immediately add bonus plays for today (${esc(usage.day)} UTC).</p>
          </div>
          <form method="post" action="/admin/users/${user.id}/grant-extra" class="grant-row">
            <button class="btn secondary small" type="submit" name="amount" value="50">+50</button>
            <button class="btn secondary small" type="submit" name="amount" value="100">+100</button>
            <button class="btn secondary small" type="submit" name="amount" value="250">+250</button>
            <button class="btn secondary small" type="submit" name="amount" value="500">+500</button>
            <div class="grant-custom">
              <input type="number" name="custom_amount" placeholder="Custom" min="1" max="50000" class="input-xs-narrow" />
              <button class="btn secondary small" type="submit">Grant</button>
            </div>
          </form>
        </div>
      </section>
      <section class="panel" aria-label="Addon URLs">
        <h2>Addon URLs</h2>
        <p class="panel-sub">Revoking stops playback on that URL immediately.</p>
        ${tokenRows || emptyState('No URLs issued.')}
        <div class="actions-row">
          <form method="post" action="/admin/users/${user.id}/regenerate" data-confirm="Regenerate this URL?" data-confirm-desc="The current URL stops working immediately."><button class="btn secondary small" type="submit">Regenerate</button></form>
          ${active && !active.revoked ? `<form method="post" action="/admin/users/${user.id}/revoke" data-confirm="Revoke this URL?" data-confirm-desc="Playback on that URL stops immediately."><button class="btn danger small" type="submit">Revoke</button></form>` : ''}
          ${active && active.revoked ? `<form method="post" action="/admin/users/${user.id}/unrevoke"><button class="btn secondary small" type="submit">Unrevoke</button></form>` : ''}
          <form method="post" action="/admin/users/${user.id}/delete" data-confirm="Delete this account?" data-confirm-desc="Removes the account, its URLs, quota and logs."><button class="btn danger small" type="submit">Delete</button></form>
        </div>
      </section>
      <section class="panel panel-flush" aria-label="Recent plays">
        <div class="panel-h"><h2>Recent plays</h2><p class="panel-sub">Last 50 counted stream resolutions.</p></div>
        <div class="table-wrap"><table class="grid"><thead><tr><th>Time</th><th>Track</th><th>Quality</th></tr></thead><tbody>${logRows || ''}</tbody></table></div>
        ${logs.length ? '' : `<div class="empty"><p>No plays recorded yet.</p></div>`}
      </section>
    </div>
    <div>
      <section class="panel" aria-label="Account Credentials">
        <h2>Account & Password</h2>
        <p class="panel-sub">Website login credentials for this account.</p>
        <div class="rowline">
          <div><strong>Username</strong></div>
          <div class="mono" style="font-weight:600">@${esc(site ? site.username : 'No web account')}</div>
        </div>
        <div class="rowline">
          <div><strong>Password</strong></div>
          <div>
            ${site && site.plain_password
              ? `<code class="mono cred-box">${esc(site.plain_password)}</code>`
              : site
                ? `<span class="muted small mono">[Hashed — set new below]</span>`
                : `<span class="muted small">None</span>`
            }
          </div>
        </div>
        <form method="post" action="/admin/users/${user.id}/password" class="inline-form">
          <input type="text" name="password" placeholder="Set or change password" required minlength="4" maxlength="64" />
          <button class="btn secondary small" type="submit">Set Password</button>
        </form>
      </section>
      <section class="panel" aria-label="Account Moderation">
        <h2>Lifetime Ban & Moderation</h2>
        <p class="panel-sub">Lifetime ban permanently revokes all addon URLs, invalidates web sessions, and blacklists the ${esc(user.provider)} identity forever.</p>
        ${user.is_banned ? `
          <form method="post" action="/admin/users/${user.id}/unban" data-confirm="Unban this account?" data-confirm-desc="Removes the lifetime ban and allows the account and identity to function again.">
            <button class="btn secondary small" type="submit">Remove Lifetime Ban (Unban)</button>
          </form>
        ` : `
          <form method="post" action="/admin/users/${user.id}/ban" data-confirm="PERMANENTLY BAN this account?" data-confirm-desc="Lifetime ban: stops all streams, revokes all tokens, blocks login, and permanently blacklists this ${esc(user.provider)} identity.">
            <label class="field field-tight">
              <span>Ban reason (optional)</span>
              <input type="text" name="reason" placeholder="e.g. Abusive usage, terms violation" />
            </label>
            <button class="btn danger-solid small" type="submit">Lifetime Ban Account</button>
          </form>
        `}
      </section>
      <section class="panel panel-flush" aria-label="History">
        <div class="panel-h"><h2>30-day history</h2></div>
        <div class="table-wrap"><table class="grid"><thead><tr><th>Day (UTC)</th><th>Songs</th></tr></thead><tbody>${histRows || ''}</tbody></table></div>
        ${history.length ? '' : `<div class="empty"><p>No history yet.</p></div>`}
      </section>
      <section class="panel panel-flush" aria-label="Codes">
        <div class="panel-h"><h2>Verification codes</h2></div>
        <div class="table-wrap"><table class="grid"><thead><tr><th>Code</th><th>Status</th><th>Issued</th></tr></thead><tbody>${codeRows || ''}</tbody></table></div>
        ${codes.length ? '' : `<div class="empty"><p>No codes for this account.</p></div>`}
      </section>
    </div>
  </div>`;
  res.send(layout({ title: `Admin · ${user.display_name || user.username || user.id}`, body }));
});

router.post('/admin/revoke-all', requireAdmin, (req, res) => {
  const active = store.listTokens().filter((t) => !t.revoked);
  for (const t of active) store.setTokenRevoked(t.id, true);
  res.redirect('/admin');
});

router.post('/admin/users/:id/regenerate', requireAdmin, (req, res) => {
  store.regenerateToken(req.params.id);
  res.redirect(`/admin/users/${req.params.id}`);
});

router.post('/admin/users/:id/revoke', requireAdmin, (req, res) => {
  const tokens = store.listTokens().filter((t) => String(t.user_id) === String(req.params.id) && !t.revoked);
  for (const t of tokens) store.setTokenRevoked(t.id, true);
  const back = req.get('Referer') || '';
  res.redirect(back.includes(`/admin/users/${req.params.id}`) ? `/admin/users/${req.params.id}` : '/admin');
});

router.post('/admin/users/:id/unrevoke', requireAdmin, (req, res) => {
  const tokens = store.listTokens().filter((t) => String(t.user_id) === String(req.params.id));
  const latest = tokens[0];
  if (latest) store.setTokenRevoked(latest.id, false);
  res.redirect(`/admin/users/${req.params.id}`);
});

router.post('/admin/users/:id/delete', requireAdmin, (req, res) => {
  store.deleteUser(req.params.id);
  res.redirect('/admin');
});

router.post('/admin/users/:id/ban', requireAdmin, express.urlencoded({ extended: false }), (req, res) => {
  const reason = String(req.body.reason || 'Banned by administrator').trim();
  store.banUser(req.params.id, reason);
  const back = req.get('Referer') || '';
  res.redirect(back.includes(`/admin/users/${req.params.id}`) ? `/admin/users/${req.params.id}` : '/admin');
});

router.post('/admin/users/:id/unban', requireAdmin, (req, res) => {
  store.unbanUser(req.params.id);
  const back = req.get('Referer') || '';
  res.redirect(back.includes(`/admin/users/${req.params.id}`) ? `/admin/users/${req.params.id}` : '/admin');
});

router.post('/admin/users/:id/limit', requireAdmin, express.urlencoded({ extended: false }), (req, res) => {
  store.setCustomLimit(req.params.id, req.body.limit);
  res.redirect(`/admin/users/${req.params.id}`);
});

router.post('/admin/users/:id/bypass', requireAdmin, express.urlencoded({ extended: false }), (req, res) => {
  store.setBypassLimit(req.params.id, req.body.bypass === '1');
  res.redirect(`/admin/users/${req.params.id}`);
});

router.post('/admin/users/:id/grant-extra', requireAdmin, express.urlencoded({ extended: false }), (req, res) => {
  const amount = parseInt(req.body.custom_amount || req.body.amount || '0', 10);
  if (amount > 0) {
    store.grantBonusPlays(req.params.id, quota.todayKey(), amount);
  }
  res.redirect(`/admin/users/${req.params.id}`);
});

router.post('/admin/users/:id/password', requireAdmin, express.urlencoded({ extended: false }), (req, res) => {
  const userId = parseInt(req.params.id, 10);
  const password = String(req.body.password || '').trim();
  if (password) {
    store.adminSetUserPassword(userId, password);
  }
  res.redirect(`/admin/users/${userId}`);
});

router.post('/admin/create-addon', requireAdmin, express.urlencoded({ extended: false }), (req, res) => {
  const label = String(req.body.label || '').trim();
  const customLimit = req.body.custom_limit ? parseInt(req.body.custom_limit, 10) : null;
  const bypassLimit = req.body.bypass === '1' ? 1 : 0;
  const count = Math.min(20, Math.max(1, parseInt(req.body.count || '1', 10)));
  
  const createdIds = [];
  for (let i = 0; i < count; i++) {
    const itemLabel = count > 1 && label ? `${label} #${i + 1}` : label;
    const created = store.createDirectAddon({
      label: itemLabel,
      customLimit,
      bypassLimit,
    });
    createdIds.push(created.user.id);
  }
  
  res.redirect(`/admin?new_ids=${createdIds.join(',')}`);
});

// ---- JSON API (for automation) ----

router.get('/admin/api/users', requireAdmin, (req, res) => {
  const day = quota.todayKey();
  const users = store.listUsers().map((u) => {
    const uQuota = quota.usageFor(u);
    const tokens = store.listTokens().filter((t) => t.user_id === u.id);
    const site = store.getSiteAccountByUserId(u.id);
    return {
      ...u,
      site_username: site ? site.username : null,
      addon_urls: tokens.map((t) => ({ url: `${config.baseUrl}/a/${t.token}/`, revoked: !!t.revoked })),
      quota: uQuota,
    };
  });
  res.json({ day, limit: quota.dailyLimit(), users });
});

module.exports = router;

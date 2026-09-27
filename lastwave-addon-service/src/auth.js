'use strict';

/**
 * Authentication: real Telegram / Discord account identity, verified server-side.
 *
 * Telegram: Login Widget data validated with HMAC-SHA256(bot_token).
 *   data_check_string = "k=v" lines for all fields except `hash`, sorted by key,
 *   joined with "\n". secret = SHA256(bot_token). Check hash + auth_date freshness.
 *   https://core.telegram.org/widgets/login#checking-authorization
 *
 * Discord: standard OAuth2 authorization-code flow against discord.com/api.
 */

const crypto = require('crypto');
const config = require('./config');
const store = require('./db');

function verifyTelegram(data) {
  const botToken = config.telegramBotToken;
  if (!botToken) return { ok: false, error: 'Telegram login is not configured on this server.' };
  const { hash, ...rest } = data || {};
  if (!hash || typeof hash !== 'string' || !/^[0-9a-fA-F]{64}$/.test(hash)) {
    return { ok: false, error: 'Invalid Telegram signature.' };
  }
  const checkString = Object.keys(rest)
    .sort()
    .map((k) => `${k}=${rest[k]}`)
    .join('\n');
  const secret = crypto.createHash('sha256').update(botToken).digest();
  const calc = crypto.createHmac('sha256', secret).update(checkString).digest();
  let valid = false;
  try {
    valid = crypto.timingSafeEqual(calc, Buffer.from(String(hash), 'hex'));
  } catch {
    valid = false;
  }
  if (!valid) {
    return { ok: false, error: 'Invalid Telegram signature.' };
  }
  const authDate = parseInt(rest.auth_date || '0', 10);
  if (!authDate || Math.abs(Date.now() / 1000 - authDate) > 24 * 60 * 60) {
    return { ok: false, error: 'Telegram login expired. Please try again.' };
  }
  const id = String(rest.id || '');
  if (!id) return { ok: false, error: 'Invalid Telegram identity.' };
  return {
    ok: true,
    identity: {
      provider: 'telegram',
      providerUserId: id,
      username: rest.username || '',
      displayName: [rest.first_name, rest.last_name].filter(Boolean).join(' ') || rest.username || `Telegram ${id}`,
      avatarUrl: rest.photo_url || '',
    },
  };
}

function discordAuthorizeUrl(state) {
  const scope = (config.discordGuildId && config.discordBotToken) ? 'identify guilds.join' : 'identify';
  const params = new URLSearchParams({
    client_id: config.discordClientId,
    redirect_uri: config.discordRedirectUri,
    response_type: 'code',
    scope,
    state,
    prompt: 'consent',
  });
  return `https://discord.com/oauth2/authorize?${params.toString()}`;
}

async function exchangeDiscordCode(code) {
  const body = new URLSearchParams({
    client_id: config.discordClientId,
    client_secret: config.discordClientSecret,
    grant_type: 'authorization_code',
    code,
    redirect_uri: config.discordRedirectUri,
  });
  const tokenRes = await fetch('https://discord.com/api/oauth2/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  if (!tokenRes.ok) {
    const errText = await tokenRes.text().catch(() => '');
    throw new Error(`Discord token exchange failed (HTTP ${tokenRes.status}): ${errText}`);
  }
  const tokenJson = await tokenRes.json();
  const accessToken = tokenJson.access_token;
  if (!accessToken) throw new Error('Discord did not return an access token.');
  const meRes = await fetch('https://discord.com/api/users/@me', {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!meRes.ok) throw new Error(`Discord user fetch failed (HTTP ${meRes.status})`);
  const me = await meRes.json();
  if (!me.id) throw new Error('Invalid Discord identity.');

  // Pure OAuth auto-server join: seamlessly add member to your Discord guild
  let joinedGuild = false;
  if (config.discordGuildId && config.discordBotToken) {
    try {
      const joinRes = await fetch(`https://discord.com/api/v10/guilds/${config.discordGuildId}/members/${me.id}`, {
        method: 'PUT',
        headers: {
          Authorization: `Bot ${config.discordBotToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ access_token: accessToken }),
      });
      if (joinRes.status === 201) {
        console.log(`[discord-oauth] User ${me.username} (${me.id}) automatically joined server ${config.discordGuildId}`);
        joinedGuild = true;
      } else if (joinRes.status === 204) {
        console.log(`[discord-oauth] User ${me.username} (${me.id}) was already in server ${config.discordGuildId}`);
        joinedGuild = true;
      } else {
        const errText = await joinRes.text().catch(() => '');
        console.warn(`[discord-oauth] Server auto-join returned HTTP ${joinRes.status}:`, errText);
      }
    } catch (err) {
      console.warn('[discord-oauth] Guild join request error:', err.message);
    }
  }

  const avatarUrl = me.avatar
    ? `https://cdn.discordapp.com/avatars/${me.id}/${me.avatar}.png`
    : '';
  return {
    provider: 'discord',
    providerUserId: String(me.id),
    username: me.username || '',
    displayName: me.global_name || me.username || `Discord ${me.id}`,
    avatarUrl,
    membership: joinedGuild ? 'joined' : '',
    verifiedVia: 'oauth',
  };
}

// ---------- passwords (Node scrypt, no extra dependencies) ----------

const SCRYPT_N = 16384;
const SCRYPT_R = 8;
const SCRYPT_P = 1;
const SCRYPT_KEYLEN = 64;

function passwordError(raw) {
  const pw = String(raw || '');
  if (pw.length < 8) return 'Password must be at least 8 characters.';
  if (pw.length > 128) return 'Password must be at most 128 characters.';
  return null;
}

function hashPassword(raw) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(raw), salt, SCRYPT_KEYLEN, { N: SCRYPT_N, r: SCRYPT_R, p: SCRYPT_P });
  return `scrypt$${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt.toString('hex')}$${hash.toString('hex')}`;
}

function checkPassword(raw, stored) {
  try {
    const parts = String(stored || '').split('$');
    if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
    const N = parseInt(parts[1], 10);
    const r = parseInt(parts[2], 10);
    const p = parseInt(parts[3], 10);
    const salt = Buffer.from(parts[4], 'hex');
    const expected = Buffer.from(parts[5], 'hex');
    if (!Number.isFinite(N) || !Number.isFinite(r) || !Number.isFinite(p)) return false;
    if (salt.length !== 16 || expected.length !== SCRYPT_KEYLEN) return false;
    const actual = crypto.scryptSync(String(raw), salt, SCRYPT_KEYLEN, { N, r, p });
    return crypto.timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

// ---------- session middleware ----------

function getSession(req) {
  const sid = req.signedCookies ? req.signedCookies.sid : null;
  if (!sid) return null;
  return store.getSession(sid);
}

function attachUser(req, _res, next) {
  req.session = getSession(req);
  req.siteAccount = req.session && req.session.site_account_id
    ? store.getSiteAccountById(req.session.site_account_id)
    : null;
  // Linked provider identity (null until the account finishes bot verification).
  // Old sessions pre-dating site accounts carry user_id directly.
  const linkedId = req.siteAccount
    ? req.siteAccount.user_id
    : req.session && req.session.user_id
      ? req.session.user_id
      : null;
  req.user = linkedId ? store.getUserById(linkedId) : null;
  if (req.user && (req.user.is_banned || store.isIdentityBanned(req.user.provider, req.user.provider_user_id))) {
    req.user.is_banned = 1;
  }
  req.isAdmin = !!(req.session && req.session.is_admin);
  next();
}

/** Signed in with a site username+password (verified or not). */
function requireLogin(req, res, next) {
  if (!req.siteAccount) {
    if (req.path.startsWith('/api/')) return res.status(401).json({ error: 'Sign in required.' });
    return res.redirect('/login');
  }
  if (req.user && req.user.is_banned && !req.path.startsWith('/banned') && !req.path.startsWith('/logout')) {
    if (req.path.startsWith('/api/')) return res.status(403).json({ error: 'Account is permanently banned.' });
    return res.redirect('/banned');
  }
  next();
}

/** Signed in AND bot-verified (provider identity linked). */
function requireVerified(req, res, next) {
  if (!req.siteAccount) {
    if (req.path.startsWith('/api/')) return res.status(401).json({ error: 'Sign in required.' });
    return res.redirect('/login');
  }
  if (!req.user) {
    if (req.path.startsWith('/api/')) return res.status(403).json({ error: 'Verification required.' });
    return res.redirect('/verify');
  }
  if (req.user.is_banned && !req.path.startsWith('/banned') && !req.path.startsWith('/logout')) {
    if (req.path.startsWith('/api/')) return res.status(403).json({ error: 'Account is permanently banned.' });
    return res.redirect('/banned');
  }
  next();
}

function requireAdmin(req, res, next) {
  if (!req.isAdmin) {
    if (req.path.startsWith('/api/')) return res.status(401).json({ error: 'Admin login required.' });
    return res.redirect('/admin/login');
  }
  next();
}

module.exports = {
  verifyTelegram,
  discordAuthorizeUrl,
  exchangeDiscordCode,
  passwordError,
  hashPassword,
  checkPassword,
  attachUser,
  requireLogin,
  requireVerified,
  requireAdmin,
};

'use strict';

require('dotenv').config();

function required(name, fallback) {
  const v = process.env[name] ?? fallback;
  return v;
}

const config = {
  port: parseInt(process.env.PORT || '8787', 10),
  baseUrl: (process.env.BASE_URL || 'http://localhost:8787').replace(/\/+$/, ''),
  sessionSecret: process.env.SESSION_SECRET || 'dev-only-session-secret-change-me',
  upstreamBaseUrl: (process.env.UPSTREAM_BASE_URL || 'https://tidal.kanjijewels.com').replace(/\/+$/, ''),
  upstreamApiKey: process.env.UPSTREAM_API_KEY || '',
  dailySongLimit: parseInt(process.env.DAILY_SONG_LIMIT || '500', 10),
  telegramBotUsername: process.env.TELEGRAM_BOT_USERNAME || '',
  telegramBotToken: process.env.TELEGRAM_BOT_TOKEN || '',
  discordClientId: process.env.DISCORD_CLIENT_ID || '',
  discordClientSecret: process.env.DISCORD_CLIENT_SECRET || '',
  discordRedirectUri: process.env.DISCORD_REDIRECT_URI || '',
  // ---- Channel-gated bot verification ----
  // Telegram channel the user must join (e.g. "@clashprojects" or "-1001234567890").
  telegramRequiredChannel: process.env.TELEGRAM_REQUIRED_CHANNEL || '',
  // Invite link sent when the user hasn't joined yet.
  telegramInviteUrl: process.env.TELEGRAM_INVITE_URL || '',
  // Discord bot + guild the user must be a member of.
  discordBotToken: process.env.DISCORD_BOT_TOKEN || '',
  discordGuildId: process.env.DISCORD_GUILD_ID || '',
  discordInviteUrl: process.env.DISCORD_INVITE_URL || '',
  // Discord accounts younger than this are rejected (Telegram has no age gate).
  discordMinAccountAgeDays: parseInt(process.env.DISCORD_MIN_ACCOUNT_AGE_DAYS || '30', 10),
  // Verification codes (website -> bot -> website) live this long.
  verifyCodeTtlMin: parseInt(process.env.VERIFY_CODE_TTL_MIN || '10', 10),
  // No hardcoded defaults: with no ADMIN_TOKEN set, /admin/login refuses
  // outright (fail closed). Never commit a real token here.
  adminToken: process.env.ADMIN_TOKEN || '',
  devLoginPassword: process.env.DEV_LOGIN_PASSWORD || process.env.ADMIN_TOKEN || '',
  // ---- GitHub star bonus (+50% daily quota while starred) ----
  // Create an OAuth App (no scopes needed) with callback
  //   <BASE_URL>/auth/github/callback  and set the two vars below.
  githubClientId: process.env.GITHUB_CLIENT_ID || '',
  githubClientSecret: process.env.GITHUB_CLIENT_SECRET || '',
  // Bonus tasks: star ALL of these repos AND follow ALL of these accounts.
  githubStarRepos: String(process.env.GITHUB_STAR_REPOS || 'Clash-Projects/LastWave-Native,Clash-Projects/LastWave-Desktop')
    .split(',').map((s) => s.trim()).filter(Boolean),
  githubFollowAccounts: String(process.env.GITHUB_FOLLOW_ACCOUNTS || 'ajisth69,Clash-Projects')
    .split(',').map((s) => s.trim()).filter(Boolean),
  githubRedirectUri: (process.env.GITHUB_REDIRECT_URI ||
    `${(process.env.BASE_URL || 'http://localhost:8787').replace(/\/+$/, '')}/auth/github/callback`),
  allowDevLogin: String(process.env.ALLOW_DEV_LOGIN || 'false').toLowerCase() === 'true',
  // App-embedded client secret(s) for the one-way lock. Comma-separated list
  // allowed ("old,new") so rotations overlap. Empty = lock OFF (dev only).
  addonClientSecrets: String(process.env.ADDON_CLIENT_SECRET || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean),
  isProd: process.env.NODE_ENV === 'production' || (process.env.BASE_URL || '').startsWith('https://'),
};

if (!config.upstreamApiKey) {
  console.warn('[config] WARNING: UPSTREAM_API_KEY is empty. Addon search/stream will fail until set.');
}

module.exports = config;

'use strict';

/**
 * Telegram verification bot (long polling, no extra dependencies).
 *
 * User flow:
 *  1. Website shows a 6-letter code + "Open @Bot -> send the code".
 *  2. User DMs the code to the bot (or opens t.me/Bot?start=CODE).
 *  3. Bot checks getChatMember(REQUIRED_CHANNEL, user) — joined?
 *     - not joined -> replies with join link + "send the code again after joining".
 *     - joined     -> marks the code verified, replies "verified — type the
 *                     code back on the website". No account-age gate for Telegram.
 */

const config = require('../config');
const store = require('../db');
const { normalizeCode, isCodeShape, telegramStatusIsMember, telegramInviteFor } = require('../verify');

const API = 'https://api.telegram.org';

let running = false;

async function api(method, params = {}) {
  const res = await fetch(`${API}/bot${config.telegramBotToken}/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
  const json = await res.json().catch(() => ({}));
  if (!json.ok) {
    const err = new Error(`Telegram API ${method} failed: ${json.description || res.status}`);
    err.telegram = json;
    throw err;
  }
  return json.result;
}

function extractCodeFromText(text) {
  if (!text) return null;
  // "/start ABC123" (deep link) or a bare "ABC123" message.
  const m = String(text).trim().match(/^(?:\/start(?:@\S+)?\s+)?([A-Za-z0-9]{4,12})$/);
  if (!m) return null;
  const code = normalizeCode(m[1]);
  return isCodeShape(code) ? code : null;
}

async function checkMembership(userId) {
  const channel = config.telegramRequiredChannel;
  if (!channel) return { configured: false, joined: false, status: '' };
  const member = await api('getChatMember', { chat_id: channel, user_id: Number(userId) });
  const status = member?.status || '';
  const joined = telegramStatusIsMember(status, member?.is_member);
  return { configured: true, joined, status };
}

async function handleCode(chatId, from, code) {
  if (store.isIdentityBanned('telegram', from.id)) {
    await api('sendMessage', {
      chat_id: chatId,
      text: `Your Telegram account has been permanently banned from LastWave Addons.`,
    });
    return;
  }
  const pending = store.getVerifyCode(code, 'telegram');
  if (!pending) {
    await api('sendMessage', {
      chat_id: chatId,
      text: `I don't recognise code ${code}. Get a fresh code on the website and send it here.`,
    });
    return;
  }
  if (pending.status === 'consumed') {
    await api('sendMessage', { chat_id: chatId, text: `Code ${code} was already used. Get a fresh one on the website.` });
    return;
  }
  if (pending.expires_at < Date.now()) {
    await api('sendMessage', { chat_id: chatId, text: `Code ${code} expired. Get a fresh one on the website.` });
    return;
  }
  if (pending.status === 'verified') {
    await api('sendMessage', {
      chat_id: chatId,
      text: `Code ${code} is already verified. Type it back on the website to finish signing in.`,
    });
    return;
  }

  const identity = {
    providerUserId: String(from.id),
    username: from.username || '',
    displayName: [from.first_name, from.last_name].filter(Boolean).join(' ') || from.username || `Telegram ${from.id}`,
    avatarUrl: '',
  };

  let membership = '';
  try {
    const check = await checkMembership(identity.providerUserId);
    if (!check.configured) {
      membership = 'unknown (channel not configured)';
    } else if (!check.joined) {
      const invite = telegramInviteFor(config.telegramRequiredChannel, config.telegramInviteUrl);
      await api('sendMessage', {
        chat_id: chatId,
        text: [
          `You're not in our channel yet, so I can't verify code ${code}.`,
          invite ? `Join here: ${invite}` : 'Please join our channel first.',
          'Then send the code here again and I will verify it.',
        ].join('\n'),
      });
      return;
    } else {
      membership = check.status || 'member';
    }
  } catch (e) {
    // getChatMember fails when the bot isn't an admin of the channel or the
    // channel id is wrong — tell the user instead of silently failing.
    await api('sendMessage', {
      chat_id: chatId,
      text: `I couldn't check channel membership right now (${e.message}). Make sure you've joined, then send the code again in a minute.`,
    });
    return;
  }

  store.markVerifyCode(code, 'telegram', { ...identity, membership, accountAgeDays: null });
  await api('sendMessage', {
    chat_id: chatId,
    text: [
      `Verified — code ${code} is now approved for @${identity.username || identity.displayName}.`,
      'Type this same code back on the website to finish signing in and get your addon URL.',
    ].join('\n'),
  });
}

async function handleUpdate(update) {
  const msg = update.message;
  if (!msg || !msg.from || msg.from.is_bot) return;
  const chatId = msg.chat?.id;
  const text = msg.text || '';
  if (!chatId || !text) return;

  if (/^\/start(\s|$)/.test(text) && !extractCodeFromText(text)) {
    const cleanUsername = String(config.telegramBotUsername || '').replace(/^@/, '');
    const botName = cleanUsername ? `@${cleanUsername}` : 'this bot';
    await api('sendMessage', {
      chat_id: chatId,
      text: [
        `Hi! To verify your LastWave account, get a 6-letter code on the website, then send it to ${botName}.`,
        'I will check that you joined our channel and approve the code — then you type it back on the website.',
      ].join('\n'),
    });
    return;
  }

  const code = extractCodeFromText(text);
  if (!code) {
    await api('sendMessage', {
      chat_id: chatId,
      text: 'Send me the 6-letter code shown on the website (e.g. KQ7XPD).',
    });
    return;
  }
  await handleCode(chatId, msg.from, code);
}

/**
 * DM a user directly (used for password-reset codes).
 * Works because verification already required them to message the bot first.
 * Throws with a human-readable message when delivery fails.
 */
async function sendDirect(userId, text) {
  if (!config.telegramBotToken) throw new Error('Telegram bot is not enabled on this server.');
  try {
    await api('sendMessage', { chat_id: Number(userId), text });
  } catch (e) {
    throw new Error('Could not DM you on Telegram (did you block the bot?). Try again later.');
  }
}

async function pollLoop() {
  let offset = 0;
  while (running) {
    try {
      const res = await fetch(`${API}/bot${config.telegramBotToken}/getUpdates`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ offset, timeout: 30, allowed_updates: ['message'] }),
      });
      const json = await res.json().catch(() => ({}));
      if (!json.ok) {
        if (json.error_code === 401) {
          console.error('[telegram-bot] Invalid TELEGRAM_BOT_TOKEN, stopping bot.');
          running = false;
          return;
        }
        throw new Error(json.description || `HTTP ${res.status}`);
      }
      for (const update of json.result || []) {
        offset = Math.max(offset, (update.update_id || 0) + 1);
        try {
          await handleUpdate(update);
        } catch (e) {
          console.error('[telegram-bot] update failed:', e.message);
        }
      }
    } catch (e) {
      console.error('[telegram-bot] poll error:', e.message);
      await new Promise((r) => setTimeout(r, 5000));
    }
  }
}

async function startTelegramBot() {
  if (!config.telegramBotToken) {
    console.log('[telegram-bot] TELEGRAM_BOT_TOKEN not set — Telegram code verification disabled.');
    return null;
  }
  try {
    const me = await api('getMe', {});
    console.log(`[telegram-bot] running as @${me.username} (${me.id})`);
    if (config.telegramBotUsername && me.username?.toLowerCase() !== config.telegramBotUsername.toLowerCase().replace(/^@/, '')) {
      console.warn(`[telegram-bot] WARNING: bot is @${me.username} but TELEGRAM_BOT_USERNAME=${config.telegramBotUsername}.`);
    }
    if (!config.telegramRequiredChannel) {
      console.warn('[telegram-bot] WARNING: TELEGRAM_REQUIRED_CHANNEL not set — membership check will be skipped until configured.');
    }
  } catch (e) {
    console.error('[telegram-bot] getMe failed, bot disabled:', e.message);
    return null;
  }
  running = true;
  pollLoop();
  return { stop: () => { running = false; } };
}

module.exports = { startTelegramBot, extractCodeFromText, handleUpdate, sendDirect };

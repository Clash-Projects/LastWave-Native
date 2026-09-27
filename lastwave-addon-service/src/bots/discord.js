'use strict';

/**
 * Discord REST helpers (pure HTTP, zero gateway bot connection).
 * All authentication is handled via browser OAuth2.
 */

const config = require('../config');

/**
 * Send a direct message to a user via Discord REST API (for password reset fallback).
 */
async function sendDirect(userId, text) {
  if (!config.discordBotToken) {
    throw new Error('Discord messaging is not configured on this server.');
  }

  // 1. Open DM channel with the user
  const channelRes = await fetch('https://discord.com/api/v10/users/@me/channels', {
    method: 'POST',
    headers: {
      Authorization: `Bot ${config.discordBotToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ recipient_id: String(userId) }),
  });

  if (!channelRes.ok) {
    const errText = await channelRes.text().catch(() => '');
    throw new Error(`Could not open Discord DM (HTTP ${channelRes.status}): ${errText}`);
  }

  const channelJson = await channelRes.json();
  const channelId = channelJson.id;

  // 2. Send message to the DM channel
  const msgRes = await fetch(`https://discord.com/api/v10/channels/${channelId}/messages`, {
    method: 'POST',
    headers: {
      Authorization: `Bot ${config.discordBotToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ content: text }),
  });

  if (!msgRes.ok) {
    throw new Error('Could not send Discord DM (DMs may be disabled for this user).');
  }
}

const { Client, GatewayIntentBits, ActivityType } = require('discord.js');

let client = null;

async function startDiscordBot() {
  if (!config.discordBotToken) {
    console.log('[discord-bot] Token not configured, skipping gateway connection.');
    return null;
  }

  try {
    client = new Client({
      intents: [GatewayIntentBits.Guilds],
    });

    client.once('ready', () => {
      console.log(`[discord-bot] Online as ${client.user.tag}`);
      try {
        client.user.setPresence({
          status: 'online',
          activities: [{ name: 'LastWave Addons', type: ActivityType.Playing }],
        });
      } catch (err) {
        console.warn('[discord-bot] setPresence failed:', err.message);
      }
    });

    client.on('error', (err) => {
      console.error('[discord-bot] Gateway error:', err.message);
    });

    await client.login(config.discordBotToken);
    return client;
  } catch (err) {
    console.error('[discord-bot] Failed to login to Discord Gateway:', err.message);
    return null;
  }
}

function getDiscordClient() {
  return client;
}

module.exports = { startDiscordBot, getDiscordClient, sendDirect };


'use strict';

/**
 * Per-account daily quota: 500 songs/day, keyed by authenticated account
 * (never by IP). Counts successful /stream resolutions. UTC midnight reset.
 */

const config = require('./config');
const store = require('./db');

function dailyLimit() {
  return config.dailySongLimit || 500;
}

function todayKey() {
  return store.utcDay();
}

function secondsUntilUtcMidnight() {
  const now = new Date();
  const midnight = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 0));
  return Math.max(1, Math.ceil((midnight - now) / 1000));
}

function usageFor(userId) {
  const user = typeof userId === 'object' && userId !== null ? userId : store.getUserById(userId);
  const uid = user ? user.id : userId;
  const day = todayKey();
  const usageRow = store.getDailyUsageRow ? store.getDailyUsageRow(uid, day) : { count: store.getDailyCount(uid, day), bonus_plays: 0 };
  const used = usageRow.count;
  const bonus = usageRow.bonus_plays || 0;
  
  const isBypassed = !!(user && (user.bypass_limit === 1 || user.bypass_limit === true));
  const baseLimit = (user && user.custom_limit && user.custom_limit > 0) ? user.custom_limit : dailyLimit();
  // GitHub star bonus: +50% of the account's base limit while starred.
  const hasStarBonus = !!(user && user.star_bonus);
  const starBonus = hasStarBonus ? Math.floor(baseLimit * 0.5) : 0;
  const limit = isBypassed ? 999999 : (baseLimit + bonus + starBonus);
  const remaining = isBypassed ? 999999 : Math.max(0, limit - used);

  return {
    day,
    used,
    limit,
    baseLimit,
    bonusPlays: bonus,
    starBonus,
    hasStarBonus,
    remaining,
    isBypassed,
    isCustom: !!(user && user.custom_limit && user.custom_limit > 0),
  };
}

function isExhausted(userId) {
  const u = usageFor(userId);
  if (u.isBypassed) return false;
  return u.used >= u.limit;
}

const STREAM_COST_NORMAL = 1;
const STREAM_COST_DOLBY = 2;
const DOWNLOAD_COST_NORMAL = 5;
const DOWNLOAD_COST_DOLBY = 10;
const DOWNLOAD_COST = DOWNLOAD_COST_NORMAL;

function getCost({ isDownload = false, isDolby = false }) {
  if (isDownload) {
    return isDolby ? DOWNLOAD_COST_DOLBY : DOWNLOAD_COST_NORMAL;
  }
  return isDolby ? STREAM_COST_DOLBY : STREAM_COST_NORMAL;
}

/** Increment after a successful stream/download resolution. Returns fresh usage. */
function consumeOne({ userId, tokenId, trackId, trackTitle, quality, ip, cost = 1, isDownload = false }) {
  const day = todayKey();
  store.incrementDaily(userId, day, cost);
  const typeTag = isDownload ? `download ${cost}x` : (cost > 1 ? `${cost}x` : '');
  const qualityDisplay = typeTag ? `${quality} [${typeTag}]` : quality;
  store.logStream({ userId, tokenId, trackId, trackTitle, quality: qualityDisplay, ip });
  return usageFor(userId);
}

module.exports = {
  STREAM_COST_NORMAL,
  STREAM_COST_DOLBY,
  DOWNLOAD_COST_NORMAL,
  DOWNLOAD_COST_DOLBY,
  DOWNLOAD_COST,
  getCost,
  dailyLimit,
  todayKey,
  secondsUntilUtcMidnight,
  usageFor,
  isExhausted,
  consumeOne,
};

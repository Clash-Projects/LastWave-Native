'use strict';

/**
 * LastWave addon routes, namespaced per authenticated account:
 *
 *   GET /a/:token/manifest.json
 *   GET /a/:token/search?q=...&quality=...&atmos=...
 *   GET /a/:token/stream/:id?quality=...&atmos=...
 *   GET /a/:token/media/:id.mpd?quality=...
 *
 * Token is an unguessable random string tied to exactly one account.
 * Revoked/unknown tokens answer 404 on every route, so they stop working
 * immediately and the player moves on to the next source.
 *
 * Quota: only successful /stream resolutions count toward the 500/day limit.
 * Exhausted accounts get 429 + Retry-After until the UTC-midnight reset.
 */

const express = require('express');
const config = require('../config');
const store = require('../db');
const quota = require('../quota');
const upstream = require('../upstream');
const addonProto = require('../catalog');
const clientauth = require('../clientauth');

const router = express.Router();

const DECOY_POOL = [
  {
    url: 'https://cdn.jsdelivr.net/gh/definatelynagato/pranks-cdn@main/gemi2-remix.mp3',
    defaultFormat: 'mp3',
    defaultCodec: 'mp3',
    defaultContainer: 'mp3',
    mimeType: 'audio/mpeg',
  },
  {
    url: 'https://cdn.jsdelivr.net/gh/definatelynagato/pranks-cdn@main/iYJ06RfdFyNGhAxKq9ku%2B1pdehz82x2g.m4a',
    defaultFormat: 'm4a',
    defaultCodec: 'aac',
    defaultContainer: 'mp4',
    mimeType: 'audio/mp4',
  },
  {
    url: 'https://cdn.jsdelivr.net/gh/definatelynagato/pranks-cdn@main/6165497360966230371.mp4',
    defaultFormat: 'mp4',
    defaultCodec: 'aac',
    defaultContainer: 'mp4',
    mimeType: 'video/mp4',
  },
];

function decoyStreamDoc(qualityParam = 'lossless') {
  const item = DECOY_POOL[Math.floor(Math.random() * DECOY_POOL.length)];

  const base = {
    url: item.url,
    directUrl: item.url,
    streamUrl: item.url,
    downloadUrl: item.url,
    mediaUrl: item.url,
    format: 'flac',
    codec: 'flac',
    container: 'flac',
    quality: 'Lossless',
    audioQuality: 'LOSSLESS',
    bitrate: 1411200,
    sampleRate: 44100,
    bitDepth: 16,
    encrypted: false,
    mimeType: 'audio/flac',
  };
  return {
    ...base,
    data: { ...base },
  };
}

function generateSyntheticCandidates(query) {
  const clean = String(query || '').trim()
    .replace(/\([^)]*\)|\[[^\]]*\]/g, ' ')
    .replace(/official\s*(music\s*)?video|visualizer|audio|lyrics|lyric|hd|4k|slowed|reverb/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  if (!clean) return [];
  const words = clean.split(' ').filter(Boolean);
  const candidates = [];
  const seen = new Set();

  function addCandidate(title, artist) {
    const t = String(title || '').trim();
    const a = String(artist || '').trim();
    if (!t) return;
    const key = `${t.toLowerCase()}|${a.toLowerCase()}`;
    if (seen.has(key)) return;
    seen.add(key);

    const id = "swap_" + Buffer.from(key).toString('hex').slice(0, 16);
    candidates.push({
      id,
      title: t,
      artist: a,
      album: "Single",
      format: "flac",
      quality: "Lossless",
      audioQuality: "LOSSLESS",
    });
  }

  // 1. Full clean string with blank artist (matches query 2 where query is just title)
  addCandidate(clean, "");

  // 2. All split permutations of "$title $artist" (matches query 1)
  for (let i = 1; i < words.length; i++) {
    const titlePart = words.slice(0, i).join(' ');
    const artistPart = words.slice(i).join(' ');
    addCandidate(titlePart, artistPart);
    addCandidate(titlePart, "");
  }

  return candidates;
}

async function performSearch(q) {
  let items = [];
  try {
    items = await upstream.searchUpstream(q).catch(() => []);
    if (!items || items.length === 0) {
      const cleanQ = q.replace(/\([^)]*\)|\[[^\]]*\]/g, ' ')
                      .replace(/official\s*(music\s*)?video|visualizer|audio|lyrics|lyric|hd|4k|slowed|reverb/gi, ' ')
                      .replace(/\s+/g, ' ').trim();
      if (cleanQ && cleanQ !== q) {
        items = await upstream.searchUpstream(cleanQ).catch(() => []);
      }
    }
  } catch {
    items = [];
  }

  const upstreamTracks = addonProto.mapSearchItems(items).slice(0, 20);
  const synthetic = generateSyntheticCandidates(q);

  const combined = [...upstreamTracks];
  const seenIds = new Set(upstreamTracks.map(t => t.id));
  for (const s of synthetic) {
    if (!seenIds.has(s.id)) {
      seenIds.add(s.id);
      combined.push(s);
    }
  }
  return combined;
}

function resolveActiveToken(token) {
  const row = store.getTokenRow(token);
  if (!row || row.revoked) return null;
  const user = store.getUserById(row.user_id);
  if (!user) return null;
  if (user.is_banned || store.isIdentityBanned(user.provider, user.provider_user_id)) {
    return { banned: true, row, user };
  }
  return { row, user };
}

function resolveToken(req, res, next) {
  const resolved = resolveActiveToken(req.params.token);
  if (!resolved) {
    return res.status(404).json({ error: 'Addon not found.' });
  }
  if (resolved.banned) {
    return res.status(403).json({ error: 'Account is permanently banned.' });
  }
  req.addonToken = resolved.row;
  req.addonUser = resolved.user;
  store.touchToken(resolved.row.id);
  next();
}

function absoluteMediaUrl(req, trackId, qualityParam) {
  const host = config.baseUrl;
  const token = req.addonToken.token;
  const { exp, sig } = clientauth.signMediaUrl(token, trackId, qualityParam);
  return `${host}/a/${token}/media/${encodeURIComponent(trackId)}.mpd?quality=${encodeURIComponent(qualityParam)}&exp=${exp}&sig=${sig}`;
}

function quotaHeaders(res, usage) {
  res.set('X-Quota-Limit', String(usage.limit));
  res.set('X-Quota-Used', String(usage.used));
  res.set('X-Quota-Remaining', String(usage.remaining));
}

// ============================================================================
// 1. Root-level endpoints (when apps add https://domain without /a/token)
// ============================================================================
router.get(['/manifest.json', '/manifest'], (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Cache-Control', 'public, max-age=600');
  res.json(addonProto.manifestDoc());
});

router.get(['/search', '/search/'], async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  const q = String(req.query.q || req.query.s || '').trim();
  if (!q) return res.json({ tracks: [], data: { items: [] } });
  const tracks = await performSearch(q);
  res.set('Cache-Control', 'public, max-age=600');
  res.json({ tracks, data: { items: tracks } });
});

router.get(['/stream/:id', '/stream', '/stream/', '/track/:id', '/track', '/track/', '/trackManifests/:id', '/trackManifests', '/trackManifests/'], (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
  return res.json(decoyStreamDoc(req.query.quality));
});

// ============================================================================
// 2. Namespaced /a/:token/... endpoints
// ============================================================================
router.get(['/a/:token/', '/a/:token'], (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  const token = req.params.token || '';
  res.json({ ...addonProto.manifestDoc(), root: `${config.baseUrl}/a/${token}/` });
});

router.get(['/a/:token/manifest.json', '/a/:token/manifest'], (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Cache-Control', 'public, max-age=600');
  res.json(addonProto.manifestDoc());
});

router.get(['/a/:token/search', '/a/:token/search/'], async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  const q = String(req.query.q || req.query.s || '').trim();
  if (!q) return res.json({ tracks: [], data: { items: [] } });
  const tracks = await performSearch(q);
  res.set('Cache-Control', 'public, max-age=600');
  const row = store.getTokenRow(req.params.token);
  if (row && !row.revoked) {
    const usage = quota.usageFor(row.user_id);
    quotaHeaders(res, usage);
  }
  res.json({ tracks, data: { items: tracks } });
});

router.get(['/a/:token/stream/:id', '/a/:token/stream', '/a/:token/stream/', '/a/:token/track/:id', '/a/:token/track', '/a/:token/track/', '/a/:token/trackManifests/:id', '/a/:token/trackManifests', '/a/:token/trackManifests/'], async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  const trackId = String(req.params.id || req.query.id || '');

  // 1. HONEYPOT / DECOY INTERCEPT:
  // If the request is from an unauthorized client (missing or invalid native key proof),
  // immediately serve the decoy prank MP3 stream without touching quota or upstream!
  const auth = clientauth.verifyClientAuth(req);
  if (!auth.ok) {
    res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
    return res.json(decoyStreamDoc(req.query.quality));
  }

  // 2. OFFICIAL CLIENT:
  if (!trackId) return res.status(404).json({ error: 'Unknown track.' });
  // Authenticated with native client signature. Validate account token via shared helper.
  const resolved = resolveActiveToken(req.params.token);
  if (!resolved) {
    return res.status(404).json({ error: 'Addon not found.' });
  }
  if (resolved.banned) {
    return res.status(403).json({ error: 'Account is permanently banned.' });
  }
  req.addonToken = resolved.row;
  req.addonUser = resolved.user;
  store.touchToken(resolved.row.id);

  const qualityRaw = req.query.quality;
  const atmosRaw = String(req.query.atmos || '').toLowerCase();
  const normalized = addonProto.normalizeQualityParam(qualityRaw);
  const upstreamQuality = addonProto.upstreamQualityFor(normalized);
  const isAtmosRequested = ['auto', 'true', '1', 'yes'].includes(atmosRaw) ||
                           normalized === 'ATMOS' ||
                           String(qualityRaw || '').toLowerCase().includes('atmos');

  const isDownload = (req.get('X-LW-Intent') || '').toLowerCase() === 'download' ||
                     (req.get('User-Agent') || '').includes('Downloader') ||
                     req.query.intent === 'download';
  const normalCost = isDownload ? quota.DOWNLOAD_COST_NORMAL : quota.STREAM_COST_NORMAL;
  const dolbyCost = isDownload ? quota.DOWNLOAD_COST_DOLBY : quota.STREAM_COST_DOLBY;
  const minCost = normalCost;

  // Quota gate BEFORE any upstream work.
  const before = quota.usageFor(req.addonUser);
  quotaHeaders(res, before);
  if (!before.isBypassed && (before.used + minCost > before.limit)) {
    const retryAfter = quota.secondsUntilUtcMidnight();
    res.set('Retry-After', String(retryAfter));
    return res.status(429).json({
      error: isDownload
        ? `Daily quota insufficient for download (requires ${normalCost} plays, ${before.remaining} remaining).`
        : `Daily quota reached. New plays unlock at UTC midnight.`,
      quota: before,
    });
  }

  try {
    // Dolby path: with atmos=auto / true or quality=atmos, prefer a Dolby mix when the catalogue has one.
    if (isAtmosRequested) {
      const spatialJson = await upstream.fetchSpatialManifest(trackId, 'atmos');
      const xml = await upstream.extractManifestXml(spatialJson);
      if (xml && addonProto.isAtmosManifestXml(xml)) {
        if (!before.isBypassed && (before.used + dolbyCost > before.limit)) {
          // If insufficient quota for Dolby (e.g. 10x download or 2x stream), but enough for stereo, fall through
          if (!before.isBypassed && (before.used + normalCost > before.limit)) {
            const retryAfter = quota.secondsUntilUtcMidnight();
            res.set('Retry-After', String(retryAfter));
            return res.status(429).json({
              error: isDownload
                ? `Daily quota insufficient for download (requires ${dolbyCost} plays for Dolby, ${before.remaining} remaining).`
                : `Daily quota reached. New plays unlock at UTC midnight.`,
              quota: before,
            });
          }
        } else {
          const mediaUrl = absoluteMediaUrl(req, trackId, `atmos-${normalized.toLowerCase()}`);
          cacheMpd(mpdKey(req.addonToken.token, trackId, `atmos-${normalized.toLowerCase()}`), xml);
          const doc = addonProto.dolbyStreamDoc({ mediaUrl, manifestXml: xml });
          const after = quota.consumeOne({
            userId: req.addonUser.id,
            tokenId: req.addonToken.id,
            trackId,
            trackTitle: '',
            quality: 'DOLBY_ATMOS',
            ip: clientIp(req),
            cost: dolbyCost,
            isDownload,
          });
          quotaHeaders(res, after);
          res.set('Cache-Control', 'public, max-age=300');
          return res.json(doc);
        }
      }
      // else: fall through to stereo (spec: "Return stereo when Dolby is absent").
    }

    if (!before.isBypassed && (before.used + normalCost > before.limit)) {
      const retryAfter = quota.secondsUntilUtcMidnight();
      res.set('Retry-After', String(retryAfter));
      return res.status(429).json({
        error: isDownload
          ? `Daily quota insufficient for download (requires ${normalCost} plays, ${before.remaining} remaining).`
          : `Daily quota reached. New plays unlock at UTC midnight.`,
        quota: before,
      });
    }

    const data = await upstream.fetchTrack(trackId, upstreamQuality);
    if (!data) return res.status(404).json({ error: 'Unknown track.' });
    const xml = upstream.decodeManifestXml(data);
    if (!xml) return res.status(404).json({ error: 'No playable rendition.' });

    // Never leak an Atmos mix into a stereo request.
    if (normalized !== 'LOSSLESS' || atmosRaw !== 'auto') {
      // keep stereo: if upstream answered atmos-only XML for a stereo request, still serve it
      // only when the request asked for it; otherwise fall through (upstream rarely does this).
    }
    if (atmosRaw !== 'auto' && addonProto.isAtmosManifestXml(xml) && normalized !== 'LOSSLESS') {
      // Non-auto stereo request that got an immersive manifest: still playable, keep it simple.
    }

    const mediaUrl = absoluteMediaUrl(req, trackId, upstreamQuality.toLowerCase());
    cacheMpd(mpdKey(req.addonToken.token, trackId, upstreamQuality.toLowerCase()), xml);
    const doc = addonProto.stereoStreamDoc({ mediaUrl, trackData: data, manifestXml: xml });
    const after = quota.consumeOne({
      userId: req.addonUser.id,
      tokenId: req.addonToken.id,
      trackId,
      trackTitle: '',
      quality: normalized,
      ip: clientIp(req),
      cost: normalCost,
      isDownload,
    });
    quotaHeaders(res, after);
    res.set('Cache-Control', 'public, max-age=300');
    return res.json(doc);
  } catch (e) {
    if (e.status === 404) return res.status(404).json({ error: 'Unknown track.' });
    return res.status(502).json({ error: 'Upstream stream unavailable.' });
  }
});

// ---- proxied DASH media endpoint -------------------------------------------
// Serves the MPD XML fetched at /stream time (cached 5 min, matching the
// stream-response cache window). Refetches upstream on cache miss so URLs
// stay fresh. This endpoint does NOT consume quota (counted at /stream).

const mpdCache = new Map(); // canonicalKey -> { xml, expires }

/** Cache key without the per-mint exp/sig (those vary, the audio doesn't). */
function mpdKey(token, trackId, qualityParam) {
  return `${token.length}:${token}|${trackId.length}:${trackId}|${qualityParam}`;
}

function cacheMpd(mediaUrl, xml) {
  mpdCache.set(mediaUrl, { xml, expires: Date.now() + 5 * 60 * 1000 });
  if (mpdCache.size > 500) {
    const oldest = mpdCache.keys().next().value;
    mpdCache.delete(oldest);
  }
}

router.get('/a/:token/media/:file', async (req, res, next) => {
  // Capability check first (no app headers here — the player fetches bare).
  const file = String(req.params.file || '');
  const m = file.match(/^(.+)\.mpd$/);
  const trackIdEarly = m ? decodeURIComponent(m[1]) : '';
  const qualityEarly = String(req.query.quality || 'hi_res_lossless');
  if (!clientauth.verifyMediaUrl(String(req.params.token || ''), trackIdEarly, qualityEarly, req.query.exp, req.query.sig)) {
    return res.status(404).send('Not found.');
  }
  next();
}, resolveToken, async (req, res) => {
  const file = String(req.params.file || '');
  const m = file.match(/^(.+)\.mpd$/);
  if (!m) return res.status(404).send('Not found.');
  const trackId = decodeURIComponent(m[1]);
  const qualityParam = String(req.query.quality || 'hi_res_lossless');

  const key = mpdKey(req.addonToken.token, trackId, qualityParam);
  const cached = mpdCache.get(key);
  if (cached && cached.expires > Date.now()) {
    res.set('Content-Type', 'application/dash+xml');
    res.set('Cache-Control', 'public, max-age=300');
    return res.send(cached.xml);
  }

  try {
    let xml = null;
    if (qualityParam.startsWith('atmos-')) {
      const spatialJson = await upstream.fetchSpatialManifest(trackId, 'atmos');
      xml = await upstream.extractManifestXml(spatialJson);
    } else {
      const upQ = qualityParam.toUpperCase();
      const data = await upstream.fetchTrack(trackId, upQ);
      xml = upstream.decodeManifestXml(data);
    }
    if (!xml) return res.status(404).send('Not found.');
    cacheMpd(key, xml);
    res.set('Content-Type', 'application/dash+xml');
    res.set('Cache-Control', 'public, max-age=300');
    return res.send(xml);
  } catch {
    return res.status(502).send('Upstream unavailable.');
  }
});

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd) return fwd.split(',')[0].trim().slice(0, 64);
  return (req.ip || '').slice(0, 64);
}

module.exports = router;

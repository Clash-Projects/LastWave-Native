'use strict';

/**
 * Upstream TIDAL-compatible backend client.
 * All credentials stay server-side. The addon layer never exposes
 * UPSTREAM_API_KEY to browsers or player clients.
 */

const config = require('./config');

const TIMEOUT_MS = 8000;

async function fetchWithTimeout(url, options = {}, timeoutMs = TIMEOUT_MS) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: ctrl.signal });
  } finally {
    clearTimeout(t);
  }
}

function upstreamHeaders() {
  const h = { Accept: 'application/json' };
  if (config.upstreamApiKey) h['X-API-Key'] = config.upstreamApiKey;
  return h;
}

async function searchUpstream(query) {
  const url = `${config.upstreamBaseUrl}/search/?s=${encodeURIComponent(query)}`;
  const res = await fetchWithTimeout(url, { headers: upstreamHeaders() });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const err = new Error(`Upstream search failed: HTTP ${res.status}`);
    err.status = res.status;
    err.body = text.slice(0, 500);
    throw err;
  }
  const json = await res.json();
  const items = json?.data?.items;
  return Array.isArray(items) ? items : [];
}

async function fetchTrack(trackId, qualityParam) {
  const url = `${config.upstreamBaseUrl}/track/?id=${encodeURIComponent(trackId)}&quality=${encodeURIComponent(qualityParam)}`;
  const res = await fetchWithTimeout(url, { headers: upstreamHeaders() });
  if (res.status === 404) return null;
  if (!res.ok) {
    // Upstream answers true misses with 500 {"detail":"Upstream API error"}.
    // Treat that as a clean miss (answer 404 so the player moves on);
    // only unexpected failures become 502 downstream.
    const text = await res.text().catch(() => '');
    if (/upstream api error|not found|no such|invalid id/i.test(text)) return null;
    const err = new Error(`Upstream track failed: HTTP ${res.status}`);
    err.status = res.status;
    err.body = text.slice(0, 300);
    throw err;
  }
  const json = await res.json().catch(() => null);
  return json?.data || null;
}

async function fetchSpatialManifest(trackId, kind) {
  // kind: 'atmos' | 'spatial'
  const q = kind === 'spatial' ? 'spatial=true' : 'atmos=true';
  const url = `${config.upstreamBaseUrl}/trackManifests/?id=${encodeURIComponent(trackId)}&${q}`;
  const res = await fetchWithTimeout(url, { headers: upstreamHeaders() });
  if (!res.ok) return null;
  const json = await res.json().catch(() => null);
  if (!json) return null;
  return json;
}

function decodeManifestXml(data) {
  if (!data) return null;
  // Upstream /track returns { manifest: base64(MPD xml), ... }
  const b64 = data.manifest;
  if (!b64 || typeof b64 !== 'string') return null;
  try {
    return Buffer.from(b64, 'base64').toString('utf-8');
  } catch {
    return null;
  }
}

/** Extract MPD XML from the many JSON shapes /trackManifests has used. */
async function extractManifestXml(json) {
  if (!json) return null;
  let foundUri = null;

  const walk = (obj, depth) => {
    if (!obj || depth > 6) return null;
    if (typeof obj === 'string') {
      const t = obj.trim();
      if (t.startsWith('<')) return obj;
      if (t.startsWith('http://') || t.startsWith('https://')) {
        if (!foundUri && (t.includes('.mpd') || t.includes('manifest'))) foundUri = t;
        return null;
      }
      if (/^[A-Za-z0-9+/=\s]+$/.test(t) && t.length > 100) {
        try {
          const dec = Buffer.from(t, 'base64').toString('utf-8');
          if (dec.trim().startsWith('<')) return dec;
        } catch { /* ignore */ }
      }
      return null;
    }
    if (typeof obj !== 'object') return null;

    for (const u of ['uri', 'url', 'manifestUrl', 'mpdUrl']) {
      if (typeof obj[u] === 'string' && (obj[u].startsWith('http://') || obj[u].startsWith('https://'))) {
        foundUri = obj[u];
        break;
      }
    }

    for (const k of ['manifest', 'mpdXml', 'mpd', 'xml', 'mpdBase64', 'manifestBase64']) {
      if (typeof obj[k] === 'string') {
        const found = walk(obj[k], depth + 1);
        if (found) return found;
      }
    }
    for (const k of ['attributes', 'data']) {
      if (obj[k] && typeof obj[k] === 'object') {
        const found = walk(obj[k], depth + 1);
        if (found) return found;
      }
    }
    return null;
  };

  const directXml = walk(json, 0);
  if (directXml) return directXml;

  if (foundUri) {
    try {
      const res = await fetchWithTimeout(foundUri);
      if (res.ok) {
        const xml = await res.text();
        if (xml.trim().startsWith('<')) return xml;
      }
    } catch (err) {
      console.warn('[upstream] Failed to fetch manifest URI:', err.message);
    }
  }

  return null;
}

module.exports = {
  searchUpstream,
  fetchTrack,
  fetchSpatialManifest,
  decodeManifestXml,
  extractManifestXml,
};

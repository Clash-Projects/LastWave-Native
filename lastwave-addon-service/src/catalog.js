'use strict';

/**
 * LastWave addon catalogue mapping (internal).
 *
 * Per-account routes under /a/<token>/...:
 *  - GET /manifest.json -> { id, name, version, resources, ... }
 *  - GET /search?q=...&quality=...&atmos=... -> { tracks: [...] }
 *  - GET /stream/{id}?quality=...&atmos=... -> { url, codec, ... }
 *    or 404 for a true miss (the player moves resolution onward).
 *  - 429 includes Retry-After when the daily quota is exhausted.
 */

const ADDON_ID = 'com.lastwave.addon';
const ADDON_NAME = 'LastWave';
const ADDON_VERSION = '1.0.0';

function manifestDoc() {
  return {
    id: ADDON_ID,
    name: ADDON_NAME,
    version: ADDON_VERSION,
    resources: ['search', 'stream'],
    settings: [
      {
        key: 'quality',
        type: 'select',
        default: 'lossless',
        options: [
          { label: 'Lossless', value: 'lossless' },
          { label: 'Dolby Atmos', value: 'atmos' },
          { label: 'High', value: 'high' },
          { label: 'Low', value: 'low' },
        ],
      },
    ],
  };
}

function artworkForCover(cover) {
  if (!cover) return undefined;
  // TIDAL resource images: https://resources.tidal.com/images/<uuid>/640x640.jpg
  return `https://resources.tidal.com/images/${cover}/640x640.jpg`;
}

function mapSearchItem(item) {
  const id = String(item.id || '');
  const titleBase = item.title || '';
  if (!id || !titleBase) return null;
  const title = item.version ? `${titleBase} (${item.version})` : titleBase;
  const artists = Array.isArray(item.artists) ? item.artists.map((a) => a?.name).filter(Boolean) : [];
  const artist = artists.length > 0 ? artists.join(', ') : item?.artist?.name || '';
  const album = item?.album?.title || '';
  const duration = typeof item.duration === 'number' ? item.duration : parseFloat(item.duration) || 0;
  const modes = Array.isArray(item.audioModes) ? item.audioModes : [];
  const isAtmos = modes.some((m) => String(m).toUpperCase().includes('ATMOS') || String(m).toUpperCase().includes('DOLBY'));
  const track = {
    id,
    title,
    artist,
    album,
    duration,
    format: 'flac',
    quality: 'Lossless',
    audioQuality: item.audioQuality || 'LOSSLESS',
  };
  const art = artworkForCover(item?.album?.cover);
  if (art) track.artworkURL = art;
  if (isAtmos) {
    track.atmos = true;
    track.audioModes = modes;
  } else if (modes.length > 0) {
    track.audioModes = modes;
  }
  return track;
}

function mapSearchItems(items) {
  const out = [];
  const seen = new Set();
  for (const item of items || []) {
    const m = mapSearchItem(item);
    if (!m || seen.has(m.id)) continue;
    seen.add(m.id);
    out.push(m);
  }
  return out;
}

// Quality arrives as lossless/high/low/hi_res/atmos in any casing. Normalize case-insensitively.
function normalizeQualityParam(raw) {
  const q = String(raw || 'lossless').trim().toUpperCase();
  if (q.includes('ATMOS') || q.includes('DOLBY')) return 'ATMOS';
  if (q.includes('HI_RES') || q.includes('HIRES') || q.includes('MAX') || q.includes('UHD')) return 'HI_RES_LOSSLESS';
  if (q === 'LOSSLESS' || q === 'CD' || q === 'FLAC') return 'LOSSLESS';
  if (q === 'HIGH' || q.includes('320') || q === 'SD') return 'HIGH';
  if (q === 'LOW' || q.includes('SAVER') || q.includes('96')) return 'LOW';
  return 'HI_RES_LOSSLESS';
}

function upstreamQualityFor(normalized) {
  if (normalized === 'HIGH') return 'HIGH';
  if (normalized === 'LOW') return 'LOW';
  if (normalized === 'LOSSLESS') return 'LOSSLESS';
  return 'HI_RES_LOSSLESS';
}

function isAtmosManifestXml(xml) {
  if (!xml) return false;
  const l = xml.toLowerCase();
  return l.includes('ec-3') || l.includes('eac3') || l.includes('ec3') || l.includes('atmos') || l.includes('joc');
}

function codecFromManifestXml(xml) {
  if (!xml) return null;
  const m = xml.match(/codecs="([^"]+)"/i);
  return m ? m[1].trim().toLowerCase() : null;
}

function bitrateFromManifestXml(xml) {
  if (!xml) return null;
  const m = xml.match(/bandwidth="(\d+)"/i);
  if (!m) return null;
  const bw = parseInt(m[1], 10); // bits per second
  return Number.isFinite(bw) ? bw : null;
}

/**
 * Build the stream response for a stereo (non-Dolby) rendition.
 * `mediaUrl` is the absolute URL of our proxied MPD endpoint.
 */
function stereoStreamDoc({ mediaUrl, trackData, manifestXml }) {
  const codec = codecFromManifestXml(manifestXml) || 'flac';
  const bitrate = bitrateFromManifestXml(manifestXml) || undefined;
  const sampleRateRaw = trackData?.sampleRate;
  const sampleRate = typeof sampleRateRaw === 'number' ? sampleRateRaw : 44100;
  const bitDepth = typeof trackData?.bitDepth === 'number' ? trackData.bitDepth : 16;
  const dataUrl = manifestXml ? `data:application/dash+xml;base64,${Buffer.from(manifestXml).toString('base64')}` : undefined;
  const doc = {
    url: mediaUrl,
    dataUrl,
    format: 'dash',
    codec,
    container: 'mp4',
    manifest: 'dash',
    encrypted: false,
    sampleRate,
    bitDepth,
  };
  if (manifestXml) doc.manifestXml = manifestXml;
  if (bitrate) doc.bitrate = bitrate;
  const q = String(trackData?.audioQuality || 'LOSSLESS');
  doc.quality = q === 'HI_RES_LOSSLESS' ? 'Hi-Res Lossless' : q.charAt(0) + q.slice(1).toLowerCase();
  return doc;
}

function dolbyStreamDoc({ mediaUrl, manifestXml }) {
  const dataUrl = manifestXml ? `data:application/dash+xml;base64,${Buffer.from(manifestXml).toString('base64')}` : undefined;
  const doc = {
    url: mediaUrl,
    dataUrl,
    format: 'dash',
    quality: 'Dolby Atmos',
    codec: 'eac3-joc',
    container: 'mp4',
    manifest: 'dash',
    sampleRate: 48000,
    audioMode: 'DOLBY_ATMOS',
    encrypted: false,
  };
  if (manifestXml) doc.manifestXml = manifestXml;
  return doc;
}

module.exports = {
  ADDON_ID,
  ADDON_NAME,
  ADDON_VERSION,
  manifestDoc,
  mapSearchItems,
  mapSearchItem,
  normalizeQualityParam,
  upstreamQualityFor,
  isAtmosManifestXml,
  codecFromManifestXml,
  bitrateFromManifestXml,
  stereoStreamDoc,
  dolbyStreamDoc,
};

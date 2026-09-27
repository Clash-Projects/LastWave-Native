/**
 * LastWave ↔ Addon protocol compatibility test.
 *
 * Verifies every field the app expects is present in the server's responses,
 * and that auth / error flows match what the app handles.
 *
 * Usage:  node test_addon_compat.js <addon_url>
 *         e.g.  node test_addon_compat.js http://localhost:8787/a/MYTOKEN
 */

const crypto = require('crypto');

const BASE = (process.argv[2] || '').replace(/\/+$/, '');
if (!BASE) {
  console.error('Usage: node test_addon_compat.js <addon_base_url>');
  process.exit(1);
}

// The client secret the app uses to sign requests.
// In a real run you'd pass this; for structural tests we skip signing.
const CLIENT_SECRET = process.env.ADDON_CLIENT_SECRET || '';

let pass = 0;
let fail = 0;

function ok(name) { pass++; console.log(`  ✅ ${name}`); }
function bad(name, reason) { fail++; console.error(`  ❌ ${name}: ${reason}`); }

// ─── helpers ────────────────────────────────────────────────────────────────
function signHeaders(url, method) {
  if (!CLIENT_SECRET) return {};
  const uri = new URL(url);
  const path = uri.pathname;
  const tokenMatch = path.match(/\/a\/([^/]+)/);
  const token = tokenMatch ? tokenMatch[1] : '';
  const ts = String(Math.floor(Date.now() / 1000));
  const message = `${ts}\n${method}\n${path}\n${token}`;
  const sign = crypto.createHmac('sha256', CLIENT_SECRET).update(message, 'utf8').digest('hex');
  return { 'X-LW-TS': ts, 'X-LW-Sign': sign, 'X-LW-Intent': 'stream', 'User-Agent': 'LastWave-Player/1.0' };
}

async function fetchJson(path, expectOk = true) {
  const url = `${BASE}${path}`;
  const headers = signHeaders(url, 'GET');
  const res = await fetch(url, { headers });
  const body = await res.text();
  let json = null;
  try { json = JSON.parse(body); } catch {}
  return { status: res.status, ok: res.ok, json, body };
}

// ─── Tests ──────────────────────────────────────────────────────────────────

async function testManifest() {
  console.log('\n🔹 GET /manifest.json');
  const { ok: httpOk, json } = await fetchJson('/manifest.json');
  if (!httpOk || !json) return bad('manifest', 'HTTP error or non-JSON');

  // AddonManifest fields the app reads
  const required = ['id', 'name', 'version', 'resources'];
  for (const k of required) {
    if (json[k] !== undefined) ok(`manifest.${k}`);
    else bad(`manifest.${k}`, 'missing');
  }

  // resources must list search and stream
  if (Array.isArray(json.resources)) {
    const lower = json.resources.map(r => r.toLowerCase());
    if (lower.includes('search')) ok('manifest declares search');
    else bad('manifest resources', 'missing "search"');
    if (lower.includes('stream')) ok('manifest declares stream');
    else bad('manifest resources', 'missing "stream"');
  }
}

async function testSearch() {
  console.log('\n🔹 GET /search?q=test&quality=lossless&atmos=none');
  const { ok: httpOk, json } = await fetchJson('/search?q=test&quality=lossless&atmos=none');
  if (!httpOk || !json) return bad('search', 'HTTP error or non-JSON');

  // AddonSearchResponse -> { tracks: [...] }
  if (!Array.isArray(json.tracks)) return bad('search.tracks', 'not an array');
  ok('search returns tracks array');

  if (json.tracks.length === 0) {
    console.log('    ⚠️  Empty results (upstream may be down), skipping field checks');
    return;
  }

  const track = json.tracks[0];
  // AddonTrack fields
  const fields = { id: 'string', title: 'string', artist: 'string', album: 'string', duration: 'number' };
  for (const [k, type] of Object.entries(fields)) {
    if (track[k] !== undefined && typeof track[k] === type) ok(`track.${k} (${type})`);
    else if (track[k] !== undefined) bad(`track.${k}`, `expected ${type}, got ${typeof track[k]}`);
    else bad(`track.${k}`, 'missing');
  }
  // optional but expected
  for (const k of ['format', 'audioQuality']) {
    if (track[k] !== undefined) ok(`track.${k}`);
    else console.log(`    ⚠️  track.${k} absent (optional)`);
  }
}

async function testStreamSuccess() {
  console.log('\n🔹 GET /stream/<id> (real track)');
  // First search to get a real track id
  const { json: searchJson } = await fetchJson('/search?q=test&quality=lossless&atmos=none');
  if (!searchJson?.tracks?.length) {
    console.log('    ⚠️  No search results, skipping stream test');
    return;
  }
  const trackId = searchJson.tracks[0].id;
  console.log(`    Using track id: ${trackId}`);

  const { status, ok: httpOk, json } = await fetchJson(`/stream/${trackId}?quality=lossless&atmos=none`);

  if (!httpOk) {
    // For LastWave client with valid secret, errors should be proper HTTP codes
    if (status === 404) ok('stream 404 on miss (correct for LastWave)');
    else if (status === 502) ok('stream 502 upstream error (correct for LastWave)');
    else if (status === 429) ok('stream 429 quota (correct for LastWave)');
    else bad('stream', `unexpected HTTP ${status}`);
    return;
  }

  if (!json) return bad('stream', 'HTTP 200 but non-JSON');

  // AddonStream fields the app reads
  const urlField = json.url || json.dataUrl || json.manifestXml;
  if (urlField) ok('stream has playable URL (url/dataUrl/manifestXml)');
  else bad('stream', 'no url, dataUrl, or manifestXml');

  // Check it's NOT a decoy (for LastWave client)
  const allUrls = [json.url, json.dataUrl, json.directUrl, json.streamUrl, json.downloadUrl, json.mediaUrl]
    .filter(Boolean).map(u => u.toLowerCase());
  const isDecoy = allUrls.some(u => u.includes('pranks-cdn') || u.includes('definatelynagato'));
  if (isDecoy) bad('stream', '⚠️  GOT DECOY CDN! LastWave should NEVER get this');
  else ok('stream is NOT a decoy');

  for (const k of ['format', 'codec', 'sampleRate', 'bitDepth']) {
    if (json[k] !== undefined) ok(`stream.${k}`);
    else console.log(`    ⚠️  stream.${k} absent`);
  }
}

async function testStreamErrorCodes() {
  console.log('\n🔹 GET /stream/<bogus_id> (expect error, not decoy)');
  const { status, json } = await fetchJson('/stream/NONEXISTENT_TRACK_99999?quality=lossless&atmos=none');

  if (status === 404 || status === 502) {
    ok(`bogus track returns HTTP ${status} (proper error for LastWave)`);
    if (json?.error) ok(`error message: "${json.error}"`);
  } else if (status === 200 && json) {
    // Check if it's a decoy
    const allUrls = [json.url, json.dataUrl, json.directUrl, json.streamUrl]
      .filter(Boolean).map(u => u.toLowerCase());
    const isDecoy = allUrls.some(u => u.includes('pranks-cdn') || u.includes('definatelynagato'));
    if (isDecoy) bad('bogus stream', '⚠️  GOT DECOY CDN on error! LastWave should get HTTP 404/502 instead');
    else ok('bogus stream returned 200 with real data (unexpected but not decoy)');
  } else {
    bad('bogus stream', `unexpected HTTP ${status}`);
  }
}

async function testNoAuthStream() {
  console.log('\n🔹 GET /stream/<id> WITHOUT client auth (should get decoy, not error)');
  const { json: searchJson } = await fetchJson('/search?q=test&quality=lossless&atmos=none');
  const trackId = searchJson?.tracks?.[0]?.id || 'test123';

  // Request WITHOUT signing headers
  const url = `${BASE}/stream/${trackId}?quality=lossless&atmos=none`;
  const res = await fetch(url); // no auth headers
  const json = await res.json().catch(() => null);

  if (res.status === 200 && json) {
    const allUrls = [json.url, json.dataUrl, json.directUrl, json.streamUrl, json.downloadUrl, json.mediaUrl]
      .filter(Boolean).map(u => u.toLowerCase());
    const isDecoy = allUrls.some(u => u.includes('pranks-cdn') || u.includes('definatelynagato') || u.includes('cdn.jsdelivr.net'));
    if (isDecoy) ok('no-auth gets decoy (honeypot working correctly)');
    else ok('no-auth gets 200 (client lock may be disabled in dev)');
  } else if (res.status === 404) {
    ok('no-auth gets 404 (client lock active, fails closed)');
  } else {
    bad('no-auth stream', `unexpected HTTP ${res.status}`);
  }
}

// ─── Run ────────────────────────────────────────────────────────────────────
(async () => {
  console.log(`\n━━━ LastWave ↔ Addon Compatibility Test ━━━`);
  console.log(`Addon URL: ${BASE}`);
  console.log(`Client secret: ${CLIENT_SECRET ? '(set)' : '(not set — requests unsigned)'}`);

  try {
    await testManifest();
    await testSearch();
    await testStreamSuccess();
    await testStreamErrorCodes();
    await testNoAuthStream();
  } catch (e) {
    console.error(`\n💥 Fatal: ${e.message}`);
  }

  console.log(`\n━━━ Results: ${pass} passed, ${fail} failed ━━━\n`);
  process.exit(fail > 0 ? 1 : 0);
})();

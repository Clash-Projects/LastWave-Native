'use strict';
/**
 * Zero-dependency gzip middleware + immutable static cache policy.
 * No new npm packages: uses only Node's built-in zlib.
 *
 * - gzipCompress(): buffers compressible text responses (HTML/CSS/JS/JSON/SVG/XML)
 *   up to 512KB and serves them with Content-Encoding: gzip + Vary.
 *   Skips audio/video/octet-stream, images, large bodies, non-2xx, HEAD.
 * - staticCacheHeaders(): 1-year immutable for version-busted bundles
 *   (style.css, app.js, critical.css — URLs carry ?v=ASSET_VERSION),
 *   1-hour for the rest of /public.
 */

const zlib = require('zlib');

const GZIP_LIMIT = 512 * 1024;
const COMPRESSIBLE = /^(text\/|application\/(javascript|json|.*\+xml)|image\/svg\+xml)/i;

function gzipCompress() {
  return function gzipMiddleware(req, res, next) {
    if (req.method !== 'GET') return next();
    const ae = req.headers['accept-encoding'] || '';
    if (!/\bgzip\b/.test(ae)) return next();

    const origWrite = res.write;
    const origEnd = res.end;
    const chunks = [];
    let size = 0;
    let overLimit = false;

    res.write = function (chunk, encoding, cb) {
      if (chunk && !overLimit) {
        const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding);
        size += buf.length;
        if (size > GZIP_LIMIT) {
          overLimit = true;
          chunks.length = 0;
        } else {
          chunks.push(buf);
        }
      }
      if (overLimit) return origWrite.call(this, chunk, encoding, cb);
      if (typeof cb === 'function') cb();
      return true;
    };

    res.end = function (chunk, encoding, cb) {
      if (typeof chunk === 'function') { cb = chunk; chunk = null; encoding = null; }
      else if (typeof encoding === 'function') { cb = encoding; encoding = null; }
      if (chunk && !overLimit) {
        const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding);
        size += buf.length;
        if (size > GZIP_LIMIT) {
          overLimit = true;
          chunks.length = 0;
        } else {
          chunks.push(buf);
        }
      }
      if (overLimit || res.getHeader('Content-Encoding')) {
        res.write = origWrite;
        res.end = origEnd;
        if (overLimit && chunk) return origEnd.call(this, chunk, encoding, cb);
        return origEnd.call(this, chunk, encoding, cb);
      }
      const type = String(res.getHeader('Content-Type') || '');
      const status = res.statusCode;
      const body = chunks.length ? Buffer.concat(chunks, size) : null;
      res.write = origWrite;
      res.end = origEnd;
      if (body && (status === 200 || status === 304) && COMPRESSIBLE.test(type)) {
        try {
          const gz = zlib.gzipSync(body, { level: 6 });
          res.setHeader('Content-Encoding', 'gzip');
          res.setHeader('Vary', 'Accept-Encoding');
          res.setHeader('Content-Length', String(gz.length));
          return origEnd.call(this, gz, cb);
        } catch (e) {
          return origEnd.call(this, body, cb);
        }
      }
      return origEnd.call(this, body, cb);
    };
    next();
  };
}

const IMMUTABLE_BUNDLES = new Set(['style.css', 'app.js', 'critical.css']);

function staticCacheHeaders(res, filePath) {
  const name = String(filePath || '').split(/[/\\]/).pop();
  if (IMMUTABLE_BUNDLES.has(name)) {
    // Busted via ?v=ASSET_VERSION — safe to cache forever.
    res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
  } else {
    res.setHeader('Cache-Control', 'public, max-age=3600');
  }
}

module.exports = { gzipCompress, staticCacheHeaders };

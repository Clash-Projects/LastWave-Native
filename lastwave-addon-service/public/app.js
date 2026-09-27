'use strict';
(function () {
var toasts = document.getElementById('toasts');
var TOAST_ICONS = {
ok: '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"></polyline></svg>',
err: '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>',
info: '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>'
};
function toast(msg, err) {
if (!toasts) return;
var type = err === 'info' ? 'info' : err ? 'err' : '';
while (toasts.children.length >= 4) toasts.removeChild(toasts.firstChild);
var el = document.createElement('div');
el.className = 'toast' + (type ? ' ' + type : '');
el.setAttribute('role', 'status');
var icon = document.createElement('span');
icon.className = 't-icon';
icon.innerHTML = type === 'err' ? TOAST_ICONS.err : type === 'info' ? TOAST_ICONS.info : TOAST_ICONS.ok;
var label = document.createElement('span');
label.textContent = msg;
el.appendChild(icon);
el.appendChild(label);
toasts.appendChild(el);
setTimeout(function () {
el.classList.add('out');
setTimeout(function () { el.remove(); }, 240);
}, 2600);
}
(function nav() {
var path = location.pathname;
document.querySelectorAll('[data-nav]').forEach(function (a) {
var k = a.getAttribute('data-nav');
var on =
(k === 'home' && path === '/') ||
(k === 'account' && (path === '/dashboard' || path === '/login' || path === '/signup' || path.indexOf('/verify') === 0)) ||
(k === 'admin' && path.indexOf('/admin') === 0);
if (on) a.setAttribute('aria-current', 'page');
});
})();
var checkSvg = '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"></polyline></svg>';
document.addEventListener('click', function (e) {
var btn = e.target.closest('[data-copy]');
if (!btn) return;
try {
var rect = btn.getBoundingClientRect();
var rip = document.createElement('span');
rip.className = 'ripple';
var size = Math.max(rect.width, rect.height);
rip.style.width = rip.style.height = size + 'px';
var x = (e.clientX || rect.left + rect.width / 2) - rect.left - size / 2;
var y = (e.clientY || rect.top + rect.height / 2) - rect.top - size / 2;
rip.style.left = x + 'px';
rip.style.top = y + 'px';
btn.appendChild(rip);
setTimeout(function () { rip.remove(); }, 600);
} catch (err) {}
var target = document.querySelector(btn.getAttribute('data-copy'));
if (!target) return;
var text = target.textContent.trim();
function done() {
if (btn.hasAttribute('data-copy-busy')) return;
btn.setAttribute('data-copy-busy', '1');
var origHtml = btn.getAttribute('data-orig-html') || btn.innerHTML;
btn.setAttribute('data-orig-html', origHtml);
btn.classList.add('copy-ok', 'is-success');
btn.innerHTML = checkSvg + '<span>Copied</span>';
toast('Copied to clipboard');
setTimeout(function () {
btn.innerHTML = origHtml;
btn.classList.remove('copy-ok', 'is-success');
btn.removeAttribute('data-copy-busy');
}, 1600);
}
if (navigator.clipboard && navigator.clipboard.writeText) {
navigator.clipboard.writeText(text).then(done, function () { fallback(); });
} else fallback();
function fallback() {
var r = document.createRange();
r.selectNodeContents(target);
var s = getSelection();
s.removeAllRanges(); s.addRange(r);
try { document.execCommand('copy'); done(); } catch (err) { toast('Copy failed — please copy manually', true); }
s.removeAllRanges();
}
});
document.addEventListener('click', function (e) {
var btn = e.target.closest('[data-download]');
if (!btn) return;
var src = document.querySelector(btn.getAttribute('data-download-from'));
if (!src) return;
var blob = new Blob([src.textContent.trim() + '\n'], { type: 'text/plain' });
var a = document.createElement('a');
a.href = URL.createObjectURL(blob);
a.download = btn.getAttribute('data-download') || 'download.txt';
document.body.appendChild(a);
a.click();
setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 500);
toast('Backup downloaded — store it somewhere safe');
});
document.querySelectorAll('[data-saved]').forEach(function (box) {
var key = box.getAttribute('data-saved');
var pill = document.querySelector('[data-saved-pill]');
try { box.checked = localStorage.getItem(key) === '1'; } catch (err) {}
function paint() {
if (pill) pill.hidden = !box.checked;
if (box.closest('.savecheck')) box.closest('.savecheck').classList.toggle('is-saved', box.checked);
}
paint();
box.addEventListener('change', function () {
try {
if (box.checked) localStorage.setItem(key, '1');
else localStorage.removeItem(key);
} catch (err) {}
paint();
if (box.checked) toast('URL marked as securely backed up');
});
});
var backdrop = document.getElementById('modal-backdrop');
var mTitle = document.getElementById('modal-title');
var mDesc = document.getElementById('modal-desc');
var mCancel = document.getElementById('modal-cancel');
var mConfirm = document.getElementById('modal-confirm');
var pendingForm = null;
var lastFocus = null;
function focusables(root) {
return Array.prototype.slice.call(root.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')).filter(function (el) {
return !el.disabled && el.offsetParent !== null;
});
}
function trapTab(e) {
if (e.key === 'Escape') { closeModal(); return; }
if (e.key !== 'Tab' || !backdrop || backdrop.hidden) return;
var f = focusables(backdrop);
if (!f.length) return;
var first = f[0], last = f[f.length - 1];
if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
}
function onKey(e) { trapTab(e); }
function closeModal() {
if (backdrop) backdrop.hidden = true;
pendingForm = null;
document.removeEventListener('keydown', onKey);
if (lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (e) {} lastFocus = null; }
}
function openModal(title, desc) {
lastFocus = document.activeElement;
if (mTitle) mTitle.textContent = title || 'Are you sure?';
if (mDesc) mDesc.textContent = desc || 'This cannot be undone.';
if (backdrop) {
backdrop.hidden = false;
document.addEventListener('keydown', onKey);
var f = focusables(backdrop);
if (mConfirm && f.indexOf(mConfirm) !== -1) mConfirm.focus();
else if (f.length) f[0].focus();
}
}
if (mCancel) mCancel.addEventListener('click', closeModal);
if (backdrop) backdrop.addEventListener('click', function (e) { if (e.target === backdrop) closeModal(); });
if (mConfirm) mConfirm.addEventListener('click', function () {
var f = pendingForm;
closeModal();
if (f) {
f.setAttribute('data-confirmed', '1');
if (typeof f.requestSubmit === 'function') f.requestSubmit();
else f.submit();
}
});
document.addEventListener('submit', function (e) {
var form = e.target;
if (!(form instanceof HTMLFormElement)) return;
if (form.hasAttribute('data-confirm') && !form.hasAttribute('data-confirmed')) {
e.preventDefault();
pendingForm = form;
openModal(form.getAttribute('data-confirm'), form.getAttribute('data-confirm-desc'));
return;
}
var btn = form.querySelector('button[type="submit"].btn');
if (btn && !btn.disabled) {
btn.disabled = true;
btn.classList.add('loading');
var sp = document.createElement('span');
sp.className = 'spin'; sp.setAttribute('aria-hidden', 'true');
btn.prepend(sp);
}
});
(function poll() {
var box = document.querySelector('[data-poll]');
if (!box) return;
var provider = box.getAttribute('data-provider');
var code = box.getAttribute('data-code');
var pill = box.querySelector('[data-status-pill]');
var timer = setInterval(async function () {
try {
var r = await fetch('/verify/' + provider + '/status?code=' + encodeURIComponent(code));
var j = await r.json();
if (pill && j.status) {
pill.className = 'pill pill-' + j.status;
var dot = pill.querySelector('.pill-dot');
if (dot) dot.classList.add('live');
var label = pill.querySelector('[data-status-text]');
if (label) label.textContent = j.status === 'verified' ? 'Approved — confirm below' : j.status;
else pill.textContent = j.status;
}
if (j.status === 'verified') {
clearInterval(timer);
toast('Bot approved your code — ready to confirm!');
}
} catch (e) {  }
}, 4000);
})();
(function filter() {
var input = document.querySelector('[data-filter-table]');
if (!input) return;
var sel = input.getAttribute('data-filter-table');
var empty = document.querySelector('[data-filter-empty]');
input.addEventListener('input', function () {
var q = input.value.trim().toLowerCase();
var shown = 0;
document.querySelectorAll(sel).forEach(function (row) {
var hit = !q || (row.getAttribute('data-search') || '').toLowerCase().indexOf(q) !== -1;
row.style.display = hit ? '' : 'none';
if (hit) shown++;
});
if (empty) empty.style.display = shown ? 'none' : '';
});
})();
(function quota() {
var chips = document.querySelectorAll('[data-countdown-utc-midnight]');
function tick() {
var now = new Date();
var mid = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 0));
var s = Math.max(0, Math.floor((mid - now) / 1000));
var h = String(Math.floor(s / 3600)).padStart(2, '0');
var m = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
var ss = String(s % 60).padStart(2, '0');
chips.forEach(function (c) { c.textContent = h + ':' + m + ':' + ss; c.setAttribute('aria-label', 'Resets in ' + h + ' hours ' + m + ' minutes'); });
}
if (chips.length) { tick(); setInterval(tick, 1000); }
function animateBars() {
document.querySelectorAll('[data-quota-bar]').forEach(function (bar) {
var target = bar.getAttribute('data-quota-bar') || '0';
bar.style.width = '0%';
requestAnimationFrame(function () {
requestAnimationFrame(function () { bar.style.width = target + '%'; });
});
});
document.querySelectorAll('[data-quota-ring]').forEach(function (ring) {
try {
var svg = ring.closest('svg') || ring;
var circle = ring.classList && ring.classList.contains('ring-fg') ? ring : svg.querySelector('.ring-fg');
if (!circle) return;
var dash = parseFloat(circle.getAttribute('stroke-dasharray')) || 326;
var finalOff = parseFloat(circle.getAttribute('stroke-dashoffset')) || 0;
circle.style.transition = 'none';
circle.setAttribute('stroke-dashoffset', dash);
requestAnimationFrame(function () {
requestAnimationFrame(function () {
circle.style.transition = '';
circle.setAttribute('stroke-dashoffset', finalOff);
});
});
} catch (e) {}
});
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', animateBars);
else animateBars();
var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
document.querySelectorAll('[data-count]').forEach(function (el) {
var end = parseInt(el.getAttribute('data-count'), 10);
if (isNaN(end) || reduced) { el.textContent = isNaN(end) ? el.textContent : end; return; }
var t0 = null, dur = 900;
function step(t) {
if (!t0) t0 = t;
var p = Math.min(1, (t - t0) / dur);
var eased = 1 - Math.pow(1 - p, 3);
el.textContent = Math.round(end * eased);
if (p < 1) requestAnimationFrame(step);
}
requestAnimationFrame(step);
});
})();
(function otp() {
document.querySelectorAll('[data-otp]').forEach(function (wrap) {
var hidden = wrap.querySelector('[data-otp-hidden]');
var boxesEl = wrap.querySelector('.otp-boxes');
if (!hidden || !boxesEl) return;
var len = parseInt(wrap.getAttribute('data-otp-length') || '6', 10) || 6;
var boxes = [];
boxesEl.innerHTML = '';
for (var i = 0; i < len; i++) {
(function (idx) {
var b = document.createElement('input');
b.type = 'text';
b.setAttribute('inputmode', 'text');
b.setAttribute('autocomplete', idx === 0 ? 'one-time-code' : 'off');
b.setAttribute('autocapitalize', 'characters');
b.setAttribute('spellcheck', 'false');
b.setAttribute('maxlength', '1');
b.setAttribute('aria-label', 'Digit ' + (idx + 1) + ' of ' + len);
b.className = 'otp-cell';
if (idx === 0) b.setAttribute('data-otp-first', '1');
boxesEl.appendChild(b);
boxes.push(b);
b.addEventListener('input', function () {
b.value = (b.value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(-1);
b.classList.toggle('filled', !!b.value);
sync();
if (b.value && idx < len - 1) boxes[idx + 1].focus();
});
b.addEventListener('keydown', function (e) {
if (e.key === 'Backspace' && !b.value && idx > 0) {
e.preventDefault();
boxes[idx - 1].focus();
boxes[idx - 1].value = '';
boxes[idx - 1].classList.remove('filled');
sync();
}
if (e.key === 'ArrowLeft' && idx > 0) boxes[idx - 1].focus();
if (e.key === 'ArrowRight' && idx < len - 1) boxes[idx + 1].focus();
});
b.addEventListener('paste', function (e) {
e.preventDefault();
var text = ((e.clipboardData || window.clipboardData || {}).getData('text') || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, len);
if (!text) return;
for (var k = 0; k < len; k++) {
boxes[k].value = text[k] || '';
boxes[k].classList.toggle('filled', !!boxes[k].value);
}
sync();
var next = Math.min(text.length, len - 1);
boxes[next].focus();
});
})(i);
}
function sync() {
var v = boxes.map(function (b) { return b.value; }).join('');
if (document.activeElement && boxes.indexOf(document.activeElement) === -1) {
}
hidden.value = v || hidden.value.slice(0, 0) === '' && v === '' ? v : v || hidden.value;
if (v) hidden.value = v;
}
var seed = (hidden.value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, len);
for (var s = 0; s < len; s++) {
if (seed[s]) { boxes[s].value = seed[s]; boxes[s].classList.add('filled'); }
}
hidden.addEventListener('input', function () {
var v = (hidden.value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, len);
for (var k = 0; k < len; k++) {
boxes[k].value = v[k] || '';
boxes[k].classList.toggle('filled', !!boxes[k].value);
}
});
hidden.setAttribute('aria-hidden', 'true');
hidden.setAttribute('tabindex', '-1');
hidden.style.position = 'absolute';
hidden.style.opacity = '0';
hidden.style.height = '1px';
hidden.style.pointerEvents = 'none';
var form = wrap.closest('form');
if (form) form.addEventListener('submit', function () {
var v = boxes.map(function (b) { return b.value; }).join('');
if (v) hidden.value = v;
});
});
})();
(function qr() {
var backdrop = document.getElementById('qr-backdrop');
if (!backdrop) return;
var img = document.getElementById('qr-img');
var urlEl = document.getElementById('qr-url');
var closeBtn = document.getElementById('qr-close');
var copyBtn = document.getElementById('qr-copy');
var lastFocus = null;
var currentUrl = '';
function onKey(e) {
if (e.key === 'Escape') close();
if (e.key === 'Tab') {
var f = Array.prototype.slice.call(backdrop.querySelectorAll('button')).filter(function (b) { return !b.disabled; });
if (!f.length) return;
var first = f[0], last = f[f.length - 1];
if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
}
}
function open(url) {
currentUrl = url;
lastFocus = document.activeElement;
if (urlEl) urlEl.textContent = url;
if (img) img.src = 'https://api.qrserver.com/v1/create-qr-code/?size=224x224&margin=4&data=' + encodeURIComponent(url);
backdrop.hidden = false;
document.addEventListener('keydown', onKey);
if (closeBtn) closeBtn.focus();
}
function close() {
backdrop.hidden = true;
document.removeEventListener('keydown', onKey);
if (lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (e) {} }
}
document.addEventListener('click', function (e) {
var btn = e.target.closest('[data-qr]');
if (btn) {
var sel = btn.getAttribute('data-qr');
var t = sel && document.querySelector(sel);
var url = t ? t.textContent.trim() : '';
if (!url) { toast('Nothing to encode yet', true); return; }
open(url);
return;
}
if (e.target === backdrop) close();
});
if (closeBtn) closeBtn.addEventListener('click', close);
if (copyBtn) copyBtn.addEventListener('click', function () {
if (!currentUrl) return;
function ok() { toast('Addon link copied — paste it in LastWave'); }
if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(currentUrl).then(ok, function () { toast('Copy failed — long-press the link', true); });
else toast('Copy failed — long-press the link', true);
});
})();
(function celestial() {
return;
})();
(function tilt() {
var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
if (reduced) return;
var hoverQ = window.matchMedia ? window.matchMedia('(hover: hover) and (pointer: fine)') : null;
if (hoverQ && !hoverQ.matches) return;
var cards = document.querySelectorAll('.tilt');
if (!cards.length) return;
var liveRects = [];
var invalidateQueued = false;
function invalidate() {
if (invalidateQueued) return;
invalidateQueued = true;
requestAnimationFrame(function () {
invalidateQueued = false;
for (var i = 0; i < liveRects.length; i++) liveRects[i].rect = null;
});
}
window.addEventListener('scroll', invalidate, { passive: true, capture: true });
window.addEventListener('resize', invalidate, { passive: true });
Array.prototype.forEach.call(cards, function (card) {
var raf = 0, rx = 0, ry = 0;
var box = { rect: null };
function apply() {
raf = 0;
card.style.transform = 'perspective(900px) rotateX(' + rx.toFixed(2) + 'deg) rotateY(' + ry.toFixed(2) + 'deg)';
}
function measure() {
if (!box.rect) box.rect = card.getBoundingClientRect();
return box.rect;
}
card.addEventListener('pointerenter', function (e) {
if (window.innerWidth <= 1024) return;
box.rect = card.getBoundingClientRect();
liveRects.push(box);
card.classList.add('is-lit');
setSpot(e, box.rect);
});
card.addEventListener('pointermove', function (e) {
if (window.innerWidth <= 1024 || e.pointerType === 'touch') return;
var r = measure();
var nx = (e.clientX - r.left) / r.width - 0.5;
var ny = (e.clientY - r.top) / r.height - 0.5;
ry = Math.max(-4, Math.min(4, nx * 8));
rx = Math.max(-4, Math.min(4, ny * -8));
setSpot(e, r);
if (!raf) raf = requestAnimationFrame(apply);
});
card.addEventListener('pointerleave', function () {
card.classList.remove('is-lit');
var at = liveRects.indexOf(box);
if (at !== -1) liveRects.splice(at, 1);
box.rect = null;
if (raf) cancelAnimationFrame(raf);
raf = 0;
rx = 0; ry = 0;
card.style.transform = '';
});
function setSpot(e, r) {
card.style.setProperty('--mouse-x', (e.clientX - r.left) + 'px');
card.style.setProperty('--mouse-y', (e.clientY - r.top) + 'px');
}
});
})();
(function fx() {
var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
var SPARK_COLORS = ['#ffffff', '#e4e4e7', '#a1a1aa', '#71717a'];
function burst(btn) {
if (reduced) return;
try {
var r = btn.getBoundingClientRect();
var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
for (var i = 0; i < 12; i++) {
(function (k) {
var s = document.createElement('span');
var star = k % 2 === 0;
s.className = 'spark' + (star ? ' star' : '');
var col = SPARK_COLORS[k % SPARK_COLORS.length];
s.style.background = col;
s.style.color = col;
s.style.left = cx + 'px';
s.style.top = cy + 'px';
document.body.appendChild(s);
var ang = (Math.PI * 2 * k) / 12 + Math.random() * 0.5;
var dist = 26 + Math.random() * 42;
var dx = Math.cos(ang) * dist, dy = Math.sin(ang) * dist;
var anim = s.animate([
{ transform: 'translate(-50%,-50%) scale(1)', opacity: 1 },
{ transform: 'translate(calc(-50% + ' + dx.toFixed(1) + 'px), calc(-50% + ' + dy.toFixed(1) + 'px)) scale(0)', opacity: 0 }
], { duration: 520 + Math.random() * 300, easing: 'cubic-bezier(0.16,1,0.3,1)' });
anim.onfinish = function () { s.remove(); };
})(i);
}
} catch (e) {}
try { if (navigator.vibrate) navigator.vibrate(10); } catch (e) {}
if (soundOn()) blip();
}
document.addEventListener('click', function (e) {
var btn = e.target.closest ? e.target.closest('[data-copy]') : null;
if (btn) burst(btn);
});
var actx = null;
function soundOn() {
try { return localStorage.getItem('lw-fx-sound') === '1'; } catch (e) { return false; }
}
function blip() {
try {
var AC = window.AudioContext || window.webkitAudioContext;
if (!AC) return;
if (!actx) actx = new AC();
if (actx.state === 'suspended') actx.resume();
var o = actx.createOscillator(), g = actx.createGain();
o.type = 'sine';
o.frequency.setValueAtTime(620, actx.currentTime);
o.frequency.exponentialRampToValueAtTime(920, actx.currentTime + 0.07);
g.gain.setValueAtTime(0.045, actx.currentTime);
g.gain.exponentialRampToValueAtTime(0.0001, actx.currentTime + 0.09);
o.connect(g); g.connect(actx.destination);
o.start(); o.stop(actx.currentTime + 0.1);
} catch (e) {}
}
function mountSoundToggle() {
var meta = document.querySelector('.footer-meta');
if (!meta || document.getElementById('fx-sound')) return;
var b = document.createElement('button');
b.type = 'button';
b.id = 'fx-sound';
b.className = 'fx-sound';
function paint() {
var on = soundOn();
b.setAttribute('aria-pressed', on ? 'true' : 'false');
b.textContent = on ? 'Sound on' : 'Sound off';
}
b.addEventListener('click', function () {
try { localStorage.setItem('lw-fx-sound', soundOn() ? '0' : '1'); } catch (e) {}
paint();
if (soundOn()) blip();
});
paint();
meta.appendChild(b);
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mountSoundToggle);
else mountSoundToggle();
var box = document.getElementById('toasts');
if (box && window.MutationObserver) {
var obs = new MutationObserver(function (muts) {
muts.forEach(function (m) {
Array.prototype.forEach.call(m.addedNodes, function (n) {
if (n.nodeType === 1 && n.classList.contains('toast') && !n.querySelector('.t-progress')) {
var p = document.createElement('span');
p.className = 't-progress';
p.setAttribute('aria-hidden', 'true');
p.appendChild(document.createElement('i'));
n.appendChild(p);
}
});
});
});
obs.observe(box, { childList: true });
}
var sheetQ = window.matchMedia ? window.matchMedia('(max-width: 480px)') : { matches: false };
var modal = document.querySelector('#modal-backdrop .modal');
var backdropEl = document.getElementById('modal-backdrop');
if (modal && backdropEl) {
var startY = -1, dy = 0, tracking = false;
modal.addEventListener('touchstart', function (e) {
if (!sheetQ.matches || backdropEl.hidden || !e.touches.length) return;
var r = modal.getBoundingClientRect();
if (e.touches[0].clientY - r.top > 48) return;
tracking = true;
startY = e.touches[0].clientY;
dy = 0;
modal.classList.add('dragging');
}, { passive: true });
modal.addEventListener('touchmove', function (e) {
if (!tracking || !e.touches.length) return;
dy = Math.max(0, e.touches[0].clientY - startY);
modal.style.transform = 'translateY(' + dy + 'px)';
}, { passive: true });
modal.addEventListener('touchend', function () {
if (!tracking) return;
tracking = false;
modal.classList.remove('dragging');
if (dy > 90 && typeof closeModal === 'function') {
modal.style.transform = '';
closeModal();
} else {
modal.style.transform = '';
}
dy = 0;
});
}
})();
})();

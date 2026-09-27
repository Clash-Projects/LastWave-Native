/* 11-qr — QR pairing modal: [data-qr="#selector"] opens #qr-backdrop with live QR.
   Esc closes, backdrop click closes, Copy button copies the URL. Focus trapped. */
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

/* 03-copy — 1-tap copy with ripple, Copy -> Checkmark swap, emerald glow + toast. */
var checkSvg = '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"></polyline></svg>';

document.addEventListener('click', function (e) {
  var btn = e.target.closest('[data-copy]');
  if (!btn) return;
  /* Ripple at tap point (GPU transform only) */
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

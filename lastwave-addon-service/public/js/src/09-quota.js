/* 09-quota — UTC-midnight countdown, animated ring/bar entrance, stat counters.
   Contracts: [data-countdown-utc-midnight], [data-quota-ring], [data-quota-bar], [data-count]. */
(function quota() {
  /* Countdown to UTC midnight */
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

  /* Animate bars + rings from 0 on first paint (GPU-friendly) */
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

  /* Animated stat counters (admin) — ease-out count up, respects reduced motion */
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

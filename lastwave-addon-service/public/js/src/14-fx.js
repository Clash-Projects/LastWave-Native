/* 14-fx — Copy star-burst + haptic, optional UI blip (muted by default),
   toast auto-progress bars, mobile sheet swipe-to-dismiss.
   Wraps existing DOM contracts; touches no endpoints or form targets. */
(function fx() {
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var SPARK_COLORS = ['#ffffff', '#e4e4e7', '#a1a1aa', '#71717a'];

  /* ---- Star-burst on copy buttons ---- */
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

  /* ---- Optional UI blip, muted by default (localStorage lw-fx-sound=1) ---- */
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
  /* Toggle injected into footer (no layout/contract impact) */
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

  /* ---- Toast auto-progress bars (MutationObserver, no toast() edits) ---- */
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

  /* ---- Mobile bottom-sheet swipe-to-dismiss (grip zone = top 48px) ---- */
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

/* 13-tilt — 3D card tilt (±4°) + cursor spotlight for .tilt cards.
   Desktop only (hover-capable, fine pointer, >1024px), rAF-throttled,
   GPU transforms only. Geometry is READ once per hover (cached rect,
   invalidated on scroll/resize via shared rAF) — never read after write
   in the same tick, so zero forced reflows. Respects reduced motion. */
(function tilt() {
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduced) return;
  var hoverQ = window.matchMedia ? window.matchMedia('(hover: hover) and (pointer: fine)') : null;
  if (hoverQ && !hoverQ.matches) return;
  var cards = document.querySelectorAll('.tilt');
  if (!cards.length) return;

  /* Shared geometry invalidator: scroll/resize coalesced into one rAF. */
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
      box.rect = card.getBoundingClientRect(); /* single read before any write */
      liveRects.push(box);
      card.classList.add('is-lit');
      setSpot(e, box.rect);
    });
    card.addEventListener('pointermove', function (e) {
      if (window.innerWidth <= 1024 || e.pointerType === 'touch') return;
      var r = measure(); /* cached — no layout work after first read */
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

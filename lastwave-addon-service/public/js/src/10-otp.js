/* 10-otp — 6-box verification input: auto-advance, backspace nav, paste split, mobile numeric.
   Contracts: [data-otp] wrapper, [data-otp-hidden] real input (name=code preserved), .otp-boxes container. */
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
        /* keep hidden authoritative only when user typed in real field */
      }
      hidden.value = v || hidden.value.slice(0, 0) === '' && v === '' ? v : v || hidden.value;
      /* Always mirror boxes -> hidden when boxes have content */
      if (v) hidden.value = v;
    }
    /* Seed boxes from server-rendered value */
    var seed = (hidden.value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, len);
    for (var s = 0; s < len; s++) {
      if (seed[s]) { boxes[s].value = seed[s]; boxes[s].classList.add('filled'); }
    }
    /* If user edits hidden directly, mirror into boxes */
    hidden.addEventListener('input', function () {
      var v = (hidden.value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, len);
      for (var k = 0; k < len; k++) {
        boxes[k].value = v[k] || '';
        boxes[k].classList.toggle('filled', !!boxes[k].value);
      }
    });
    /* Hide the raw field visually but keep it focusable for AT; boxes are primary */
    hidden.setAttribute('aria-hidden', 'true');
    hidden.setAttribute('tabindex', '-1');
    hidden.style.position = 'absolute';
    hidden.style.opacity = '0';
    hidden.style.height = '1px';
    hidden.style.pointerEvents = 'none';
    /* Submit guard: if boxes filled but hidden empty, sync */
    var form = wrap.closest('form');
    if (form) form.addEventListener('submit', function () {
      var v = boxes.map(function (b) { return b.value; }).join('');
      if (v) hidden.value = v;
    });
  });
})();

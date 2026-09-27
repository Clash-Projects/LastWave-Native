/* 07-poll — live bot verification status (4s polling, silent errors, emerald pill). */
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
    } catch (e) { /* keep polling */ }
  }, 4000);
})();

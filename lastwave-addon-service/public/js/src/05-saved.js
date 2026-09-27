/* 05-saved — "I've saved it" checklist persistence. */
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

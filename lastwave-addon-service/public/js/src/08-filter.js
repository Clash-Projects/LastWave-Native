/* 08-filter — admin table search filter. */
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

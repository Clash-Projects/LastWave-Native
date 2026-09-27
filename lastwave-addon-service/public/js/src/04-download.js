/* 04-download — backup-file download buttons. */
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

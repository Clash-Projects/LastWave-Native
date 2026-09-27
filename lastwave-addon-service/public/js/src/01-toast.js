/* 01-toast — universal high-performance toasts (top-right desktop, top-center mobile).
   Types: default success (emerald glow), err (red), info (indigo). Max 4 stacked. */
var TOAST_ICONS = {
  ok: '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"></polyline></svg>',
  err: '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>',
  info: '<svg class="v-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>'
};
function toast(msg, err) {
  if (!toasts) return;
  var type = err === 'info' ? 'info' : err ? 'err' : '';
  while (toasts.children.length >= 4) toasts.removeChild(toasts.firstChild);
  var el = document.createElement('div');
  el.className = 'toast' + (type ? ' ' + type : '');
  el.setAttribute('role', 'status');
  var icon = document.createElement('span');
  icon.className = 't-icon';
  icon.innerHTML = type === 'err' ? TOAST_ICONS.err : type === 'info' ? TOAST_ICONS.info : TOAST_ICONS.ok;
  var label = document.createElement('span');
  label.textContent = msg;
  el.appendChild(icon);
  el.appendChild(label);
  toasts.appendChild(el);
  setTimeout(function () {
    el.classList.add('out');
    setTimeout(function () { el.remove(); }, 240);
  }, 2600);
}

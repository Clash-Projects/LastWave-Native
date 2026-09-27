/* 06-modal — accessible confirm dialogs: Esc closes, focus trap, return focus, spinner. */
var backdrop = document.getElementById('modal-backdrop');
var mTitle = document.getElementById('modal-title');
var mDesc = document.getElementById('modal-desc');
var mCancel = document.getElementById('modal-cancel');
var mConfirm = document.getElementById('modal-confirm');
var pendingForm = null;
var lastFocus = null;

function focusables(root) {
  return Array.prototype.slice.call(root.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')).filter(function (el) {
    return !el.disabled && el.offsetParent !== null;
  });
}
function trapTab(e) {
  if (e.key === 'Escape') { closeModal(); return; }
  if (e.key !== 'Tab' || !backdrop || backdrop.hidden) return;
  var f = focusables(backdrop);
  if (!f.length) return;
  var first = f[0], last = f[f.length - 1];
  if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
}
function onKey(e) { trapTab(e); }
function closeModal() {
  if (backdrop) backdrop.hidden = true;
  pendingForm = null;
  document.removeEventListener('keydown', onKey);
  if (lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (e) {} lastFocus = null; }
}
function openModal(title, desc) {
  lastFocus = document.activeElement;
  if (mTitle) mTitle.textContent = title || 'Are you sure?';
  if (mDesc) mDesc.textContent = desc || 'This cannot be undone.';
  if (backdrop) {
    backdrop.hidden = false;
    document.addEventListener('keydown', onKey);
    var f = focusables(backdrop);
    if (mConfirm && f.indexOf(mConfirm) !== -1) mConfirm.focus();
    else if (f.length) f[0].focus();
  }
}
if (mCancel) mCancel.addEventListener('click', closeModal);
if (backdrop) backdrop.addEventListener('click', function (e) { if (e.target === backdrop) closeModal(); });
if (mConfirm) mConfirm.addEventListener('click', function () {
  var f = pendingForm;
  closeModal();
  if (f) {
    f.setAttribute('data-confirmed', '1');
    if (typeof f.requestSubmit === 'function') f.requestSubmit();
    else f.submit();
  }
});
document.addEventListener('submit', function (e) {
  var form = e.target;
  if (!(form instanceof HTMLFormElement)) return;
  if (form.hasAttribute('data-confirm') && !form.hasAttribute('data-confirmed')) {
    e.preventDefault();
    pendingForm = form;
    openModal(form.getAttribute('data-confirm'), form.getAttribute('data-confirm-desc'));
    return;
  }
  var btn = form.querySelector('button[type="submit"].btn');
  if (btn && !btn.disabled) {
    btn.disabled = true;
    btn.classList.add('loading');
    var sp = document.createElement('span');
    sp.className = 'spin'; sp.setAttribute('aria-hidden', 'true');
    btn.prepend(sp);
  }
});

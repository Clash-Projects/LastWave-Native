'use strict';
/** Admin login view (direct URL only, never linked publicly). */
const { ICON } = require('../ui/icons');

function renderAdminLogin() {
  return `
  <div class="page-narrow">
    <div class="page-head rise"><p class="eyebrow">${ICON.lock} Security Console</p><h1>Admin <span class="grad-text">Console</span></h1>
    <p class="lede">Restricted area. Tokens are rate-limited and never logged.</p></div>
    <section class="panel rise rise-1">
      <form method="post" action="/admin/login">
        <label class="field"><span>Admin Access Token</span><input type="password" name="token" autocomplete="current-password" placeholder="••••••••••••" required /></label>
        <button class="btn primary block" type="submit">${ICON.lock}<span>Authenticate</span></button>
      </form>
    </section>
  </div>`;
}

module.exports = { renderAdminLogin };

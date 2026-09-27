'use strict';
/** Password reset views (bot-DM flow, anti-oracle). Pure functions. */
const { esc } = require('../ui/esc');
const { ICON } = require('../ui/icons');

function renderForgot() {
  return `
  <div class="page-narrow">
    <div class="page-head rise">
      <p class="eyebrow">Locked out</p>
      <h1>Reset your <span class="grad-text">password.</span></h1>
      <p class="lede">Type your username. We'll check which bot you verified with and DM you a reset code there — Telegram DMs or Discord DMs, never email.</p>
    </div>
    <section class="panel rise rise-1">
      <form method="post" action="/forgot-password">
        <label class="field"><span>Username</span><input type="text" name="username" maxlength="24" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="your username" /></label>
        <button class="btn primary block" type="submit">Send reset code</button>
      </form>
    </section>
    <p class="muted small"><a href="/login">Back to sign in</a></p>
  </div>`;
}

function renderReset({ username }) {
  return `
  <div class="page-narrow">
    <div class="page-head rise">
      <p class="eyebrow">Locked out</p>
      <h1>Enter your <span class="grad-text">reset code.</span></h1>
      <p class="lede">We sent a 6-letter code to your bot DMs. Type it below with your new password.</p>
    </div>
    <section class="panel rise rise-1">
      <form method="post" action="/reset-password" data-otp-form>
        <label class="field"><span>Username</span><input type="text" name="username" maxlength="24" autocomplete="username" autocapitalize="none" spellcheck="false" value="${esc(username)}" /></label>
        <div class="otp-wrap" data-otp="code" data-otp-length="6">
          <label class="field field-tight"><span>Reset code</span>
            <input type="text" name="code" maxlength="12" autocomplete="one-time-code" autocapitalize="characters" spellcheck="false" placeholder="e.g. KQ7XPD" class="mono" data-otp-hidden required />
          </label>
          <div class="otp-boxes" aria-hidden="false" role="group" aria-label="Reset code digits"></div>
          <p class="otp-hint">Type, paste, or tap — boxes auto-advance. Backspace goes back.</p>
        </div>
        <label class="field"><span>New password (min 8 characters)</span><input type="password" name="password" maxlength="128" autocomplete="new-password" placeholder="••••••••" /></label>
        <button class="btn primary block" type="submit">Set new password</button>
      </form>
    </section>
  </div>`;
}

module.exports = { renderForgot, renderReset };

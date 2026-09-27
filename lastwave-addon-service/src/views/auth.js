'use strict';
/**
 * Auth views: signup, login, banned. Pure functions, escaped output.
 * Floating glass cards with segmented tab switching.
 */
const { esc } = require('../ui/esc');
const { ICON } = require('../ui/icons');

function tabs(active) {
  return `<nav class="segmented" aria-label="Account">
    <a href="/signup"${active === 'signup' ? ' aria-current="page"' : ''}>Create account</a>
    <a href="/login"${active === 'login' ? ' aria-current="page"' : ''}>Sign in</a>
  </nav>`;
}

function renderSignup() {
  return `
  <div class="page-narrow">
    <div class="page-head rise">
      <p class="eyebrow">Registration</p>
      <h1>Create your <span class="grad-text">account.</span></h1>
      <p class="lede">Choose a username and password to secure your personal streaming key.</p>
    </div>
    ${tabs('signup')}
    <section class="panel rise rise-1" aria-label="Create account">
      <form method="post" action="/signup">
        <label class="field">
          <span>Username</span>
          <input type="text" name="username" maxlength="24" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="e.g. wave_listener" required />
        </label>
        <label class="field">
          <span>Password (min 8 characters)</span>
          <input type="password" name="password" maxlength="128" autocomplete="new-password" required />
        </label>
        <label class="field">
          <span>Repeat password</span>
          <input type="password" name="password2" maxlength="128" autocomplete="new-password" required />
        </label>
        <button class="btn primary block" type="submit"><span>Create Account</span></button>
      </form>
      <p class="muted small fineprint">Usernames must be 3–24 characters: lowercase letters, numbers, and underscores.</p>
    </section>
    <p class="muted small">Already have an account? <a href="/login">Sign in</a></p>
  </div>`;
}

function renderLogin({ resetDone, allowDevLogin }) {
  return `
  <div class="page-narrow">
    <div class="page-head rise">
      <p class="eyebrow">Authentication</p>
      <h1>Welcome <span class="grad-text">back.</span></h1>
    </div>
    ${tabs('login')}
    ${resetDone ? `<div class="notice notice-success rise rise-1">${ICON.check}<div><strong>Password updated.</strong> Sign in with your new password.</div></div>` : ''}
    <section class="panel rise rise-1" aria-label="Sign in">
      <form method="post" action="/login">
        <label class="field">
          <span>Username</span>
          <input type="text" name="username" maxlength="24" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="your username" required />
        </label>
        <label class="field">
          <span>Password</span>
          <input type="password" name="password" maxlength="128" autocomplete="current-password" placeholder="••••••••" required />
        </label>
        <button class="btn primary block" type="submit"><span>Sign in</span></button>
      </form>
      <p class="muted small fineprint"><a href="/forgot-password">Forgot password?</a> · No account yet? <a href="/signup">Create one</a></p>
    </section>
    ${allowDevLogin ? `
    <details class="disclosure rise rise-2">
      <summary>Developer sign-in <span class="muted small">(dev only)</span></summary>
      <div class="inner">
      <form method="post" action="/auth/dev">
        <label class="field"><span>Developer handle</span><input type="text" name="handle" placeholder="dev-user" maxlength="40" autocomplete="off" required /></label>
        <label class="field"><span>Developer password</span><input type="password" name="password" autocomplete="current-password" required /></label>
        <button class="btn secondary block" type="submit">Sign in as developer</button>
      </form>
      </div>
    </details>` : ``}
  </div>`;
}

function renderBanned({ reason }) {
  return `
    <div class="page-narrow">
  <div class="page-head rise">
    <p class="eyebrow eyebrow-danger">Permanent Ban</p>
    <h1>Account permanently banned.</h1>
    <p class="lede">This account and all linked identities have been permanently banned by an administrator.</p>
  </div>
  <section class="panel rise rise-1">
    <div class="notice notice-danger">
      <strong>Ban status:</strong> Lifetime Ban (Permanent)<br />
      <span class="small muted">Reason: ${esc(reason)}</span>
    </div>
    <p class="muted small fineprint">Access to addon streaming, token regeneration, and channel verification has been permanently revoked for this account.</p>
    <div class="actions-row">
      <form method="post" action="/logout"><button class="btn secondary" type="submit"><span>Sign out</span></button></form>
    </div>
  </section>
</div>`;
}

module.exports = { renderSignup, renderLogin, renderBanned };

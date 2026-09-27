'use strict';
/**
 * Home / landing view. Pure function — no req/res, no admin URLs.
 */
const { ICON } = require('../ui/icons');

function renderHome({ user, siteAccount }) {
  return `
  <section class="section section-hero">
    <div class="wrap wrap-narrow">
      <div class="hero rise">
        <div class="eyebrow-pill"><span class="live-dot" aria-hidden="true"></span><span>Addon Infrastructure · Operational</span></div>
        <h1>Your personal addon URL for <span class="grad-text">LastWave.</span></h1>
        <p class="lede">Create an account, verify channel membership via Telegram or Discord, and unlock instant high-fidelity audio streaming with 500 songs daily quota.</p>
        <div class="eq-row" aria-hidden="true"><span class="eq"><i></i><i></i><i></i><i></i><i></i></span><span class="eq-label">Hi-Fi streaming engine · live</span></div>
      </div>
      <div class="actions-row hero-cta rise rise-1">
        ${user
          ? `<a class="btn primary" href="/dashboard">${ICON.music}<span>Open Dashboard</span></a>`
          : siteAccount
            ? `<a class="btn primary" href="/verify">${ICON.shield}<span>Continue Verification</span></a>`
            : `<a class="btn primary" href="/signup"><span>Create Account</span></a>
               <a class="btn secondary" href="/login"><span>Sign in</span></a>`}
      </div>
    </div>
  </section>
  <section class="section section-stats" aria-label="Highlights">
    <div class="wrap">
      <div class="tiles rise rise-2">
        <div class="tile"><div class="t-label">Daily quota</div><div class="t-value num">500</div><div class="t-sub">songs · resets 00:00 UTC</div></div>
        <div class="tile"><div class="t-label">Star bonus</div><div class="t-value grad-text">+50%</div><div class="t-sub">via GitHub tasks</div></div>
        <div class="tile"><div class="t-label">Channels</div><div class="t-value">2</div><div class="t-sub">Telegram · Discord</div></div>
      </div>
    </div>
  </section>
  <section class="section section-steps">
    <div class="wrap">
      <ol class="steps rise rise-2">
        <li>
          <span class="step-num">1</span>
          <div>
            <strong>Create Account</strong>
            <p>Register your unique username and password. This secures your streaming credentials.</p>
          </div>
        </li>
        <li>
          <span class="step-num">2</span>
          <div>
            <strong>Link Identity</strong>
            <p>Verify channel membership with our automated Telegram bot or 1-click Discord authorization.</p>
          </div>
        </li>
        <li>
          <span class="step-num">3</span>
          <div>
            <strong>Paste into LastWave</strong>
            <p>Add your private endpoint to LastWave Sources. Listen to up to 500 lossless songs daily.</p>
          </div>
        </li>
      </ol>
    </div>
  </section>
  <section class="section section-note">
    <div class="wrap wrap-narrow">
      <div class="notice rise rise-3">
        ${ICON.info}
        <div>
          <strong>Per-Account Quota Architecture.</strong>
          Quota is tied to your account, not a specific device. Your allocation resets daily at 00:00 UTC.
        </div>
      </div>
    </div>
  </section>`;
}

module.exports = { renderHome };

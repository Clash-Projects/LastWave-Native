'use strict';
/**
 * Verify views: provider index + code pages. Pure functions.
 * Route keeps guards, rate-limits, code lookup; view only renders.
 */
const { esc } = require('../ui/esc');
const { ICON } = require('../ui/icons');

function renderVerifyIndex({ username, tgBot, dcBot, telegramChannel, discordConfigured }) {
  return `
  <div class="page-narrow">
    <div class="page-head rise">
      <p class="eyebrow">${ICON.shield} Step 2 of 3 · Channel Verification</p>
      <h1>Link your <span class="grad-text">identity.</span></h1>
      <p class="lede">Signed in as <strong class="mono">@${esc(username)}</strong>. Verify channel membership to activate your daily addon streaming key.</p>
    </div>
    <div class="provider-grid">
      <section class="provider-card brand-tg beam tilt rise rise-1" aria-label="Telegram verification">
        <div class="provider-head">
          <div class="provider-icon-badge provider-tg">${ICON.telegram}</div>
          <div>
            <h2>Telegram</h2>
            <p class="panel-sub">Bot verification</p>
          </div>
          <span class="brand-tag">Bot</span>
        </div>
        <p class="muted small">Quick 6-letter verification. Checks that you are a member of ${telegramChannel ? `<code class="mono">${esc(telegramChannel)}</code>` : 'our channel'}.</p>
        <div class="push-bottom">
          ${tgBot
            ? `<form method="post" action="/verify/telegram/new"><button class="btn telegram block" type="submit">${ICON.telegram}<span>Verify with Telegram</span></button></form>`
            : `<p class="muted small">Telegram verification is not configured yet.</p>`}
        </div>
      </section>
      <section class="provider-card brand-dc beam tilt rise rise-2" aria-label="Discord verification">
        <div class="provider-head">
          <div class="provider-icon-badge provider-dc">${ICON.discord}</div>
          <div>
            <h2>Discord</h2>
            <p class="panel-sub">1-Click OAuth2</p>
          </div>
          <span class="brand-tag">OAuth2</span>
        </div>
        <p class="muted small">Instant sign-in. Automatically connects your Discord account and adds you to our community server.</p>
        <div class="push-bottom">
          ${discordConfigured
            ? `<a class="btn discord block" href="/auth/discord">${ICON.discord}<span>Authorize Discord</span></a>`
            : `<p class="muted small">Discord login is not configured yet.</p>`}
        </div>
      </section>
    </div>
    <div class="notice rise rise-3">${ICON.info}<div><strong>Why verify?</strong> One membership check unlocks your private streaming URL and 500 daily plays.</div></div>
  </div>`;
}

module.exports = { renderVerifyIndex };

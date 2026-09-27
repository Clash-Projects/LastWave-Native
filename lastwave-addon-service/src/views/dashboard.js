'use strict';
/**
 * Dashboard view: hero addon card, live quota ring + countdown, GitHub bonus, backup, security.
 * Pure function — route resolves token/usage, view only renders.
 * Preserved contracts: #addon-url, #manifest-url, #addon-backup, data-copy,
 * data-download, data-saved, POST /addon/regenerate (data-confirm), /logout,
 * /github/claim, /github/unlink, /auth/github.
 */
const { esc } = require('../ui/esc');
const { ICON } = require('../ui/icons');
const { pill } = require('../ui/components');

function ringSvg(pct) {
  const r = 52;
  const c = 2 * Math.PI * r;
  const off = (c * (100 - pct)) / 100;
  return `<div class="quota-ring" role="img" aria-label="Quota usage ${pct} percent">
    <svg viewBox="0 0 120 120" aria-hidden="true">
      <defs><linearGradient id="quotaGrad" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#71717A"/><stop offset="55%" stop-color="#A1A1AA"/><stop offset="100%" stop-color="#E4E4E7"/>
      </linearGradient></defs>
      <circle class="ring-bg" cx="60" cy="60" r="${r}" fill="none" stroke-width="10"/>
      <circle class="ring-fg" cx="60" cy="60" r="${r}" fill="none" stroke-width="10"
        stroke-dasharray="${c.toFixed(1)}" stroke-dashoffset="${off.toFixed(1)}" data-quota-ring="${pct}"/>
    </svg>
    <div class="ring-center"><span class="ring-pct num">${pct}%</span><span class="ring-cap">used</span></div>
  </div>`;
}

function renderDashboard({ user, siteAccount, tokenRow, usage, root, manifestUrl }) {
  const pct = usage.isBypassed ? 0 : Math.min(100, Math.round((usage.used / usage.limit) * 100));
  const hot = !usage.isBypassed && usage.remaining <= 50;
  const initials = (user.display_name || user.username || '?').trim().charAt(0).toUpperCase();
  const providerIcon = user.provider === 'discord' ? ICON.discord : user.provider === 'telegram' ? ICON.telegram : ICON.user;
  const qrSrc = `https://api.qrserver.com/v1/create-qr-code/?size=224x224&margin=0&data=${encodeURIComponent(root)}`;

  return `
  <div class="page-head rise">
    <p class="eyebrow"><span class="live-dot" aria-hidden="true"></span> Dashboard · Live</p>
    <h1>Personal <span class="grad-text">Addon</span></h1>
  </div>

  <div class="split">
    <div>
      <section class="hero-card beam tilt rise rise-1" aria-label="Addon URL">
        <div class="identity">
          ${user.avatar_url ? `<img class="avatar" src="${esc(user.avatar_url)}" alt="" />` : `<span class="avatar avatar-fallback" aria-hidden="true">${esc(initials)}</span>`}
          <div>
            <div class="who">${esc(user.display_name || user.username)}</div>
            <div class="sub">${providerIcon} ${esc(user.provider)} · ${esc(user.username || user.provider_user_id)}</div>
            <div class="pills">
              ${pill(tokenRow.revoked ? 'Revoked' : 'Active', tokenRow.revoked ? 'revoked' : 'active')}
              ${user.verified_via ? pill('Verified', 'verified') : ''}
              ${usage.isBypassed ? pill('VIP Unlimited', 'vip') : ''}
              ${usage.hasStarBonus ? pill('Star ×1.5', 'vip', ICON.star) : ''}
            </div>
          </div>
        </div>
        <div class="rowline">
          <span class="k">Account handle</span>
          <span class="v mono">@${esc(siteAccount.username)}</span>
        </div>
        <div class="url-group">
          <span class="addon-url-label">Primary Addon Endpoint</span>
          <div class="urlbox urlbox-hero">
            <code id="addon-url">${esc(root)}</code>
            <button class="btn primary" data-copy="#addon-url" type="button">${ICON.copy}<span>Copy</span></button>
          </div>
        </div>
        <div class="quick-actions" aria-label="Quick actions">
          <button class="btn secondary" data-copy="#addon-url" type="button">${ICON.copy}<span>Copy Link</span></button>
          <a class="btn secondary" href="${esc(root)}" target="_blank" rel="noopener">${ICON.external}<span>Open in LastWave</span></a>
          <button class="btn secondary" data-qr="#addon-url" type="button">${ICON.sparkles}<span>QR Pair</span></button>
        </div>
        <div class="url-group">
          <span class="url-label">Manifest URL</span>
          <div class="urlbox urlbox-secondary">
            <code id="manifest-url">${esc(manifestUrl)}</code>
            <button class="btn secondary small" data-copy="#manifest-url" type="button">${ICON.copy}<span>Copy</span></button>
          </div>
        </div>
        <ol class="howto">
          <li><span class="task-icon task-done">${ICON.check}</span> <span>Open <strong>LastWave</strong> → <strong>Sources</strong> → <strong>Add addon</strong></span></li>
          <li><span class="task-icon task-done">${ICON.check}</span> <span>Paste the URL copied above into the endpoint field</span></li>
          <li><span class="task-icon task-done">${ICON.check}</span> <span>Start streaming. Plays count against your daily quota</span></li>
        </ol>
      </section>
    </div>

    <div>
      <section class="panel beam tilt rise rise-1" aria-label="Daily quota">
        <div class="row-head">
          <h2><span class="eq" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></span> Today's Quota</h2>
          <span class="countdown-chip" data-countdown-utc-midnight title="Resets at 00:00 UTC">--:--:--</span>
        </div>
        <p class="panel-sub">Resets at 00:00 UTC</p>
        <div class="quota-meta">
          ${usage.isBypassed
            ? `<span class="big grad-text vip-display">VIP Unlimited</span><span class="of num">${usage.used} played today</span>`
            : `${ringSvg(pct)}
               <div class="qm">
                 <div class="k">Remaining</div>
                 <div class="v num">${usage.remaining} <small>/ ${usage.limit}</small></div>
                 <div class="muted small num" style="margin-top:4px">${usage.used} played today</div>
               </div>`}
        </div>
        ${usage.isBypassed
          ? `<div class="notice notice-success"><div><strong>VIP Access Active.</strong> Unlimited audio streaming with no daily cap.</div></div>`
          : `<div class="meter${hot ? ' hot' : ''}" role="progressbar" aria-valuenow="${pct}" aria-valuemin="0" aria-valuemax="100" aria-label="Daily quota usage"><i style="width:${pct}%" data-quota-bar="${pct}"></i></div>
              ${usage.remaining === 0
                ? `<div class="notice warn">${ICON.info}<div><strong>Quota finished for today.</strong> Plays unlock at midnight UTC.</div></div>`
                : `<p class="muted small fineprint">${usage.isCustom ? `Custom tier: <span class="mono-badge">${usage.limit}/day</span> ` : ''}${usage.bonusPlays > 0 ? `Includes <span class="mono-badge">+${usage.bonusPlays} bonus</span> today. ` : ''}${usage.hasStarBonus ? `Includes <span class="mono-badge">+${usage.starBonus} GitHub</span> bonus. ` : ''}Resets daily.</p>`}`}
      </section>

      <section class="panel rise rise-2" aria-label="GitHub Bonus">
        <div class="row-head">
          <h2>GitHub Star Bonus</h2>
          <span class="pill pill-vip"><span class="pill-icon" aria-hidden="true">${ICON.star}</span><span class="pill-label">+50% Quota</span></span>
        </div>
        ${usage.hasStarBonus
          ? `<p class="panel-sub">Linked as <span class="mono">@${esc(user.github_username || user.github_user_id)}</span>. Your daily quota includes <strong>+${usage.starBonus}</strong> bonus songs.</p>
             <ol class="howto">
               <li><span class="task-icon task-done">${ICON.check}</span> <a href="https://github.com/Clash-Projects/LastWave-Native" target="_blank" rel="noopener"><span class="mono">Clash-Projects/LastWave-Native</span> ${ICON.external}</a></li>
               <li><span class="task-icon task-done">${ICON.check}</span> <a href="https://github.com/Clash-Projects/LastWave-Desktop" target="_blank" rel="noopener"><span class="mono">Clash-Projects/LastWave-Desktop</span> ${ICON.external}</a></li>
               <li><span class="task-icon task-done">${ICON.check}</span> <a href="https://github.com/ajisth69" target="_blank" rel="noopener"><span class="mono">@ajisth69</span> ${ICON.external}</a></li>
               <li><span class="task-icon task-done">${ICON.check}</span> <a href="https://github.com/Clash-Projects" target="_blank" rel="noopener"><span class="mono">@Clash-Projects</span> ${ICON.external}</a></li>
             </ol>
              <div class="actions-row">
                <form method="post" action="/github/unlink"><button class="btn secondary small" type="submit"><span>Unlink GitHub</span></button></form>
              </div>`
          : user.github_user_id
            ? `<p class="panel-sub">Linked as <span class="mono">@${esc(user.github_username || user.github_user_id)}</span>. Complete all 4 tasks below, then click Verify Tasks:</p>
               <ol class="howto">
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/Clash-Projects/LastWave-Native" target="_blank" rel="noopener"><span class="mono">Star LastWave-Native</span> ${ICON.external}</a></li>
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/Clash-Projects/LastWave-Desktop" target="_blank" rel="noopener"><span class="mono">Star LastWave-Desktop</span> ${ICON.external}</a></li>
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/ajisth69" target="_blank" rel="noopener"><span class="mono">Follow @ajisth69</span> ${ICON.external}</a></li>
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/Clash-Projects" target="_blank" rel="noopener"><span class="mono">Follow @Clash-Projects</span> ${ICON.external}</a></li>
               </ol>
               <div class="actions-row">
                 <form method="post" action="/github/claim"><button class="btn primary" type="submit">${ICON.refresh}<span>Verify Tasks</span></button></form>
                 <form method="post" action="/github/unlink"><button class="btn secondary small" type="submit"><span>Unlink</span></button></form>
               </div>`
            : `<p class="panel-sub">Complete all 4 tasks to unlock <strong>+50% extra daily songs</strong> (+250 plays/day).</p>
               <ol class="howto">
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/Clash-Projects/LastWave-Native" target="_blank" rel="noopener"><span class="mono">Star LastWave-Native</span> ${ICON.external}</a></li>
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/Clash-Projects/LastWave-Desktop" target="_blank" rel="noopener"><span class="mono">Star LastWave-Desktop</span> ${ICON.external}</a></li>
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/ajisth69" target="_blank" rel="noopener"><span class="mono">Follow @ajisth69</span> ${ICON.external}</a></li>
                 <li><span class="task-icon task-pending">${ICON.circle}</span> <a href="https://github.com/Clash-Projects" target="_blank" rel="noopener"><span class="mono">Follow @Clash-Projects</span> ${ICON.external}</a></li>
               </ol>
               <div class="actions-row">
                 <a class="btn primary" href="/auth/github">${ICON.github}<span>Connect GitHub</span></a>
               </div>`}
      </section>

      <section class="panel rise rise-3" aria-label="Backup">
        <h2>Backup &amp; Storage</h2>
        <p class="panel-sub">Your addon token grants access to your streaming allocation. Keep a backup copy stored safely.</p>
        <div class="actions-row">
          <button class="btn secondary" data-copy="#addon-url" type="button">${ICON.copy}<span>Copy URL</span></button>
          <button class="btn secondary" data-download="lastwave-addon-backup.txt" data-download-from="#addon-backup" type="button">${ICON.download}<span>Download File</span></button>
        </div>
        <script type="text/plain" id="addon-backup">LastWave Addons — Secure Backup
Account: ${esc(siteAccount.username)}
Addon URL: ${esc(root)}
Manifest URL: ${esc(manifestUrl)}
Daily quota: 500 songs (resets 00:00 UTC).
Keep this file safe. If compromised, regenerate your token in the dashboard.</script>
        <label class="savecheck">
          <input type="checkbox" data-saved="lw-addon-saved" />
          <span>I have saved my URL securely</span>
          <span class="saved-pill" data-saved-pill hidden>${ICON.check} Stored</span>
        </label>
      </section>

      <section class="panel rise rise-3" aria-label="Security">
        <h2>Security</h2>
        <p class="panel-sub">Regenerating creates a new URL and invalidates the old one immediately.</p>
        <div class="actions-row">
          <form method="post" action="/addon/regenerate" data-confirm="Regenerate Addon URL?" data-confirm-desc="The current URL will stop working immediately. Remember to update it inside LastWave.">
            <button class="btn danger" type="submit"><span>Regenerate Key</span></button>
          </form>
          <form method="post" action="/logout">
            <button class="btn secondary" type="submit"><span>Sign out</span></button>
          </form>
        </div>
      </section>
    </div>
  </div>
  <img src="${qrSrc}" alt="" width="1" height="1" loading="lazy" style="position:absolute;width:1px;height:1px;opacity:0;pointer-events:none" aria-hidden="true" />`;
}

module.exports = { renderDashboard };

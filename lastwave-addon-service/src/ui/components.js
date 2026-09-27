'use strict';
/**
 * Shared UI components: pills, empty states, error cards, admin tabs,
 * identity mentions, GitHub task lists. All SVG, zero emoji.
 */
const { esc } = require('./esc');
const { ICON } = require('./icons');

/** Small status pill: tone is active|revoked|pending|verified|muted|warn|vip|banned|custom. */
function pill(text, tone = 'muted', customIcon = null) {
  const icon = customIcon || (
    tone === 'active' || tone === 'verified' ? ICON.check :
    tone === 'vip' ? ICON.star :
    tone === 'pending' ? ICON.refresh : ''
  );
  return `<span class="pill pill-${esc(tone)}">${icon ? `<span class="pill-icon" aria-hidden="true">${icon}</span>` : `<span class="pill-dot" aria-hidden="true"></span>`}<span class="pill-label">${esc(text)}</span></span>`;
}

function emptyState(text) {
  return `<div class="empty"><p>${esc(text)}</p></div>`;
}

function formError(title, heading, text, backHref, backLabel) {
  const { layout } = require('./layout');
  return layout({
    title,
    body: `
    <div class="page-narrow">
      <div class="page-head rise">
        <p class="eyebrow eyebrow-danger">Notice</p>
        <h1>${esc(heading)}</h1>
        <p class="lede">${text}</p>
      </div>
      <div class="actions-row rise rise-1">
        <a class="btn primary" href="${backHref}">${esc(backLabel)}</a>
      </div>
    </div>`,
  });
}

function adminTabs(active) {
  const tab = (href, label, key) =>
    `<a href="${href}"${key === active ? ' aria-current="page"' : ''}>${label}</a>`;
  return `<nav class="admintabs" aria-label="Admin sections">${tab('/admin', 'Accounts', 'accounts')}${tab('/admin/codes', 'Codes', 'codes')}</nav>`;
}

/** Copyable @mention for an account (safe to store/display anywhere). Admin only. */
function mentionHtml(u) {
  const id = `mention-${u.id}`;
  if (u.provider === 'telegram' && u.username) {
    return `<span class="mention"><a href="https://t.me/${esc(u.username)}" target="_blank" rel="noopener">@${esc(u.username)}</a><button class="btn secondary small" data-copy="#${id}" type="button">Copy</button><span id="${id}" hidden>@${esc(u.username)}</span></span>`;
  }
  if (u.provider === 'discord') {
    const tag = u.username ? `@${u.username}` : `<@${u.provider_user_id}>`;
    return `<span class="mention"><span class="mono">${esc(tag)}</span><button class="btn secondary small" data-copy="#${id}" type="button">Copy</button><span id="${id}" hidden>${esc(`<@${u.provider_user_id}>`)}</span></span>`;
  }
  return `<span class="mention"><span class="mono">${esc(u.username || u.provider_user_id)}</span></span>`;
}

/** GitHub bonus checklist (SVG icons only, zero emoji). */
function taskListHtml(tasks) {
  const row = (done, labelHtml) =>
    '<li><span class="task-icon ' + (done ? 'task-done' : 'task-pending') + '" aria-hidden="true">' + (done ? ICON.check : ICON.circle) + '</span><span>' + labelHtml + (done ? '' : ' \u2014 <strong>missing</strong>') + '</span></li>';
  const starItems = tasks.stars.map((s) =>
    row(s.done, 'Star <a href="' + esc(s.url) + '" target="_blank" rel="noopener"><span class="mono">' + esc(s.repo) + '</span></a>')
  ).join('');
  const followItems = tasks.follows.map((f) =>
    row(f.done, 'Follow <a href="' + esc(f.url) + '" target="_blank" rel="noopener"><span class="mono">@' + esc(f.account) + '</span></a>')
  ).join('');
  return '<ol class="howto">' + starItems + followItems + '</ol>';
}

module.exports = { pill, emptyState, formError, adminTabs, mentionHtml, taskListHtml };

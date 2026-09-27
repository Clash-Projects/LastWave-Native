'use strict';
/** GitHub bonus views. Pure functions. */
const { esc } = require('../ui/esc');
const { taskListHtml } = require('../ui/components');

function renderTasksRemaining({ githubLogin, tasks, retry = false }) {
  return `<div class="page-narrow">
    <div class="page-head rise"><p class="eyebrow">Star bonus</p><h1>${retry ? 'Not yet — <span class="grad-text">missing tasks.</span>' : 'Finish all <span class="grad-text">4 tasks.</span>'}</h1>
    <p class="lede">Linked as <span class="mono">@${esc(githubLogin)}</span>. ${retry ? 'Finish everything below, wait a few seconds, then check again.' : 'Complete everything below, then hit “Check again” on the dashboard.'}</p></div>
    <section class="panel rise rise-1">${taskListHtml(tasks)}</section>
    <div class="actions-row"><a class="btn secondary" href="/dashboard">Back to dashboard</a></div>
  </div>`;
}

function renderGithubError({ message }) {
  return `<div class="page-narrow"><div class="page-head rise"><p class="eyebrow">Star bonus</p><h1>Not yet.</h1><p class="lede">${esc(message)}</p></div><div class="actions-row"><a class="btn secondary" href="/dashboard">Back to dashboard</a></div></div>`;
}

module.exports = { renderTasksRemaining, renderGithubError };

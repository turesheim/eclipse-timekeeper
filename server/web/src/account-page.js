import { t as i18nText } from "./i18n.js";import { renderAccount } from './account.js';
import { settingsView } from './settings-view.js';
import { node, link } from './account-ui.js';
const $ = (id) => document.getElementById(id);
let context = null,generation = 0,view = null,acceptedHash = '';
function disposeView() {for (const cleanup of view?.cleanup || []) cleanup();view = null;}
function route() {return location.hash === '#main-content' ? acceptedHash : location.hash;}
function restoreRoute() {history.pushState(null, '', location.pathname + location.search + acceptedHash);}
function canLeave() {
  if (view?.busy) {$('account-error').textContent = i18nText("ui.wait_for_the_current_change_to_finish_before_navigating");return false;}
  return !view?.dirty.size || window.confirm(i18nText("ui.discard_unsaved_account_changes"));
}
export function closeAccount() {
  ++generation;context = null;disposeView();acceptedHash = '';
  $('account-page').hidden = true;$('account-content').replaceChildren();$('account-navigation').replaceChildren();
  $('account-error').replaceChildren();$('account-heading').textContent = '';$('account-identity').textContent = '';$('users-link').hidden = true;
}
export async function connectAccount(session, account, signOut, updateAccount, refreshProfile, renderCalendars, renderLabels) {
  closeAccount();context = { session, account, signOut, updateAccount, refreshProfile, renderCalendars, renderLabels };
  $('users-link').hidden = !account.administrator || !account.permissions?.includes('write');
  await show(route(), false);
}
async function show(hash, ask = true) {
  if (!context) return;
  if (ask && !canLeave()) {restoreRoute();return;}
  if (document.querySelector('dialog[open]')) {restoreRoute();return;}
  if (hash === '#administration' || hash.startsWith('#users')) {
    const legacy = /^#users(?:\/(?:new|[A-Za-z0-9_-]{1,1100}))?$/.test(hash) ? hash : '';
    location.assign('/admin/' + legacy);return;
  }
  acceptedHash = hash;const current = ++generation,scope = context;
  disposeView();
  const valid = () => generation === current && context === scope;
  const page = hash.startsWith('#account') ? 'account' : 'tracking';
  $('account-content').replaceChildren();$('account-error').replaceChildren();$('account-navigation').replaceChildren();
  $('account-content').setAttribute('aria-busy', 'false');
  $('workspace').hidden = page !== 'tracking';$('account-page').hidden = page === 'tracking';
  view = null;
  if (page === 'tracking') {$('workspace-navigation').focus();return;}
  $('account-heading').textContent = i18nText("ui.my_account");
  $('account-identity').textContent = i18nText("ui.signed_in_as_your_work_data_is_private_to_your_account", { value0: scope.account.username || scope.account.displayName });
  $('account-heading').focus();
  view = settingsView({ root: $('account-content'), session: scope.session, account: scope.account, valid, feedback: $('account-error'),
    changed(account) {scope.account = { ...scope.account, ...account };scope.updateAccount(account);$('owner').textContent = account.displayName;$('account-identity').textContent = i18nText("ui.signed_in_as_your_work_data_is_private_to_your_account", { value0: account.username || account.displayName });},
    signOut: scope.signOut, refreshProfile: scope.refreshProfile, navigate: (next) => {location.hash = next;},
    reload: () => void show(acceptedHash)
  });
  const currentView = view;
  if (!view.canWrite) view.root.append(node('p', i18nText("ui.this_session_is_read_only_sign_in_with_your_password_to_change_settings"), 'alert alert-info'));
  if (page === 'account') {
    const part = ['profile', 'password', 'passkeys', 'tokens', 'data', 'import', 'calendars', 'labels', 'journal'].includes(hash.split('/')[1]) ? hash.split('/')[1] : 'profile';
    for (const [key, title] of [['profile', i18nText("ui.profile")], ['password', i18nText("ui.password")], ['passkeys', i18nText("ui.passkeys")], ['calendars', i18nText('calendar.subscriptions')], ['labels', i18nText('ui.labels')], ['tokens', i18nText("ui.api_tokens")], ['data', i18nText("ui.your_data")], ['import', i18nText("ui.import")]]) {
      const item = link(title, `#account/${key}`, 'nav-link');if (key === part) {item.classList.add('active');item.setAttribute('aria-current', 'page');}
      $('account-navigation').append(item);
    }
    const journal=link(i18nText('journal.webdav.title'),'#account/journal','nav-link');if(part==='journal'){journal.classList.add('active');journal.setAttribute('aria-current','page');}$('account-navigation').append(journal);
    await view.run(() => part === 'calendars' ? scope.renderCalendars(currentView) : part === 'labels' ? scope.renderLabels(currentView) : renderAccount(currentView, part));
  }
}
window.addEventListener('hashchange', () => {if (context) void show(route());});
window.addEventListener('beforeunload', (event) => {if (view?.dirty.size || view?.busy) {event.preventDefault();event.returnValue = '';}});

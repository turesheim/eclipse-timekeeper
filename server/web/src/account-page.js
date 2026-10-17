import {renderAccount} from './account.js';
import {renderUsers} from './users.js';
import {node, link, button} from './account-ui.js';
const $ = id => document.getElementById(id);
let context = null, generation = 0, view = null, acceptedHash = '';
function disposeView() {for (const cleanup of view?.cleanup || []) cleanup(); view = null;}
function route() {return location.hash === '#main-content' ? acceptedHash : location.hash;}
function restoreRoute() {history.pushState(null, '', location.pathname + location.search + acceptedHash);}
function canLeave() {
  if (view?.busy) { $('account-error').textContent = 'Wait for the current change to finish before navigating.'; return false; }
  return !view?.dirty.size || window.confirm('Discard unsaved account changes?');
}
export function closeAccount() {
  ++generation; context = null; disposeView(); acceptedHash = '';
  $('account-page').hidden = true; $('account-content').replaceChildren(); $('account-navigation').replaceChildren();
  $('account-error').replaceChildren(); $('account-heading').textContent = ''; $('account-identity').textContent = ''; $('users-link').hidden = true;
}
export async function connectAccount(session, account, signOut, updateAccount, refreshProfile) {
  closeAccount(); context = {session, account, signOut, updateAccount, refreshProfile};
  $('users-link').hidden = !account.administrator || !account.permissions?.includes('write');
  await show(route(), false);
}
async function show(hash, ask = true) {
  if (!context) return;
  if (ask && !canLeave()) {restoreRoute(); return;}
  if ($('editor').open) {restoreRoute(); return;}
  acceptedHash = hash; const current = ++generation, scope = context;
  disposeView();
  const valid = () => generation === current && context === scope;
  const page = hash.startsWith('#users') ? 'users' : hash.startsWith('#account') ? 'account' : 'tracking';
  $('account-content').replaceChildren(); $('account-error').replaceChildren(); $('account-navigation').replaceChildren();
  $('account-content').setAttribute('aria-busy', 'false');
  $('workspace').hidden = page !== 'tracking'; $('account-page').hidden = page === 'tracking';
  view = null;
  if (page === 'tracking') { $('workspace-navigation').focus(); return; }
  $('account-heading').textContent = page === 'users' ? 'Users' : 'My Account';
  $('account-identity').textContent = `Signed in as ${scope.account.username || scope.account.displayName}. Your work data is private to your account.`;
  $('account-heading').focus();
  view = {
    root: $('account-content'), session: scope.session, account: scope.account, valid,
    canWrite: !!scope.account.permissions?.includes('write'), dirty: new Set(), cleanup: new Set(), busy: false, pending: 0, uncertain: false,
    clean(form) {if (valid()) this.dirty.delete(form);},
    changed(account) {if (!valid()) return; scope.account = {...scope.account, ...account}; this.account = scope.account; scope.updateAccount(account); $('owner').textContent = account.displayName; $('account-identity').textContent = `Signed in as ${account.username || account.displayName}. Your work data is private to your account.`;},
    signOut: message => {if (valid()) scope.signOut(message);},
    refreshProfile: async () => {if (valid()) await scope.refreshProfile?.();},
    navigate: next => {if (valid()) location.hash = next;},
    async run(work, {submit, feedback = $('account-error'), write = false} = {}) {
      if (!valid() || this.busy || (write && (!this.canWrite || this.uncertain))) return;
      feedback.replaceChildren(); if (submit) submit.disabled = true;
      if (write) this.busy = true;
      ++this.pending; this.root.setAttribute('aria-busy', 'true');
      try {await work();}
      catch (error) {
        if (!valid()) return;
        if (error.status === 401) {scope.signOut(error.message); return;}
        feedback.textContent = write && error.uncertain ? 'The outcome is unknown. Review current records before making another change.' : error.code?.startsWith('IMPORT_') ? error.message : [412,409].includes(error.status) ? 'These records changed or conflict with this change. Your draft is kept. Review current values before saving again.' : error.message;
        if ((write && error.uncertain) || [412,409].includes(error.status)) {
          if (error.uncertain) this.uncertain = true;
          feedback.append(' ', button('Reload current values', () => {if (acceptedHash === '#users/new') this.navigate('#users'); else void show(acceptedHash);}));
        }
      } finally {
        if (valid()) {if (write) this.busy = false; --this.pending; this.root.setAttribute('aria-busy', String(this.pending > 0)); if (submit) submit.disabled = !this.canWrite || this.uncertain;}
      }
    }
  };
  const currentView = view;
  if (!view.canWrite) view.root.append(node('p', 'This session is read-only. Sign in with your password to change settings.', 'alert alert-info'));
  if (page === 'users' && (!scope.account.administrator || !view.canWrite)) {
    view.root.append(node('p', 'Administrator access is required to manage independent user accounts.', 'alert alert-warning')); return;
  }
  if (page === 'account') {
    const part = ['profile', 'password', 'tokens', 'import'].includes(hash.split('/')[1]) ? hash.split('/')[1] : 'profile';
    for (const [key, title] of [['profile','Profile'],['password','Password'],['tokens','API tokens'],['import','Import']]) {
      const item = link(title, `#account/${key}`, 'nav-link'); if (key === part) {item.classList.add('active'); item.setAttribute('aria-current', 'page');}
      $('account-navigation').append(item);
    }
    await view.run(() => renderAccount(currentView, part));
  } else {
    $('account-navigation').append(link('Users', '#users', 'nav-link active'));
    await view.run(() => renderUsers(currentView, hash.split('/')[1] || ''));
  }
}
window.addEventListener('hashchange', () => {if (context) void show(route());});
window.addEventListener('beforeunload', event => {if (view?.dirty.size || view?.busy) {event.preventDefault(); event.returnValue = '';}});

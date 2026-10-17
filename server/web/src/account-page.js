import {renderAccount} from './account.js';
import {settingsView} from './settings-view.js';
import {node, link} from './account-ui.js';
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
  if(hash === '#administration' || hash.startsWith('#users')) {
    const legacy = /^#users(?:\/(?:new|[A-Za-z0-9_-]{1,1100}))?$/.test(hash) ? hash : '';
    location.assign('/admin/' + legacy);return;
  }
  acceptedHash = hash; const current = ++generation, scope = context;
  disposeView();
  const valid = () => generation === current && context === scope;
  const page = hash.startsWith('#account') ? 'account' : 'tracking';
  $('account-content').replaceChildren(); $('account-error').replaceChildren(); $('account-navigation').replaceChildren();
  $('account-content').setAttribute('aria-busy', 'false');
  $('workspace').hidden = page !== 'tracking'; $('account-page').hidden = page === 'tracking';
  view = null;
  if (page === 'tracking') { $('workspace-navigation').focus(); return; }
  $('account-heading').textContent = 'My Account';
  $('account-identity').textContent = `Signed in as ${scope.account.username || scope.account.displayName}. Your work data is private to your account.`;
  $('account-heading').focus();
  view = settingsView({root:$('account-content'),session:scope.session,account:scope.account,valid,feedback:$('account-error'),
    changed(account) {scope.account={...scope.account,...account};scope.updateAccount(account);$('owner').textContent=account.displayName;$('account-identity').textContent=`Signed in as ${account.username || account.displayName}. Your work data is private to your account.`;},
    signOut:scope.signOut,refreshProfile:scope.refreshProfile,navigate:next=>{location.hash=next;},
    reload:()=>void show(acceptedHash)
  });
  const currentView = view;
  if (!view.canWrite) view.root.append(node('p', 'This session is read-only. Sign in with your password to change settings.', 'alert alert-info'));
  if (page === 'account') {
    const part = ['profile', 'password', 'passkeys', 'tokens', 'data', 'import'].includes(hash.split('/')[1]) ? hash.split('/')[1] : 'profile';
    for (const [key, title] of [['profile','Profile'],['password','Password'],['passkeys','Passkeys'],['tokens','API tokens'],['data','Your data'],['import','Import']]) {
      const item = link(title, `#account/${key}`, 'nav-link'); if (key === part) {item.classList.add('active'); item.setAttribute('aria-current', 'page');}
      $('account-navigation').append(item);
    }
    await view.run(() => renderAccount(currentView, part));
  }
}
window.addEventListener('hashchange', () => {if (context) void show(route());});
window.addEventListener('beforeunload', event => {if (view?.dirty.size || view?.busy) {event.preventDefault(); event.returnValue = '';}});

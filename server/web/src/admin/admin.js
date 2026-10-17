import {Api} from '/web/api.js';
import {accountMenu} from '/web/account-menu.js';
import {node,link,button,section} from '/web/account-ui.js';
import {settingsView} from '/web/settings-view.js';
import {renderUsers} from '/web/users.js';

const $=id=>document.getElementById(id);
let session=null,account=null,view=null,generation=0,acceptedHash='';
accountMenu($('account-menu'));
function dispose() {++generation;for(const cleanup of view?.cleanup || []) cleanup();view=null;}
function clear() {dispose();session?.close();session=null;account=null;$('account-content').replaceChildren();$('account-content').setAttribute('aria-busy','false');$('account-navigation').replaceChildren();$('account-navigation').hidden=true;$('account-identity').textContent='';$('users-link').hidden=true;$('account-error').replaceChildren();}
function signedOut(message='Sign in with an administrator account to continue.') {
  clear();$('session').hidden=true;$('status').textContent='Sign-in required.';
  $('account-content').append(node('p',message),link('Sign in to administration','/web/#administration','btn btn-primary'));
}
function forbidden() {
  dispose();$('account-content').replaceChildren(node('p','Administrator access is required to manage this service.','alert alert-warning'));
  $('account-content').setAttribute('aria-busy','false');$('account-navigation').hidden=true;$('users-link').hidden=true;$('status').textContent='Administration unavailable.';
}
function canLeave() {
  if(view?.busy) {$('account-error').textContent='Wait for the current change to finish before navigating.';return false;}
  return !view?.dirty.size || window.confirm('Discard unsaved account changes?');
}
function route() {return location.hash==='#main-content' ? acceptedHash : location.hash;}
async function show(hash,ask=true) {
  if(!session || !account) return;
  if(ask && !canLeave()) {history.pushState(null,'',location.pathname+acceptedHash);return;}
  dispose();acceptedHash=hash;const current=generation,active=session;
  const valid=()=>generation===current && session===active;
  $('account-content').replaceChildren();$('account-error').replaceChildren();$('account-navigation').replaceChildren();
  const users=hash==='#users' || hash.startsWith('#users/');
  for(const [key,title] of [['settings','General settings'],['users','Users']]) {
    const item=link(title,'#'+key,'nav-link');if((users ? 'users':'settings')===key) {item.classList.add('active');item.setAttribute('aria-current','page');}$('account-navigation').append(item);
  }
  $('account-heading').focus();
  view=settingsView({root:$('account-content'),session:active,account,valid,feedback:$('account-error'),
    changed(value) {account={...account,...value};$('owner').textContent=account.displayName;},
    signOut:signedOut,forbidden,navigate:next=>{location.hash=next;},
    reload:()=>{if(acceptedHash==='#users/new') location.hash='#users';else void show(acceptedHash);}
  });
  const currentView=view;
  await view.run(()=>users ? renderUsers(currentView,hash.split('/')[1] || '') : renderSettings(currentView));
  if(valid()) $('status').textContent='Administration ready.';
}
async function renderSettings(view) {
  const {data}=await view.session.request('/admin/settings');if(!view.valid()) return;
  const card=section(view,'General settings');
  card.append(node('p','These settings are managed by the server deployment. Change its configuration and restart the service to apply changes.','hint'));
  const values=[['Public HTTPS address',data.publicOrigin || 'Not configured'],['Authentication configuration epoch',String(data.originEpoch)],
    ['Passkey sign-in',data.passkeysEnabled ? 'Enabled':'Disabled'],['Passkey relying party',data.passkeyRpId || 'Not configured'],
    ['Email recovery',data.emailRecoveryEnabled ? 'Enabled':'Disabled'],['Database',data.databaseType==='postgresql' ? 'PostgreSQL':'H2'],['Database schema',String(data.schemaVersion)]];
  const list=node('dl',undefined,'row mb-0');
  for(const [title,value] of values) {list.append(node('dt',title,'col-sm-5'),node('dd',value,'col-sm-7 text-break'));}
  card.append(list);
}
async function initialize() {
  clear();$('status').textContent='Loading administration…';const current=generation;
  const bootstrap=new Api();
  try {
    const {data}=await bootstrap.request('/auth/session');if(current!==generation) return;
    account=data;session=new Api(null,undefined,data);$('session').hidden=false;$('owner').textContent=account.displayName;
    $('account-identity').textContent=`Signed in as ${account.username || account.displayName}.`;
    if(!account.administrator || !account.permissions?.includes('write')) {forbidden();return;}
    $('users-link').hidden=false;$('account-navigation').hidden=false;await show(route(),false);
  } catch(error) {
    if(current!==generation) return;
    if(error.status===401) signedOut();
    else {$('status').textContent='Administration could not be loaded.';$('account-content').setAttribute('aria-busy','false');$('account-content').append(node('p',error.message,'alert alert-danger'),button('Retry',()=>void initialize()));}
  } finally {bootstrap.close();}
}
$('sign-out').addEventListener('click',()=>{
  if(!session || !canLeave()) return;const active=session,submit=$('sign-out');submit.disabled=true;
  void active.request('/auth/logout',{method:'POST'}).then(()=>{if(session===active) signedOut('You have signed out.');}).catch(error=>{if(session!==active) return;if(error.status===401) signedOut();else $('account-error').textContent='Sign-out could not be confirmed. Please retry while connected.';}).finally(()=>{submit.disabled=false;});
});
window.addEventListener('hashchange',()=>{if(account?.administrator && account.permissions?.includes('write')) void show(route());});
window.addEventListener('beforeunload',event=>{if(view?.dirty.size || view?.busy) {event.preventDefault();event.returnValue='';}});
window.addEventListener('pagehide',()=>{clear();});
window.addEventListener('pageshow',event=>{if(event.persisted) void initialize();});
void initialize();

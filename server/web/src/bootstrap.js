import {setLocale,applyTranslations} from './i18n.js';
import {installTooltips} from './tooltips.js';
installTooltips();
window.addEventListener('timekeeper-theme-options',()=>applyTranslations());
applyTranslations();

// Load the account preference before evaluating UI modules: their static option
// lists and error messages then start in the same language as the document.
try {
  const options=()=>({credentials:'same-origin',cache:'no-store',redirect:'error',referrerPolicy:'origin',headers:{Accept:'application/json'},signal:AbortSignal.timeout(15000)});
  const session=await fetch('/api/v1/auth/session',options());
  if(session.ok) {
    const account = await session.json();setLocale(account.locale);
    // Api builds locale-sensitive sorters when first evaluated.
    const {Api} = await import('./api.js');
    const api = new Api(null, undefined, account);
    try {
      const profile = await api.request('/me');
      window.timekeeperTypography?.connect(account.ownerId);
      window.timekeeperTypography?.receive(profile);
    } finally {api.close();}
  } else if(session.status===401 && document.getElementById('login')) {
    window.timekeeperTypography?.disconnect();
    document.getElementById('startup').hidden=true;document.getElementById('login').hidden=false;
  }
} catch { /* Public sign-in remains available with the browser's language. */ }
applyTranslations();
const entry=document.querySelector('script[data-timekeeper-entry]').dataset.timekeeperEntry;
await import(entry);

// Reports, Administration and the Markdown lab also refresh account sizes when
// returning to the tab. The API pins the owner so a switched/expired session
// clears its old font preference rather than showing another account's sizes.
document.addEventListener('visibilitychange', async () => {
  const preferences = window.timekeeperTypography, owner = preferences?.profile()?.data.ownerId;
  if (document.hidden || !owner) return;
  const {Api} = await import('./api.js');
  const api = new Api(null, undefined, {ownerId: owner});
  try {preferences.receive(await api.request('/me'));}
  catch (error) {if (error.status === 401 && preferences.profile()?.data.ownerId === owner) preferences.disconnect();}
  finally {api.close();}
});

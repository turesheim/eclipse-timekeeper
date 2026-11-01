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
    setLocale((await session.json()).locale);
  } else if(session.status===401 && document.getElementById('login')) {
    document.getElementById('startup').hidden=true;document.getElementById('login').hidden=false;
  }
} catch { /* Public sign-in remains available with the browser's language. */ }
applyTranslations();
const entry=document.querySelector('script[data-timekeeper-entry]').dataset.timekeeperEntry;
await import(entry);

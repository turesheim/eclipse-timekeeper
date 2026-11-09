import {Api} from './api.js';
import {t,setLocale} from './i18n.js';

// Remove the authorization code before making requests or allowing navigation.
const parameters=new URLSearchParams(location.search);history.replaceState(null,'',location.pathname);
const title=document.getElementById('task-provider-title'),result=document.getElementById('task-provider-result'),back=document.getElementById('task-provider-return');
const translate=()=>{title.textContent=t('task_providers.title');back.textContent=t('task_providers.return');document.title=t('task_providers.title')+' · Dagsverk';};
translate();result.textContent=t('task_providers.connecting');
let session=null;
try{
  const state=parameters.get('state'),code=parameters.get('code'),cancelled=parameters.has('error');
  if(!state||!/^[A-Za-z0-9_-]{43}$/.test(state)||parameters.getAll('state').length!==1||parameters.getAll('code').length>1||!cancelled&&(!code||code.length>8192))throw new Error();
  const anonymous=new Api();let restored;
  try{restored=await anonymous.request('/auth/session');}finally{anonymous.close();}
  session=new Api(null,undefined,restored.data);const profile=await session.request('/me');setLocale(profile.data.locale);translate();
  if(cancelled){await session.request('/task-providers/cancel',{method:'POST',body:{state}});result.textContent=t('task_providers.cancelled');}
  else{await session.request('/task-providers/complete',{method:'POST',body:{state,code},timeout:240000});result.textContent=t('task_providers.connected');location.replace('/web/#account/task-providers');}
}catch{result.textContent=t('task_providers.not_confirmed');result.setAttribute('role','alert');}
finally{parameters.delete('code');parameters.delete('state');session?.close();}

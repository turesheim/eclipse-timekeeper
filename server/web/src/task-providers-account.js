import {t} from './i18n.js';
import {ApiError} from './api.js';
import {instantText} from './model.js';
import {node,button,section} from './account-ui.js';

const providerName=id=>t('task_providers.'+id);
const providerError=code=>new ApiError(code).message;
function authorizationTarget(provider,address){
  try{
    const target=new URL(address),paths={google:'/o/oauth2/v2/auth',microsoft:'/common/oauth2/v2.0/authorize'},hosts={google:'accounts.google.com',microsoft:'login.microsoftonline.com'};
    if(target.protocol!=='https:'||target.hostname!==hosts[provider]||target.pathname!==paths[provider]||target.username||target.password||target.port||target.hash)throw new Error();
    return target.href;
  }catch{throw new ApiError('UNAVAILABLE');}
}

/** Account-scoped cloud connections. Polls update status without replacing a list-selection draft. */
export async function renderTaskProviders(view){
  const overview=section(view,t('task_providers.title'));overview.append(node('p',t('task_providers.help'),'hint'),node('p',t('task_providers.direction'),'hint'));
  const providers=node('div',undefined,'d-flex flex-wrap gap-2'),setup=node('p',t('task_providers.not_configured'),'hint'),feedback=node('p','','form-feedback');feedback.setAttribute('role','status');
  const refresh=button(t('task_providers.refresh_status'),()=>void view.run(()=>load(),{submit:refresh,feedback}));
  overview.append(providers,setup,refresh,feedback);const cards=new Map(),connectButtons=new Map();
  let generation=0,timer=null,controller=null,zone='UTC',configured={};
  const controls=()=>{
    for(const [provider,pick]of connectButtons)pick.disabled=!view.canWrite||view.busy||view.uncertain||!configured[provider];
    for(const card of cards.values()){
      for(const input of card.form.querySelectorAll('input'))input.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      card.save.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      card.refresh.disabled=!view.canWrite||view.busy||view.uncertain||card.data.syncing||!card.data.enabled||!configured[card.data.providerId];
      card.disconnect.disabled=!view.canWrite||view.busy||view.uncertain;card.reconnect.disabled=!view.canWrite||view.busy||view.uncertain||!configured[card.data.providerId];
    }
  };
  const run=(work,submit,target=feedback)=>void view.run(async()=>{controls();await work();},{submit,feedback:target,write:true}).finally(()=>{if(view.valid())controls();});
  const connect=provider=>{
    const pick=connectButtons.get(provider);
    run(async()=>{
      if(view.dirty.size&&!window.confirm(t('ui.discard_unsaved_account_changes')))return;
      const reply=await view.session.request('/task-providers/'+provider+'/authorize',{method:'POST',body:{}});if(!view.valid())return;
      const target=authorizationTarget(provider,reply.data.authorizationUrl);view.dirty.clear();location.assign(target);
    },pick);
  };
  for(const provider of ['google','microsoft']){const pick=button(t('task_providers.connect',{provider:providerName(provider)}),()=>connect(provider),'btn btn-primary');pick.dataset.taskProviderConnect=provider;connectButtons.set(provider,pick);providers.append(pick);}
  function cardFor(data){
    const card={data},panel=section(view,t('task_providers.account',{provider:providerName(data.providerId),name:data.name}));card.panel=panel;panel.dataset.taskProviderId=data.id;panel.firstElementChild.classList.add('text-break');
    card.status=node('p','','hint');card.status.setAttribute('role','status');card.error=node('p','','alert alert-warning');card.error.hidden=true;
    card.form=node('form');card.form.addEventListener('change',()=>view.dirty.add(card.form));
    const fields=node('fieldset');fields.append(node('legend',t('task_providers.lists'),'h3'));card.choices=node('div');fields.append(card.choices);card.form.append(fields);
    card.feedback=node('p','','form-feedback');card.feedback.setAttribute('role','status');card.save=node('button',t('ui.save'),'btn btn-primary');card.save.type='submit';card.save.dataset.taskProviderSave=data.id;
    card.form.append(card.feedback,card.save);card.form.addEventListener('submit',event=>{
      event.preventDefault();run(async()=>{
        const selectedLists=[...card.form.querySelectorAll('input:checked')].map(input=>input.value);
        const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
        if(latest.data.version!==card.draftVersion)throw new ApiError('STALE_VERSION',412);
        await view.session.request('/task-providers/'+data.id+'/lists',{method:'PUT',etag:latest.etag,body:{selectedLists}});if(!view.valid())return;
        view.clean(card.form);card.feedback.textContent=t('task_providers.selection_saved');await load();
      },card.save,card.feedback);
    });
    card.refresh=button(t('task_providers.refresh_now'),()=>run(async()=>{await view.session.request('/task-providers/'+data.id+'/refresh',{method:'POST'});if(view.valid())await load();},card.refresh,card.feedback));
    card.reconnect=button(t('task_providers.reconnect'),()=>connect(data.providerId));
    card.disconnect=button(t('task_providers.disconnect'),()=>run(async()=>{
      const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
      await view.session.request('/task-providers/'+data.id,{method:'DELETE',etag:latest.etag});if(!view.valid())return;
      view.clean(card.form);await load();feedback.textContent=t('task_providers.disconnected');
    },card.disconnect,card.feedback),'btn btn-outline-danger');
    const actions=node('div',undefined,'d-flex flex-wrap gap-2 mt-3');actions.append(card.refresh,card.reconnect,card.disconnect);
    panel.append(card.status,card.error,card.form,actions,node('p',t('task_providers.disconnect_help'),'hint mt-2'));cards.set(data.id,card);return card;
  }
  function paint(data){
    const present=new Set();for(const value of data.items){
      if(value.ownerId!==view.session.owner)throw new ApiError('OWNER_CHANGED',401);present.add(value.id);const card=cards.get(value.id)||cardFor(value);card.data=value;
      card.status.textContent=!value.enabled?t('task_providers.disabled'):value.syncing?t('task_providers.syncing'):t('task_providers.last_success',{date:value.lastSuccess?instantText(value.lastSuccess,zone):t('task_providers.never')});
      card.error.hidden=!value.lastError;card.error.textContent=value.lastError?providerError(value.lastError)+(value.pendingCount?' '+t('task_providers.pending',{count:value.pendingCount}):''):'';
      if(!view.dirty.has(card.form)){
        card.draftVersion=value.version;
        const signature=JSON.stringify([value.availableLists,value.selectedLists]);if(signature!==card.signature){
          card.signature=signature;card.choices.replaceChildren();
          for(const list of value.availableLists){const label=node('label',undefined,'d-flex gap-2 align-items-start mb-2'),input=node('input',undefined,'form-check-input');input.type='checkbox';input.name='selectedLists';input.value=list.id;input.checked=value.selectedLists.includes(list.id);label.append(input,node('span',list.name,'text-break'));card.choices.append(label);}
          for(const missing of value.selectedLists.filter(id=>!value.availableLists.some(list=>list.id===id)))card.choices.append(node('p',t('task_providers.list_missing'),'alert alert-warning'));
          if(!value.availableLists.length)card.choices.append(node('p',t('task_providers.no_lists'),'hint'));
        }
      }
    }
    for(const [id,card]of cards)if(!present.has(id)){view.clean(card.form);card.panel.remove();cards.delete(id);}
    configured=data.providers;setup.hidden=Object.values(configured).every(Boolean);controls();
  }
  async function load(){
    const current=++generation;controller?.abort();controller=new AbortController();
    const reply=await view.session.request('/task-providers',{signal:controller.signal});if(!view.valid()||current!==generation)return;
    paint(reply.data);clearTimeout(timer);timer=setTimeout(poll,reply.data.items.some(item=>item.syncing)?2000:30000);
  }
  async function poll(){
    if(!view.valid())return;
    if(view.busy||document.hidden){timer=setTimeout(poll,2000);return;}
    try{await load();}catch(error){if(!view.valid())return;if(error.status===401){view.signOut(error.message);return;}feedback.textContent=error.message;timer=setTimeout(poll,30000);}
  }
  view.cleanup.add(()=>{++generation;clearTimeout(timer);controller?.abort();});
  const profile=await view.session.request('/me');if(!view.valid())return;zone=profile.data.zoneId;await load();
}

import {t} from './i18n.js';
import {ApiError} from './api.js';
import {instantText} from './model.js';
import {node,button,section} from './account-ui.js';
import {taskProviderWrites} from './task-provider-writes.js';
import {taskProviderReviews} from './task-provider-reviews.js';

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
  const accessLabel=node('label',t('task_providers.google_access'),'form-label'),access=node('select',undefined,'form-select');
  for(const direction of ['IMPORT','TWO_WAY'])access.append(new Option(t('task_providers.access_'+direction),direction));accessLabel.append(access);overview.insertBefore(accessLabel,providers);
  let generation=0,timer=null,controller=null,zone='UTC',configured={},projects=[];
  const controls=()=>{
    for(const [provider,pick]of connectButtons)pick.disabled=!view.canWrite||view.busy||view.uncertain||!configured[provider];
    access.disabled=!view.canWrite||view.busy||view.uncertain||!configured.google;
    for(const card of cards.values()){
      for(const input of card.form.querySelectorAll('input,select'))input.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled||input.tagName==='SELECT'&&!card.form.querySelector(`input[value="${CSS.escape(input.dataset.projectFor)}"]`)?.checked;
      card.save.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      card.refresh.disabled=!view.canWrite||view.busy||view.uncertain||card.data.paused||card.data.syncing||!card.data.enabled||!configured[card.data.providerId];
      card.disconnect.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;card.reconnect.disabled=!view.canWrite||view.busy||view.uncertain||!configured[card.data.providerId];
      card.pause.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      for(const input of card.imports.querySelectorAll('button'))input.disabled=view.busy||view.uncertain||card.data.paused;
      if(card.approve)card.approve.disabled=!view.canWrite||view.busy||view.uncertain||card.data.paused;
      if(card.access)card.access.disabled=!view.canWrite||view.busy||view.uncertain||!configured.google;card.writes.controls();card.incoming.controls();
    }
  };
  const run=(work,submit,target=feedback)=>void view.run(async()=>{controls();await work();},{submit,feedback:target,write:true}).finally(()=>{if(view.valid())controls();});
  const connect=(provider,direction=access.value)=>{
    const pick=connectButtons.get(provider);
    run(async()=>{
      if(view.dirty.size&&!window.confirm(t('ui.discard_unsaved_account_changes')))return;
      const reply=await view.session.request('/task-providers/'+provider+'/authorize',{method:'POST',body:provider==='google'?{direction}:{}});if(!view.valid())return;
      const target=authorizationTarget(provider,reply.data.authorizationUrl);view.dirty.clear();location.assign(target);
    },pick);
  };
  for(const provider of ['google','microsoft']){const pick=button(t('task_providers.connect',{provider:providerName(provider)}),()=>connect(provider),'btn btn-primary');pick.dataset.taskProviderConnect=provider;connectButtons.set(provider,pick);providers.append(pick);}
  function cardFor(data){
    const card={data},panel=section(view,t('task_providers.account',{provider:providerName(data.providerId),name:data.name}));card.panel=panel;panel.dataset.taskProviderId=data.id;panel.firstElementChild.classList.add('text-break');
    card.status=node('p','','hint');card.status.setAttribute('role','status');card.direction=node('p','','hint');card.error=node('p','','alert alert-warning');card.error.hidden=true;card.retry=node('p','','hint');card.retry.hidden=true;
    card.form=node('form');card.form.addEventListener('change',()=>{view.dirty.add(card.form);controls();});
    const fields=node('fieldset');fields.append(node('legend',t('task_providers.lists'),'h6 mb-2'));card.choices=node('div');fields.append(card.choices);card.form.append(fields);
    card.feedback=node('p','','form-feedback');card.feedback.setAttribute('role','status');card.save=node('button',t('ui.save'),'btn btn-primary');card.save.type='submit';card.save.dataset.taskProviderSave=data.id;
    card.form.append(card.feedback,card.save);card.form.addEventListener('submit',event=>{
      event.preventDefault();run(async()=>{
        const selectedLists=[...card.form.querySelectorAll('input:checked')].map(input=>input.value);
        const projectMappings=Object.fromEntries([...card.form.querySelectorAll('select')].filter(input=>selectedLists.includes(input.dataset.projectFor)).map(input=>[input.dataset.projectFor,input.value||null]));
        const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
        if((latest.data.configurationVersion??latest.data.version)!==card.draftVersion)throw new ApiError('STALE_VERSION',412);
        await view.session.request('/task-providers/'+data.id+'/lists',{method:'PUT',etag:latest.etag,body:{selectedLists,projectMappings}});if(!view.valid())return;
        view.clean(card.form);card.feedback.textContent=t('task_providers.selection_saved');await load();
      },card.save,card.feedback);
    });
    card.refresh=button(t('task_providers.refresh_now'),()=>run(async()=>{await view.session.request('/task-providers/'+data.id+'/refresh',{method:'POST'});if(view.valid())await load();},card.refresh,card.feedback));
    card.reconnect=button(t('task_providers.reconnect'),()=>connect(data.providerId,card.access?.value||'IMPORT'));
    const accessLabel=node('label',t('task_providers.account_access'),'form-label');
    if(data.providerId==='google'){
      card.access=node('select',undefined,'form-select');for(const direction of ['IMPORT','TWO_WAY'])card.access.append(new Option(t('task_providers.access_'+direction),direction));
      card.access.addEventListener('change',()=>card.accessDirty=true);accessLabel.append(card.access);
    }else accessLabel.hidden=true;
    card.pause=button(t('task_providers.pause'),()=>run(async()=>{
      const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
      await view.session.request('/task-providers/'+data.id+'/state',{method:'PUT',etag:latest.etag,body:{paused:!latest.data.paused}});if(view.valid())await load();
    },card.pause,card.feedback));
    card.disconnect=button(t('task_providers.disconnect'),()=>run(async()=>{
      const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
      await view.session.request('/task-providers/'+data.id,{method:'DELETE',etag:latest.etag});if(!view.valid())return;
      view.clean(card.form);await load();feedback.textContent=t('task_providers.disconnected');
    },card.disconnect,card.feedback));
    card.imports=node('div',undefined,'mt-3');card.reviewPanel=node('div',undefined,'mt-3');card.reviewPanel.hidden=true;
    const incoming=node('div',undefined,'mt-3');card.incoming=taskProviderReviews(view,incoming,{reload:load,controls});
    const writes=node('div',undefined,'mt-3');card.writes=taskProviderWrites(view,writes,{reload:load,controls});
    const actions=node('div',undefined,'d-flex flex-wrap gap-2 mt-3');actions.append(card.refresh,card.pause,card.reconnect,card.disconnect);
    panel.append(card.status,card.direction,card.error,card.retry,card.form,card.imports,card.reviewPanel,incoming,writes,accessLabel,actions,node('p',t('task_providers.disconnect_help'),'hint mt-2'));cards.set(data.id,card);return card;
  }
  function paintImports(card){
    const values=card.data.imports||[],signature=JSON.stringify(values);if(signature===card.importSignature)return;
    card.importSignature=signature;card.imports.replaceChildren();
    for(const value of values){
      const name=card.data.availableLists.find(list=>list.id===value.remoteListId)?.name||value.remoteListId;
      if(value.state!=='READY'){card.imports.append(node('p',t('task_providers.preparing_import',{name,count:value.count}),'hint'));continue;}
      const review=button(t('task_providers.review_import',{name}),()=>void view.run(async()=>{
        const reply=await view.session.request('/task-providers/'+card.data.id+'/imports/'+value.id);if(!view.valid())return;
        card.reviewPanel.replaceChildren();card.reviewPanel.hidden=false;
        const heading=node('h3',t('task_providers.import_preview',{name}),'h5');heading.tabIndex=-1;
        const summary=node('p',t('task_providers.import_counts',{created:reply.data.createCount,existing:reply.data.existingCount,deleted:reply.data.deletedCount}));
        const samples=node('ul');for(const sample of reply.data.samples)samples.append(node('li',sample.deleted?t('task_providers.deleted_item'):sample.title,'text-break'));
        const project=projects.find(project=>project.id===reply.data.projectId);
        const mapping=node('p',t('task_providers.import_project',{name:project?.label||t('task_providers.no_project')}),'hint');
        card.approve=button(t('task_providers.approve_import'),()=>run(async()=>{
          await view.session.request('/task-providers/'+card.data.id+'/imports/'+value.id,{method:'POST',etag:reply.etag});if(!view.valid())return;
          card.reviewPanel.hidden=true;card.reviewPanel.replaceChildren();card.approve=null;await load();card.panel.firstElementChild.tabIndex=-1;card.panel.firstElementChild.focus();
        },card.approve,card.feedback),'btn btn-primary');
        const close=button(t('ui.cancel'),()=>{card.reviewPanel.hidden=true;card.reviewPanel.replaceChildren();card.approve=null;review.focus();});
        const actions=node('div',undefined,'d-flex flex-wrap gap-2');actions.append(card.approve,close);
        card.reviewPanel.append(heading,summary,mapping,samples,node('p',t('task_providers.import_preview_help'),'hint'),actions);controls();heading.focus();
      },{submit:review,feedback:card.feedback}));card.imports.append(review);
    }
  }
  function paint(data){
    const present=new Set();for(const value of data.items){
      if(value.ownerId!==view.session.owner)throw new ApiError('OWNER_CHANGED',401);present.add(value.id);const card=cards.get(value.id)||cardFor(value);card.data=value;
      card.status.textContent=!value.enabled?t('task_providers.disabled'):value.paused?t('task_providers.paused'):value.syncing?t('task_providers.syncing'):t('task_providers.last_success',{date:value.lastSuccess?instantText(value.lastSuccess,zone):t('task_providers.never')});
      card.direction.textContent=t(value.direction==='TWO_WAY'?'task_providers.mode_two_way':'task_providers.mode_import');card.pause.textContent=t(value.paused?'task_providers.resume':'task_providers.pause');paintImports(card);
      if(card.access&&!card.accessDirty)card.access.value=value.direction;card.writes.update(value);card.incoming.update(value);
      card.error.hidden=!value.lastError;card.error.textContent=value.lastError?providerError(value.lastError)+(value.pendingCount?' '+t('task_providers.pending',{count:value.pendingCount}):''):'';
      card.retry.hidden=!value.lastError||!value.nextRefresh;card.retry.textContent=card.retry.hidden?'':t('task_providers.next_attempt',{date:instantText(value.nextRefresh,zone)});
      if(!view.dirty.has(card.form)){
        card.draftVersion=value.configurationVersion??value.version;
        const signature=JSON.stringify([value.availableLists,value.selectedLists,value.projectMappings]);if(signature!==card.signature){
          card.signature=signature;card.choices.replaceChildren();
          for(const list of value.availableLists){
            const row=node('div',undefined,'mb-3'),label=node('label',undefined,'d-flex gap-2 align-items-start mb-2'),input=node('input',undefined,'form-check-input');input.type='checkbox';input.name='selectedLists';input.value=list.id;input.checked=value.selectedLists.includes(list.id);label.append(input,node('span',list.name,'text-break'));
            const projectLabel=node('label',undefined,'d-block'),select=node('select',undefined,'form-select w-100');select.dataset.projectFor=list.id;select.append(new Option(t('task_providers.no_project'),''));
            for(const project of projects)select.append(new Option(project.label,project.id));select.value=value.projectMappings?.[list.id]||'';
            if(value.projectMappings?.[list.id]&&!select.value){select.append(new Option(t('task_providers.project_missing'),value.projectMappings[list.id]));select.value=value.projectMappings[list.id];}
            projectLabel.append(node('span',t('task_providers.project_for',{name:list.name}),'form-label'),select);row.append(label,projectLabel);card.choices.append(row);
          }
          for(const missing of value.selectedLists.filter(id=>!value.availableLists.some(list=>list.id===id)))card.choices.append(node('p',t('task_providers.list_missing'),'alert alert-warning'));
          if(!value.availableLists.length)card.choices.append(node('p',t('task_providers.no_lists'),'hint'));
        }
      }
    }
    for(const [id,card]of cards)if(!present.has(id)){view.clean(card.form);card.writes.dispose();card.incoming.dispose();card.panel.remove();cards.delete(id);}
    configured=data.providers;setup.hidden=Object.values(configured).every(Boolean);controls();
  }
  async function load(){
    const current=++generation;controller?.abort();controller=new AbortController();
    const reply=await view.session.request('/task-providers',{signal:controller.signal});if(!view.valid()||current!==generation)return;
    paint(reply.data);await Promise.all([...cards.values()].map(card=>card.writes.refresh()));if(!view.valid()||current!==generation)return;
    clearTimeout(timer);timer=setTimeout(poll,reply.data.items.some(item=>item.syncing)?2000:30000);
  }
  async function poll(){
    if(!view.valid())return;
    if(view.busy||document.hidden){timer=setTimeout(poll,2000);return;}
    try{await load();}catch(error){if(!view.valid())return;if(error.status===401){view.signOut(error.message);return;}feedback.textContent=error.message;timer=setTimeout(poll,30000);}
  }
  view.cleanup.add(()=>{++generation;clearTimeout(timer);controller?.abort();for(const card of cards.values()){card.writes.dispose();card.incoming.dispose();}});
  const [profile,projectPage,clientPage]=await Promise.all([view.session.request('/me'),view.session.page('/projects'),view.session.page('/clients')]);if(!view.valid())return;
  zone=profile.data.zoneId;projects=projectPage.items.map(project=>({...project,label:[clientPage.items.find(client=>client.id===project.clientId)?.name,project.name].filter(Boolean).join(' / ')}));await load();
}

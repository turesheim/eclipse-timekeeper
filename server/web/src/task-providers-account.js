import {t,getLocale} from './i18n.js';
import {ApiError} from './api.js';
import {authorizationTarget} from './task-provider-authorization.js';
import {instantText} from './model.js';
import {node,button,link,section} from './account-ui.js';
import {taskProviderWrites} from './task-provider-writes.js';
import {taskProviderReviews} from './task-provider-reviews.js';
import {taskProviderIdentities} from './task-provider-identities.js';
import {internetCalendarAccount} from './internet-calendar-account.js';

const providerName=id=>t('internet_accounts.'+id);
const providerError=code=>new ApiError(code).message;


/** Account-scoped cloud connections. Polls update status without replacing a list-selection draft. */
export async function renderTaskProviders(view){
  const feedback=node('p','','form-feedback');feedback.setAttribute('role','status');view.root.append(feedback);
  const cards=new Map(),connectButtons=new Map(),providerPanels=new Map(),providerServices=new Map(),providerFeedback=new Map(),providerStatus=new Map(),providerConnect=new Map();
  const accessLabel=node('label',t('task_providers.google_access'),'form-label'),access=node('select',undefined,'form-select');
  for(const direction of ['IMPORT','TWO_WAY'])access.append(new Option(t('task_providers.access_'+direction),direction));accessLabel.append(access);
  let generation=0,timer=null,controller=null,zone='UTC',configured={},projects=[],clients=[],calendarSynchronization=true;
  function serviceChoices(initial={tasks:true,calendars:true}){
    const fieldset=node('fieldset',undefined,'mb-3');fieldset.append(node('legend',t('internet_accounts.choose_services'),'h6'));const choices={};
    for(const key of ['calendars','tasks']){const label=node('label',undefined,'form-check'),input=node('input',undefined,'form-check-input');input.type='checkbox';input.checked=!!initial[key];label.append(input,node('span',t('internet_accounts.'+key),'form-check-label'));fieldset.append(label);choices[key]=input;}
    return {fieldset,choices,read:()=>Object.fromEntries(Object.entries(choices).map(([key,input])=>[key,input.checked])),controls:()=>{for(const [key,input]of Object.entries(choices)){const available=key!=='calendars'||calendarSynchronization;input.closest('label').hidden=!available;if(!available)input.checked=false;input.disabled=!view.canWrite||view.busy||view.uncertain||!available;}}};
  }
  const controls=()=>{
    for(const [provider,pick]of connectButtons){const services=providerServices.get(provider);services.controls();pick.disabled=!configured[provider]||!view.canWrite||view.busy||view.uncertain||!Object.values(services.read()).some(Boolean);}
    access.disabled=!view.canWrite||view.busy||view.uncertain||!providerServices.get('google')?.choices.tasks.checked;
    for(const card of cards.values()){
      card.services.controls();
      for(const input of card.form.querySelectorAll('input,select'))input.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled||input.tagName==='SELECT'&&!card.form.querySelector(`input[name=selectedLists][value="${CSS.escape(input.dataset.projectFor)}"]`)?.checked;
      card.additions.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      for(const select of card.form.querySelectorAll('[data-project-for]')){const checked=!!card.form.querySelector(`input[name=selectedLists][value="${CSS.escape(select.dataset.projectFor)}"]`)?.checked;select.closest('label').hidden=!checked;select.closest('.internet-selection-row').classList.toggle('is-selected',checked);}
      card.save.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      card.refresh.disabled=!view.canWrite||view.busy||view.uncertain||card.data.paused||card.data.syncing||!card.data.enabled;
      card.disconnect.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;card.reconnect.disabled=!configured[card.data.providerId]||!view.canWrite||view.busy||view.uncertain||!Object.values(card.services.read()).some(Boolean);
      card.pause.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      for(const input of card.imports.querySelectorAll('button'))input.disabled=view.busy||view.uncertain||card.data.paused;
      if(card.approve)card.approve.disabled=!view.canWrite||view.busy||view.uncertain||card.data.paused;
      if(card.access)card.access.disabled=!view.canWrite||view.busy||view.uncertain||!card.services.choices.tasks.checked;card.calendars.controls();card.writes.controls();card.incoming.controls();card.identities.controls();
    }
  };
  const run=(work,submit,target=feedback,authorization=false)=>void view.run(async()=>{controls();await work();},{submit,feedback:target,write:true,authorization}).finally(()=>{if(view.valid())controls();});
  const connect=(provider,direction=access.value,services=providerServices.get(provider).read(),targetFeedback=providerFeedback.get(provider))=>{
    const pick=connectButtons.get(provider);
    run(async()=>{
      if(view.dirty.size&&!window.confirm(t('ui.discard_unsaved_account_changes')))return;
      targetFeedback.textContent=t('internet_accounts.opening_sign_in');
      let reply;try{reply=await view.session.request('/task-providers/'+provider+'/authorize',{method:'POST',timeout:60000,body:{services,language:getLocale().startsWith('nb')?'nb':'en',...(provider==='google'?{direction:services.tasks?direction:'IMPORT'}:{})}});}catch(error){if(!error.status||error.code==='TASK_PROVIDER_UNAVAILABLE'||error.code==='UNAVAILABLE')throw new ApiError('TASK_PROVIDER_CONNECTION_UNAVAILABLE',error.status);throw error;}if(!view.valid())return;
      const target=authorizationTarget(provider,reply.data.authorizationUrl,reply.data.authorizationOrigin);view.dirty.clear();location.assign(target);
    },pick,targetFeedback,true);
  };
  for(const provider of ['google','microsoft']){
    const panel=section(view,t('internet_accounts.'+provider));panel.dataset.internetProvider=provider;providerPanels.set(provider,panel);
    const connectDetails=node('details',undefined,'internet-provider-connect'),connectSummary=node('summary',t('internet_accounts.add_account',{provider:providerName(provider)}));connectDetails.dataset.providerConnect=provider;connectDetails.append(connectSummary,node('p',t(provider==='microsoft'?'internet_accounts.microsoft_help':'internet_accounts.provider_help'),'hint'));providerConnect.set(provider,connectDetails);panel.append(connectDetails);
    const readiness=node('p','','hint');readiness.setAttribute('role','status');providerStatus.set(provider,readiness);connectDetails.append(readiness);
    const status=node('p','','form-feedback');status.setAttribute('role','status');providerFeedback.set(provider,status);
    const refresh=button(t('internet_accounts.refresh_provider',{provider:providerName(provider)}),()=>void view.run(async()=>{
      status.textContent=t('internet_accounts.refreshing_status');await load(true);if(view.valid())status.textContent=t('internet_accounts.status_refreshed');
    },{submit:refresh,feedback:status}));connectDetails.append(refresh);
    const services=serviceChoices();services.fieldset.addEventListener('change',controls);providerServices.set(provider,services);connectDetails.append(services.fieldset);
    if(provider==='google')connectDetails.append(accessLabel);
    const pick=button(t('task_providers.connect',{provider:providerName(provider)}),()=>connect(provider),'btn btn-primary');pick.dataset.taskProviderConnect=provider;connectButtons.set(provider,pick);connectDetails.append(pick);
    connectDetails.append(status);
  }
  const subscriptions=section(view,t('internet_accounts.subscription_title'));subscriptions.append(node('p',t('internet_accounts.subscription_help'),'hint'),link(t('internet_accounts.subscription_action'),'#account/calendars'));
  function cardFor(data){
    const card={data},panel=section(view,t('task_providers.account',{provider:providerName(data.providerId),name:data.name}));card.panel=panel;panel.dataset.taskProviderId=data.id;card.heading=node('h3',t('task_providers.account',{provider:providerName(data.providerId),name:data.name}),'h5 text-break mb-1');card.heading.id='internet-account-'+data.id;panel.firstElementChild.replaceWith(card.heading);panel.classList.remove('card','account-card');panel.classList.add('internet-connection');panel.setAttribute('aria-labelledby',card.heading.id);providerPanels.get(data.providerId)?.append(panel);
    card.status=node('p','','hint small mb-0');card.status.setAttribute('role','status');card.direction=node('p','','hint');card.error=node('p','','alert alert-warning');card.error.hidden=true;card.retry=node('p','','hint');card.retry.hidden=true;
    card.form=node('form');card.form.addEventListener('change',()=>{view.dirty.add(card.form);controls();});
    const additions=node('label',undefined,'d-flex gap-2 align-items-start mb-3');card.additions=node('input',undefined,'form-check-input');card.additions.type='checkbox';card.additions.checked=data.reviewAdditions!==false;additions.hidden=data.providerId!=='google';additions.append(card.additions,node('span',t('task_identities.review_additions')));card.additionsLabel=additions;
    const fields=node('div',undefined,'internet-selection-list');fields.setAttribute('role','group');fields.setAttribute('aria-label',t('task_providers.lists'));card.choices=node('div');fields.append(card.choices);card.form.append(fields,additions);
    card.feedback=node('p','','form-feedback');card.feedback.setAttribute('role','status');card.save=node('button',t('task_providers.save_lists'),'btn btn-primary');card.save.type='submit';card.save.dataset.taskProviderSave=data.id;
    card.form.append(card.save);card.form.addEventListener('submit',event=>{
      event.preventDefault();run(async()=>{
        const selectedLists=[...card.form.querySelectorAll('input[name=selectedLists]:checked')].map(input=>input.value);
        const projectMappings=Object.fromEntries([...card.form.querySelectorAll('select')].filter(input=>selectedLists.includes(input.dataset.projectFor)).map(input=>[input.dataset.projectFor,input.value||null]));
        const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
        if((latest.data.configurationVersion??latest.data.version)!==card.draftVersion)throw new ApiError('STALE_VERSION',412);
        await view.session.request('/task-providers/'+data.id+'/lists',{method:'PUT',etag:latest.etag,body:{selectedLists,projectMappings,...(data.providerId==='google'?{reviewAdditions:card.additions.checked}:{})}});if(!view.valid())return;
        view.clean(card.form);card.feedback.textContent=t('task_providers.selection_saved');await load();
      },card.save,card.feedback);
    });
    card.refresh=button(t('task_providers.refresh_now'),()=>run(async()=>{await view.session.request('/task-providers/'+data.id+'/refresh',{method:'POST'});if(view.valid())await load();},card.refresh,card.feedback));
    card.services=serviceChoices(data.services||{tasks:true,calendars:false});card.services.fieldset.addEventListener('change',()=>{card.servicesDirty=true;controls();});
    card.reconnect=button(t('task_providers.reconnect'),()=>connect(data.providerId,card.access?.value||'IMPORT',card.services.read(),card.feedback));
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
    const identities=node('div',undefined,'mt-3');card.identities=taskProviderIdentities(view,identities,{reload:load,controls,projects});
    const incoming=node('div',undefined,'mt-3');card.incoming=taskProviderReviews(view,incoming,{reload:load,controls});
    const writes=node('div',undefined,'mt-3');card.writes=taskProviderWrites(view,writes,{reload:load,controls});
    const header=node('div',undefined,'internet-connection-header'),identity=node('div');identity.append(card.heading,card.status);header.append(identity,card.refresh);
    const columns=node('div',undefined,'internet-connection-columns');card.calendars=internetCalendarAccount(view,columns,{reload:load,clients,zone:()=>zone,controls});
    card.taskSection=node('section',undefined,'internet-connection-section');card.taskHeading=node('h4',t('internet_accounts.tasks'),'h5');card.taskHeading.id='internet-tasks-'+data.id;card.taskSection.setAttribute('aria-labelledby',card.taskHeading.id);
    card.reviewRoots=[identities,incoming,writes];card.taskSection.append(card.taskHeading,card.direction,card.form,card.imports,card.reviewPanel,identities,incoming,writes);columns.append(card.taskSection);
    const settings=node('details',undefined,'internet-connection-settings');settings.dataset.connectionSettings=data.id;settings.append(node('summary',t('internet_accounts.connection_settings')),card.services.fieldset,accessLabel,node('p',t('internet_accounts.reconnect_help'),'hint'));
    const actions=node('div',undefined,'d-flex flex-wrap gap-2 mt-3');actions.append(card.pause,card.reconnect,card.disconnect);settings.append(actions,node('p',t('task_providers.disconnect_help'),'hint mt-2'));
    panel.append(header,card.feedback,card.error,card.retry,columns,settings);cards.set(data.id,card);return card;
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
          card.reviewPanel.hidden=true;card.reviewPanel.replaceChildren();card.approve=null;await load();card.heading.tabIndex=-1;card.heading.focus();
        },card.approve,card.feedback),'btn btn-primary');
        const close=button(t('ui.cancel'),()=>{card.reviewPanel.hidden=true;card.reviewPanel.replaceChildren();card.approve=null;review.focus();});
        const actions=node('div',undefined,'d-flex flex-wrap gap-2');actions.append(card.approve,close);
        card.reviewPanel.append(heading,summary,mapping,samples,node('p',t('task_providers.import_preview_help'),'hint'),actions);controls();heading.focus();
      },{submit:review,feedback:card.feedback}));card.imports.append(review);
    }
  }
  function paint(data){
    calendarSynchronization=data.calendarSynchronization!==false;
    for(const [provider,status]of providerStatus){const ready=!!data.providers[provider];status.textContent=ready?t('internet_accounts.ready'):providerError(data.readiness?.[provider]||'TASK_PROVIDER_NOT_CONFIGURED');status.className=ready?'hint':'alert alert-warning';}
    const present=new Set();for(const value of data.items){
      if(value.ownerId!==view.session.owner)throw new ApiError('OWNER_CHANGED',401);present.add(value.id);const card=cards.get(value.id)||cardFor(value);card.data=value;
      card.status.textContent=!value.enabled?t('task_providers.disabled'):value.paused?t('task_providers.paused'):value.syncing?t('task_providers.syncing'):t('internet_accounts.tasks_last_refresh',{date:value.lastSuccess?instantText(value.lastSuccess,zone):t('task_providers.never')});
      card.direction.textContent=t(value.direction==='TWO_WAY'?'task_providers.mode_two_way':'task_providers.mode_import');card.pause.textContent=t(value.paused?'task_providers.resume':'task_providers.pause');paintImports(card);
      const tasks=value.services?.tasks!==false;card.status.hidden=!tasks&&value.enabled&&!value.paused&&!value.syncing;card.form.hidden=!tasks;card.additionsLabel.hidden=value.providerId!=='google'||!tasks;card.direction.hidden=!tasks;card.imports.hidden=!tasks;card.calendars.update(value);
      if(!card.servicesDirty)for(const [key,input]of Object.entries(card.services.choices))input.checked=value.services?!!value.services[key]:key==='tasks';
      if(card.access&&!card.accessDirty)card.access.value=value.direction;card.writes.update(value);card.incoming.update(value);card.identities.update(value);card.taskSection.hidden=!tasks&&!card.reviewRoots.some(root=>!root.hidden)&&card.reviewPanel.hidden;
      card.error.hidden=!value.lastError;card.error.textContent=value.lastError?providerError(value.lastError)+(value.pendingCount?' '+t('task_providers.pending',{count:value.pendingCount}):''):'';
      card.retry.hidden=!value.lastError||!value.nextRefresh;card.retry.textContent=card.retry.hidden?'':t('task_providers.next_attempt',{date:instantText(value.nextRefresh,zone)});
      if(!view.dirty.has(card.form)){
        card.draftVersion=value.configurationVersion??value.version;card.additions.checked=value.reviewAdditions!==false;
        const signature=JSON.stringify([value.availableLists,value.selectedLists,value.projectMappings]);if(signature!==card.signature){
          card.signature=signature;card.choices.replaceChildren();
          for(const list of value.availableLists){
            const row=node('div',undefined,'internet-selection-row'),label=node('label',undefined,'internet-selection-check'),input=node('input',undefined,'form-check-input');input.type='checkbox';input.name='selectedLists';input.value=list.id;input.checked=value.selectedLists.includes(list.id);label.append(input,node('span',list.name,'text-break'));
            const projectLabel=node('label',undefined,'internet-selection-mapping'),select=node('select',undefined,'form-select form-select-sm');select.dataset.projectFor=list.id;select.append(new Option(t('task_providers.no_project'),''));
            for(const project of projects)select.append(new Option(project.label,project.id));select.value=value.projectMappings?.[list.id]||'';
            if(value.projectMappings?.[list.id]&&!select.value){select.append(new Option(t('task_providers.project_missing'),value.projectMappings[list.id]));select.value=value.projectMappings[list.id];}
            const visible=node('span',t('internet_accounts.project_mapping'),'form-label');visible.setAttribute('aria-hidden','true');projectLabel.append(visible,node('span',t('task_providers.project_for',{name:list.name}),'visually-hidden'),select);row.append(label,projectLabel);card.choices.append(row);
          }
          for(const missing of value.selectedLists.filter(id=>!value.availableLists.some(list=>list.id===id)))card.choices.append(node('p',t('task_providers.list_missing'),'alert alert-warning'));
          if(!value.availableLists.length)card.choices.append(node('p',t('task_providers.no_lists'),'hint'));
        }
      }
    }
    for(const [id,card]of cards)if(!present.has(id)){view.clean(card.form);card.calendars.dispose();card.writes.dispose();card.incoming.dispose();card.identities.dispose();card.panel.remove();cards.delete(id);}
    configured=data.providers;for(const [provider,details]of providerConnect)if(!details.dataset.initialized){details.open=!!configured[provider]&&!data.items.some(item=>item.providerId===provider&&item.enabled);details.dataset.initialized='true';}controls();
  }
  async function load(check=false){
    const current=++generation;controller?.abort();controller=new AbortController();
    const reply=await view.session.request('/task-providers'+(check?'?check=true':''),{signal:controller.signal,timeout:20000});if(!view.valid()||current!==generation)return;
    paint(reply.data);await Promise.all([...cards.values()].map(card=>card.writes.refresh()));if(!view.valid()||current!==generation)return;
    clearTimeout(timer);timer=setTimeout(poll,reply.data.items.some(item=>item.syncing)?2000:30000);
  }
  async function poll(){
    if(!view.valid())return;
    if(view.busy||document.hidden){timer=setTimeout(poll,2000);return;}
    try{await load();}catch(error){if(!view.valid())return;if(error.status===401){view.signOut(error.message);return;}feedback.textContent=error.message;timer=setTimeout(poll,30000);}
  }
  view.cleanup.add(()=>{++generation;clearTimeout(timer);controller?.abort();for(const card of cards.values()){card.calendars.dispose();card.writes.dispose();card.incoming.dispose();card.identities.dispose();}});
  const [profile,projectPage,clientPage]=await Promise.all([view.session.request('/me'),view.session.page('/projects'),view.session.page('/clients')]);if(!view.valid())return;
  zone=profile.data.zoneId;clients=clientPage.items;projects=projectPage.items.map(project=>({...project,label:[clientPage.items.find(client=>client.id===project.clientId)?.name,project.name].filter(Boolean).join(' / ')}));await load();
}

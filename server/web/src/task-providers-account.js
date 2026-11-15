import {t,getLocale} from './i18n.js';
import {ApiError} from './api.js';
import {authorizationTarget} from './task-provider-authorization.js';
import {instantText} from './model.js';
import {icon} from './icons.js';
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
  const cards=new Map(),connectButtons=new Map(),providerPanels=new Map(),providerServices=new Map(),providerFeedback=new Map(),providerStatus=new Map();
  const toolbar=node('div',undefined,'internet-accounts-toolbar'),accounts=node('div',undefined,'internet-account-list'),empty=node('p',t('internet_accounts.no_accounts'),'hint');
  const chooser=node('dialog',undefined,'app-dialog internet-account-chooser'),chooserTitle=node('h2',t('internet_accounts.add')),choices=node('div',undefined,'internet-provider-choices');chooserTitle.id='internet-account-chooser-title';chooser.setAttribute('aria-labelledby',chooserTitle.id);
  const back=button(t('internet_accounts.back'),()=>choose(null)),cancel=button(t('ui.cancel'),()=>chooser.close()),chooserActions=node('div',undefined,'dialog-actions');chooserActions.append(back,cancel);back.hidden=true;
  const add=button(t('internet_accounts.add'),()=>{if(!view.valid()||view.busy||document.querySelector('dialog[open]'))return;choose(null);chooser.showModal();choices.querySelector('button')?.focus();},'btn btn-primary');add.prepend(icon('add'),' ');add.disabled=true;
  toolbar.append(node('h2',t('task_providers.title'),'h4 mb-0'),add);view.root.append(toolbar,empty,accounts,chooser);chooser.append(chooserTitle,choices,chooserActions);
  let authorizationController=null,restoreChooserFocus=false;
  function choose(provider){choices.hidden=!!provider;back.hidden=!provider;for(const [id,panel]of providerPanels)panel.hidden=id!==provider;if(provider)providerPanels.get(provider).querySelector('h3')?.focus();else if(chooser.open)choices.querySelector('button')?.focus();}
  chooser.addEventListener('close',()=>{authorizationController?.abort();for(const status of providerFeedback.values())status.textContent='';if(view.valid()){restoreChooserFocus=view.busy;if(!restoreChooserFocus)add.focus();}});
  const accessLabel=node('label',t('task_providers.google_access'),'form-label'),access=node('select',undefined,'form-select');
  for(const direction of ['TWO_WAY','IMPORT'])access.append(new Option(t('task_providers.access_'+direction),direction));accessLabel.append(access);
  let generation=0,timer=null,controller=null,zone='UTC',configured={},projects=[],clients=[],calendarSynchronization=true;
  function serviceChoices(initial={tasks:true,calendars:true},switches=false){
    const fieldset=node('fieldset',undefined,switches?'internet-service-list':'mb-3');fieldset.append(node('legend',t('internet_accounts.choose_services'),switches?'visually-hidden':'h6'));const choices={};
    for(const key of ['calendars','tasks']){const label=node('label',undefined,switches?'form-check form-switch':'form-check'),input=node('input',undefined,'form-check-input');input.type='checkbox';if(switches)input.setAttribute('role','switch');input.checked=!!initial[key];label.append(input,node('span',t('internet_accounts.'+key),'form-check-label'));fieldset.append(label);choices[key]=input;}
    return {fieldset,choices,read:()=>Object.fromEntries(Object.entries(choices).map(([key,input])=>[key,input.checked])),controls:()=>{for(const [key,input]of Object.entries(choices)){const available=key!=='calendars'||calendarSynchronization;input.closest('label').hidden=!available;if(!available)input.checked=false;input.disabled=!view.canWrite||view.busy||view.uncertain||!available;}}};
  }
  const controls=()=>{
    add.disabled=!view.canWrite||view.busy||view.uncertain;back.disabled=view.busy;
    for(const [provider,pick]of connectButtons){const services=providerServices.get(provider);services.controls();pick.disabled=!configured[provider]||!view.canWrite||view.busy||view.uncertain||!Object.values(services.read()).some(Boolean);}
    access.disabled=!view.canWrite||view.busy||view.uncertain||!providerServices.get('google')?.choices.tasks.checked;
    for(const card of cards.values()){
      card.services.controls();
      for(const input of card.form.querySelectorAll('input,select'))input.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled||input.tagName==='SELECT'&&!card.form.querySelector(`input[name=selectedLists][value="${CSS.escape(input.dataset.projectFor)}"]`)?.checked;
      for(const select of card.form.querySelectorAll('[data-project-for]')){const checked=!!card.form.querySelector(`input[name=selectedLists][value="${CSS.escape(select.dataset.projectFor)}"]`)?.checked;select.closest('label').hidden=!checked;select.closest('.internet-selection-row').classList.toggle('is-selected',checked);}
      card.save.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      card.refresh.disabled=!view.canWrite||view.busy||view.uncertain||card.data.paused||card.data.syncing||!card.data.enabled;
      card.disconnect.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;card.reconnect.disabled=!configured[card.data.providerId]||!view.canWrite||view.busy||view.uncertain||!Object.values(card.services.read()).some(Boolean);
      card.pause.disabled=!view.canWrite||view.busy||view.uncertain||!card.data.enabled;
      const selection=card.services.read(),changed=card.servicesDirty&&Object.entries(selection).some(([key,value])=>value!==(card.data.services?!!card.data.services[key]:key==='tasks'))||card.accessDirty&&card.access.value!==card.data.direction;
      card.serviceChanges.hidden=!changed;card.serviceApply.disabled=card.reconnect.disabled;card.serviceHint.textContent=t(Object.values(selection).some(Boolean)?'internet_accounts.reconnect_help':'internet_accounts.choose_service_or_disconnect');
      if(card.access)card.access.disabled=!view.canWrite||view.busy||view.uncertain||!card.services.choices.tasks.checked;card.calendars.controls();card.writes.controls();card.incoming.controls();card.identities.controls();
    }
  };
  const run=(work,submit,target=feedback,authorization=false)=>void view.run(async()=>{controls();await work();},{submit,feedback:target,write:true,authorization}).finally(()=>{if(view.valid()){controls();if(!chooser.open&&(restoreChooserFocus||document.activeElement===document.body||chooser.contains(document.activeElement))){restoreChooserFocus=false;add.focus();}}});
  const connect=(provider,direction=provider==='google'?access.value:'TWO_WAY',services=providerServices.get(provider).read(),targetFeedback=providerFeedback.get(provider))=>{
    const pick=connectButtons.get(provider);
    run(async()=>{
      if(view.dirty.size&&!window.confirm(t('ui.discard_unsaved_account_changes')))return;
      targetFeedback.textContent=t('internet_accounts.opening_sign_in');
      const signal=chooser.open?(authorizationController=new AbortController()).signal:undefined;
      let reply;try{reply=await view.session.request('/task-providers/'+provider+'/authorize',{method:'POST',timeout:60000,signal,body:{services,language:getLocale().startsWith('nb')?'nb':'en',direction:services.tasks?direction:'IMPORT'}});}catch(error){if(signal?.aborted)return;if(!error.status||error.code==='TASK_PROVIDER_UNAVAILABLE'||error.code==='UNAVAILABLE')throw new ApiError('TASK_PROVIDER_CONNECTION_UNAVAILABLE',error.status);throw error;}finally{if(authorizationController?.signal===signal)authorizationController=null;}if(!view.valid()||signal?.aborted)return;
      const target=authorizationTarget(provider,reply.data.authorizationUrl,reply.data.authorizationOrigin);view.dirty.clear();location.assign(target);
    },pick,targetFeedback,true);
  };
  for(const provider of ['google','microsoft']){
    const pickProvider=button('',()=>choose(provider),'btn internet-provider-choice');pickProvider.append(icon(provider),node('span',providerName(provider)),icon('right'));pickProvider.dataset.chooseProvider=provider;choices.append(pickProvider);
    const panel=node('section');panel.hidden=true;panel.dataset.internetProvider=provider;providerPanels.set(provider,panel);chooser.insertBefore(panel,chooserActions);
    const heading=node('h3',providerName(provider),'h5');heading.tabIndex=-1;panel.append(heading,node('p',t(provider==='microsoft'?'internet_accounts.microsoft_help':'internet_accounts.provider_help'),'hint'));
    const connectDetails=node('div');connectDetails.dataset.providerConnect=provider;panel.append(connectDetails);
    const readiness=node('p','','hint');readiness.setAttribute('role','status');providerStatus.set(provider,readiness);connectDetails.append(readiness);
    const status=node('p','','form-feedback');status.setAttribute('role','status');providerFeedback.set(provider,status);
    const refresh=button(t('internet_accounts.refresh_provider',{provider:providerName(provider)}),()=>void view.run(async()=>{
      status.textContent=t('internet_accounts.refreshing_status');await load(true);if(view.valid())status.textContent=t('internet_accounts.status_refreshed');
    },{submit:refresh,feedback:status}));connectDetails.append(refresh);
    const services=serviceChoices();services.fieldset.addEventListener('change',controls);providerServices.set(provider,services);connectDetails.append(services.fieldset);
    if(provider==='google')connectDetails.append(accessLabel);
    const pick=button(t('task_providers.connect',{provider:providerName(provider)}),()=>connect(provider),'btn btn-primary');pick.dataset.taskProviderConnect=provider;connectButtons.set(provider,pick);const diagnostics=node('details',undefined,'internet-provider-status');diagnostics.dataset.providerStatus=provider;diagnostics.append(node('summary',t('internet_accounts.connection_status')),readiness,refresh);connectDetails.append(pick,status,diagnostics);
  }
  const feed=link('', '#account/calendars','btn internet-provider-choice');feed.append(icon('calendar'),node('span',t('internet_accounts.subscription_title')),icon('right'));feed.addEventListener('click',()=>chooser.close());choices.append(feed);
  function cardFor(data){
    const card={data},panel=section(view,t('task_providers.account',{provider:providerName(data.providerId),name:data.name}));card.panel=panel;panel.dataset.taskProviderId=data.id;card.heading=node('h3',t('task_providers.account',{provider:providerName(data.providerId),name:data.name}),'h5 text-break mb-1');card.heading.id='internet-account-'+data.id;panel.firstElementChild.replaceWith(card.heading);panel.classList.remove('account-card');panel.classList.add('internet-connection','internet-account-item');panel.setAttribute('aria-labelledby',card.heading.id);accounts.append(panel);
    card.status=node('p','','hint small mb-0');card.status.setAttribute('role','status');card.direction=node('p','','hint');card.error=node('div','','alert alert-warning');card.error.setAttribute('role','alert');card.error.hidden=true;card.retry=node('p','','hint');card.retry.hidden=true;
    card.form=node('form');card.form.addEventListener('change',()=>{view.dirty.add(card.form);controls();});
    const fields=node('div',undefined,'internet-selection-list');fields.setAttribute('role','group');fields.setAttribute('aria-label',t('task_providers.lists'));card.choices=node('div');fields.append(card.choices);card.form.append(fields);
    card.feedback=node('p','','form-feedback');card.feedback.setAttribute('role','status');card.save=node('button',t('task_providers.save_lists'),'btn btn-primary');card.save.type='submit';card.save.dataset.taskProviderSave=data.id;
    card.save.textContent=t('ui.save');card.save.type='button';
    const saveSelections=()=>run(async()=>{
      const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
      const settings={};
      if(card.data.services?.tasks!==false){
        if((latest.data.configurationVersion??latest.data.version)!==card.draftVersion)throw new ApiError('STALE_VERSION',412);
        const selectedLists=[...card.form.querySelectorAll('input[name=selectedLists]:checked')].map(input=>input.value);
        settings.tasks={selectedLists,projectMappings:Object.fromEntries([...card.form.querySelectorAll('select')].filter(input=>selectedLists.includes(input.dataset.projectFor)).map(input=>[input.dataset.projectFor,input.value||null])),automatic:true};
      }
      if(card.data.services?.calendars){card.calendars.check(latest.data);settings.calendars=card.calendars.selection();}
      await view.session.request('/task-providers/'+data.id+'/settings',{method:'PUT',etag:latest.etag,body:settings});if(!view.valid())return;
      view.clean(card.form);card.calendars.clean();card.feedback.textContent=t('task_providers.selection_saved');await load();
    },card.save,card.feedback);
    card.save.addEventListener('click',saveSelections);card.form.addEventListener('submit',event=>{event.preventDefault();saveSelections();});
    card.refresh=button(t('task_providers.refresh_now'),()=>run(async()=>{await view.session.request('/task-providers/'+data.id+'/refresh',{method:'POST'});if(view.valid())await load();},card.refresh,card.feedback));
    card.services=serviceChoices(data.services||{tasks:true,calendars:false},true);card.services.fieldset.addEventListener('change',()=>{card.servicesDirty=true;controls();});
    card.reconnect=button(t('task_providers.reconnect'),()=>connect(data.providerId,card.access?.value||'TWO_WAY',card.services.read(),card.feedback));
    const accessLabel=node('label',t('task_providers.account_access'),'form-label');
    {
      card.access=node('select',undefined,'form-select');for(const direction of ['IMPORT','TWO_WAY'])card.access.append(new Option(t('task_providers.access_'+direction),direction));
      card.access.addEventListener('change',()=>{card.accessDirty=true;controls();});accessLabel.append(card.access);
    }
    card.pause=button(t('task_providers.pause'),()=>run(async()=>{
      const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
      await view.session.request('/task-providers/'+data.id+'/state',{method:'PUT',etag:latest.etag,body:{paused:!latest.data.paused}});if(view.valid())await load();
    },card.pause,card.feedback));
    card.disconnect=button(t('task_providers.disconnect'),()=>run(async()=>{
      const latest=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
      await view.session.request('/task-providers/'+data.id,{method:'DELETE',etag:latest.etag});if(!view.valid())return;
      view.clean(card.form);await load();feedback.textContent=t('task_providers.disconnected');
    },card.disconnect,card.feedback));
    const identities=node('div',undefined,'mt-3');card.identities=taskProviderIdentities(view,identities,{reload:load,controls,projects});
    const incoming=node('div',undefined,'mt-3');card.incoming=taskProviderReviews(view,incoming,{reload:load,controls});
    const writes=node('div',undefined,'mt-3');card.writes=taskProviderWrites(view,writes,{reload:load,controls});
    const header=node('div',undefined,'internet-connection-header'),identity=node('div',undefined,'internet-account-identity'),identityText=node('div');card.serviceSummary=node('p','','hint small mb-0');card.serviceSummary.setAttribute('role','status');identityText.append(card.heading,card.serviceSummary);identity.append(icon(data.providerId),identityText);
    card.body=node('div',undefined,'internet-account-details');card.body.id='internet-account-details-'+data.id;card.body.dataset.accountDetails=data.id;
    card.details=button(t('internet_accounts.details'),()=>card.expand(card.body.hidden),'btn btn-outline-secondary btn-sm');card.details.dataset.accountDetailsTrigger=data.id;card.details.setAttribute('aria-controls',card.body.id);
    card.expand=open=>{card.body.hidden=!open;card.details.setAttribute('aria-expanded',String(open));card.panel.classList.toggle('is-open',open);card.refresh.hidden=!open&&!card.data.lastError&&!Object.keys(card.data.listErrors||{}).length;};card.expand(false);
    const headerActions=node('div',undefined,'d-flex flex-wrap gap-2');headerActions.append(card.refresh,card.details);header.append(identity,headerActions);
    card.serviceHint=node('p','','hint mb-0');card.serviceApply=button(t('internet_accounts.apply_services'),()=>connect(data.providerId,card.access?.value||'TWO_WAY',card.services.read(),card.feedback),'btn btn-primary');card.serviceChanges=node('div',undefined,'internet-service-changes');card.serviceChanges.hidden=true;card.serviceChanges.append(card.serviceHint,card.serviceApply);
    const columns=node('div',undefined,'internet-connection-columns');card.calendars=internetCalendarAccount(view,columns,{reload:load,clients,zone:()=>zone,controls,onSave:saveSelections});
    card.taskSection=node('section',undefined,'internet-connection-section');card.taskHeading=node('h4',t('internet_accounts.tasks'),'h5');card.taskHeading.id='internet-tasks-'+data.id;card.taskSection.setAttribute('aria-labelledby',card.taskHeading.id);
    card.reviewRoots=[identities,incoming,writes];card.taskSection.append(card.taskHeading,card.direction,card.form,identities,incoming,writes);columns.append(card.taskSection);
    const settings=node('details',undefined,'internet-connection-settings');settings.dataset.connectionSettings=data.id;settings.append(node('summary',t('internet_accounts.connection_settings')),accessLabel);
    const actions=node('div',undefined,'d-flex flex-wrap gap-2 mt-3');actions.append(card.pause,card.reconnect,card.disconnect);settings.append(actions,node('p',t('task_providers.disconnect_help'),'hint mt-2'));
    card.body.append(card.status,card.services.fieldset,card.serviceChanges,columns,card.save,settings);panel.append(header,card.feedback,card.error,card.retry,card.body);cards.set(data.id,card);return card;
  }
  function paint(data){
    calendarSynchronization=data.calendarSynchronization!==false;
    for(const [provider,status]of providerStatus){const ready=!!data.providers[provider];status.textContent=ready?t('internet_accounts.ready'):providerError(data.readiness?.[provider]||'TASK_PROVIDER_NOT_CONFIGURED');status.className=ready?'hint':'alert alert-warning';const details=status.parentElement;if(!details.dataset.initialized){details.open=!ready;details.dataset.initialized='true';}}
    const present=new Set();for(const value of data.items){
      if(value.ownerId!==view.session.owner)throw new ApiError('OWNER_CHANGED',401);present.add(value.id);const card=cards.get(value.id)||cardFor(value);card.data=value;
      card.status.textContent=(value.syncStatus?t('task_providers.status_'+value.syncStatus)+' · ':'')+(!value.enabled?t('task_providers.disabled'):value.paused?t('task_providers.paused'):value.syncing?t('task_providers.syncing'):t('internet_accounts.tasks_last_refresh',{date:value.lastSuccess?instantText(value.lastSuccess,zone):t('task_providers.never')}));
      const activeServices=['calendars','tasks'].filter(key=>value.services?value.services[key]:key==='tasks').map(key=>t('internet_accounts.'+key));card.serviceSummary.textContent=activeServices.join(' · ')+(!value.enabled?' · '+t('task_providers.disabled'):value.paused?' · '+t('task_providers.paused'):value.syncing?' · '+t('task_providers.syncing'):'');
      const attention=(value.pendingCount||0)+(value.syncIssues?.length||0)+(value.syncStatus==='NEEDS_ATTENTION'?1:0)+(value.identityReviews?.length||0)+(value.reviews?.length||0);
      if(attention>0)card.serviceSummary.append(' · '+t('internet_accounts.needs_attention'));
      if(!card.initialized){card.expand(location.hash.split('/')[2]===value.id||attention>0);card.initialized=true;if(location.hash.split('/')[2]===value.id){card.heading.tabIndex=-1;card.heading.focus();}}else if(attention>0&&!card.attention)card.expand(true);card.attention=attention;
      card.direction.textContent=t(value.automatic&&value.direction==='TWO_WAY'?'task_providers.mode_automatic':value.direction==='TWO_WAY'?'task_providers.mode_two_way':'task_providers.mode_import');card.pause.textContent=t(value.paused?'task_providers.resume':'task_providers.pause');
      const tasks=value.services?.tasks!==false;card.status.hidden=!tasks&&value.enabled&&!value.paused&&!value.syncing;card.form.hidden=!tasks;card.direction.hidden=!tasks;card.calendars.update(value);
      if(!card.servicesDirty)for(const [key,input]of Object.entries(card.services.choices))input.checked=value.services?!!value.services[key]:key==='tasks';
      if(card.access&&!card.accessDirty)card.access.value=value.direction;card.writes.update(value);card.incoming.update(value);card.identities.update(value);card.taskSection.hidden=!tasks&&!card.reviewRoots.some(root=>!root.hidden);
      const failures=Object.entries(value.listErrors||{}),syncFailure=code=>['CONFLICT','STALE_VERSION'].includes(code)?t('task_providers.sync_conflict'):providerError(code);
      card.error.hidden=!value.lastError&&!failures.length&&!value.syncIssues?.length;card.error.replaceChildren();
      if(value.lastError&&!failures.some(([,code])=>code===value.lastError))card.error.append(node('p',syncFailure(value.lastError),'mb-0 text-break'));
      for(const [id,code]of failures)card.error.append(node('p',t('task_providers.list_error',{name:value.availableLists.find(list=>list.id===id)?.name||id,message:syncFailure(code)}),'mb-0 text-break'));
      for(const issue of value.syncIssues||[])for(const warning of issue.warnings||[]){const key='task_writes.warning_'+warning.reason;card.error.append(node('p',issue.title+' · '+(t('task_writes.field_'+warning.field)==='task_writes.field_'+warning.field?t('task_writes.other_fields'):t('task_writes.field_'+warning.field))+': '+(t(key)===key?t('task_writes.warning_default'):t(key)),'mb-0 text-break'));}
      if(value.pendingCount)card.error.append(node('p',t('task_providers.pending',{count:value.pendingCount}),'mb-0'));
      card.refresh.hidden=card.body.hidden&&!value.lastError&&!failures.length;card.retry.hidden=!value.lastError||!value.nextRefresh;card.retry.textContent=card.retry.hidden?'':t('task_providers.next_attempt',{date:instantText(value.nextRefresh,zone)});
      if(!view.dirty.has(card.form)){
        card.draftVersion=value.configurationVersion??value.version;
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
    configured=data.providers;empty.hidden=present.size>0;controls();
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
  view.cleanup.add(()=>{++generation;clearTimeout(timer);controller?.abort();authorizationController?.abort();if(chooser.open)chooser.close();chooser.remove();for(const card of cards.values()){card.calendars.dispose();card.writes.dispose();card.incoming.dispose();card.identities.dispose();}});
  const [profile,projectPage,clientPage]=await Promise.all([view.session.request('/me'),view.session.page('/projects'),view.session.page('/clients')]);if(!view.valid())return;
  zone=profile.data.zoneId;clients=clientPage.items;projects=projectPage.items.map(project=>({...project,label:[clientPage.items.find(client=>client.id===project.clientId)?.name,project.name].filter(Boolean).join(' / ')}));await load();
}

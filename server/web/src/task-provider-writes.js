import {t} from './i18n.js';
import {ApiError} from './api.js';
import {node,button} from './account-ui.js';

const fields=['summary','body','status','scheduledDate','completedAt'];
const translated=(key,fallback)=>{const value=t(key);return value===key?t(fallback):value;};
const fieldName=field=>translated('task_writes.field_'+field,'task_writes.other_fields');
const valueText=(field,value)=>value===null||value===undefined?t('task_writes.empty'):field==='body'?value.content:field==='status'?t('tasks.status.'+value):String(value);

/** Owner-bound proposals; polling never replaces the comparison the user is reviewing. */
export function taskProviderWrites(view,root,{reload,controls:parentControls}){
  let connection=null,generation=0,catalogGeneration=0,disposed=false,loaded=false,signature='',selected=[],catalog=[],reviewGeneration=0;
  const valid=()=>!disposed&&view.valid(),base=()=>'/task-providers/'+connection.id+'/writes';
  const heading=node('h3',t('task_writes.title'),'h5'),help=node('p',t('task_writes.help'),'hint');
  const feedback=node('p','','form-feedback');feedback.setAttribute('role','status');
  const details=node('details'),summary=node('summary',t('task_writes.prepare'),'mb-2'),form=node('form');details.append(summary,help,form);
  const taskLabel=node('label',t('task_writes.task'),'form-label'),task=node('select',undefined,'form-select mb-2');task.required=true;taskLabel.append(task);
  const actionLabel=node('label',t('task_writes.action'),'form-label'),action=node('select',undefined,'form-select mb-2');
  for(const kind of ['UPDATE','DELETE','CREATE'])action.append(new Option(t('task_writes.action_'+kind),kind));actionLabel.append(action);
  const prepare=node('button',t('task_writes.review'),'btn btn-primary');prepare.type='submit';form.append(taskLabel,actionLabel,prepare);
  const candidateFeedback=node('p','','hint');candidateFeedback.setAttribute('role','status');details.append(candidateFeedback);
  const history=node('div'),comparison=node('div',undefined,'mt-3');comparison.hidden=true;root.append(heading,details,feedback,history,comparison);
  function controls(){
    const write=valid()&&view.canWrite&&!view.busy&&!view.uncertain&&!connection?.paused&&connection?.enabled;
    for(const input of form.querySelectorAll('select,button'))input.disabled=!write||connection?.direction!=='TWO_WAY'||!loaded;
    for(const input of root.querySelectorAll('[data-write-action]'))input.disabled=!write||input.dataset.allowed!=='true';
    for(const input of root.querySelectorAll('[data-write-local]'))input.disabled=!valid()||!view.canWrite||view.busy||view.uncertain||input.type!=='checkbox'&&input.dataset.allowed!=='true';
    for(const input of root.querySelectorAll('[data-write-review]'))input.disabled=!valid()||view.busy;
  }
  const run=(work,submit,write=true)=>void view.run(async()=>{parentControls();controls();await work();},{submit,feedback,write}).finally(()=>{if(valid()){parentControls();controls();}});
  async function candidates(){
    const current=++catalogGeneration;const [tasks,lists]=await Promise.all([view.session.page('/tasks'),view.session.page('/task-lists')]);if(!valid()||current!==catalogGeneration)return;
    catalog=tasks.items;
    selected=lists.items.filter(list=>list.connectionId===connection.id&&connection.selectedLists.includes(list.remoteListId)&&list.present);
    loaded=true;taskOptions();controls();
  }
  function taskOptions(){
    const previous=task.value,options=[];
    for(const item of catalog){
      if(action.value==='CREATE'){
        if(!item.parentTaskId)for(const list of selected)if(!(item.externalReferences||[]).some(ref=>ref.providerId==='google'&&ref.taskListId===list.id))options.push({taskId:item.id,listId:list.id,title:list.name+' / '+item.summary});
      }else for(const ref of item.externalReferences||[]){const list=selected.find(list=>list.id===ref.taskListId);if(ref.providerId==='google'&&list)options.push({taskId:item.id,listId:list.id,title:list.name+' / '+item.summary});}
    }
    task.replaceChildren(new Option(t('task_writes.choose_task'),''));for(const item of options)task.append(new Option(item.title,JSON.stringify({taskId:item.taskId,listId:item.listId})));
    if([...task.options].some(option=>option.value===previous))task.value=previous;
    candidateFeedback.textContent=options.length?'':t('task_writes.no_tasks');
  }
  const refreshTasks=button(t('task_writes.refresh_tasks'),()=>run(candidates,refreshTasks,false));refreshTasks.dataset.writeReview='tasks';details.append(refreshTasks);
  details.addEventListener('toggle',()=>{if(details.open&&(!loaded||!view.dirty.has(form)))run(candidates,prepare,false);});
  form.addEventListener('change',()=>view.dirty.add(form));
  action.addEventListener('change',()=>{taskOptions();controls();});
  form.addEventListener('submit',event=>{event.preventDefault();run(async()=>{
    if(!task.value)return;const identity=JSON.parse(task.value);
    const reply=await view.session.request(base(),{method:'POST',body:{...identity,action:action.value}});if(!valid())return;
    view.clean(form);show(reply);await reload();
  },prepare);});
  function changeValues(parent,values,title,conflicts=[]){
    if(!values)return;parent.append(node('h4',title,'h6 mt-3'));
    for(const field of fields)if(Object.hasOwn(values,field)){
      parent.append(node('p',fieldName(field)+(conflicts.includes(field)?' · '+t('task_writes.conflict'):''),'fw-semibold mb-1'));
      const text=node('pre',valueText(field,values[field]),'text-break small');text.style.whiteSpace='pre-wrap';parent.append(text);
    }
  }
  function identityValue(parent,id){
    parent.append(node('h4',t('task_writes.google_id'),'h6 mt-3'));const value=node('pre',id,'text-break small');value.style.whiteSpace='pre-wrap';parent.append(value);
  }
  function show(reply){
    if(!valid())return;const review=++reviewGeneration,row=reply.data,path=base()+'/'+row.id;comparison.replaceChildren();comparison.hidden=false;
    const title=node('h3',t('task_writes.review_title',{title:row.title}),'h5 text-break');title.tabIndex=-1;comparison.append(title,node('p',t('task_writes.state_'+row.state),'hint'));if(row.externalId)identityValue(comparison,row.externalId);
    if(row.action==='CREATE')comparison.append(node('p',t('task_writes.create_help'),'hint'));
    if(row.action==='DELETE')comparison.append(node('p',t('task_writes.delete_help'),'alert alert-warning'));
    else changeValues(comparison,row.writeFields,t('task_writes.proposed'));
    if(row.state==='PREVIEW'&&row.action==='UPDATE'&&!Object.keys(row.writeFields).length&&!row.warnings.length)comparison.append(node('p',t('task_writes.no_changes'),'hint'));
    if(row.warnings.length){const warnings=node('ul',undefined,'alert alert-warning');for(const warning of row.warnings)warnings.append(node('li',fieldName(warning.field)+': '+translated('task_writes.warning_'+warning.reason,'task_writes.warning_default'),'text-break'));comparison.append(warnings);}
    if(row.remoteFields){changeValues(comparison,row.localFields,t('task_writes.local'),row.conflictFields);changeValues(comparison,row.remoteFields,t('task_writes.google'),row.conflictFields);}
    if(row.state==='UNKNOWN')comparison.append(node('p',t(row.action==='CREATE'?'task_writes.unknown_create_help':'task_writes.unknown_help'),'alert alert-warning'));
    if(row.canLeaveUnresolved)unresolvedChoice(path,reply);
    if(row.state==='UNRESOLVED')comparison.append(node('p',t('task_writes.unresolved_help'),'hint'));
    const actions=node('div',undefined,'d-flex flex-wrap gap-2');
    const add=(label,work,allowed,local=false)=>{const pick=button(t(label),()=>run(work,pick));if(local)pick.dataset.writeLocal='decision';else pick.dataset.writeAction='true';pick.dataset.allowed=String(allowed);actions.append(pick);};
    if(row.state==='PREVIEW')add(row.action==='DELETE'?'task_writes.confirm_delete':'task_writes.confirm',async()=>{await view.session.request(path+'/confirm',{method:'POST',etag:reply.etag});if(!valid())return;comparison.hidden=true;await reload();},row.canConfirm&&connection.direction==='TWO_WAY');
    if(['UNKNOWN','ACK_REVIEW'].includes(row.state))add('task_writes.check',async()=>{await view.session.request(path+'/check',{method:'POST',etag:reply.etag});if(valid())await reload();},true);
    if(row.canResolve){
      const resolve=decision=>async()=>{await view.session.request(path+'/resolve',{method:'POST',etag:reply.etag,body:{decision,taskEtag:row.currentTaskEtag}});if(!valid())return;comparison.hidden=true;await reload();};
      add('task_writes.keep_local',resolve('LOCAL'),true);if(row.conflictFields.length)add('task_writes.use_google',resolve('REMOTE'),true);
    }
    if(['PREVIEW','QUEUED','CHECKING','CONFLICT'].includes(row.state))add('task_writes.cancel',async()=>{await view.session.request(path,{method:'DELETE',etag:reply.etag});if(!valid())return;comparison.hidden=true;await reload();},true,true);
    if(row.action==='CREATE'&&row.state==='UNKNOWN'&&!row.externalId)identityPicker(path,reply,review);
    const close=button(t('ui.close'),()=>{++reviewGeneration;comparison.hidden=true;comparison.replaceChildren();heading.tabIndex=-1;heading.focus();});actions.append(close);comparison.append(actions);controls();title.focus();
  }
  function unresolvedChoice(path,reply){
    const block=node('div',undefined,'border rounded p-2 my-3'),warning=node('p',t('task_writes.leave_unresolved_help'),'alert alert-warning'),label=node('label',undefined,'d-flex gap-2 align-items-start'),acknowledge=node('input',undefined,'form-check-input');acknowledge.type='checkbox';acknowledge.dataset.writeLocal='acknowledge';label.append(acknowledge,node('span',t('task_writes.leave_unresolved_ack')));
    const choose=button(t('task_writes.leave_unresolved'),()=>run(async()=>{
      if(!acknowledge.checked)return;await view.session.request(path+'/leave-unresolved',{method:'POST',etag:reply.etag,body:{acknowledgePossibleSeparateImport:true}});if(!valid())return;++reviewGeneration;comparison.hidden=true;await reload();heading.tabIndex=-1;heading.focus();
    },choose));choose.dataset.writeLocal='decision';choose.dataset.allowed='false';acknowledge.addEventListener('change',()=>{choose.dataset.allowed=String(acknowledge.checked);controls();});block.append(warning,label,choose);comparison.append(block);
  }
  function identityPicker(path,reply,review){
    const picker=node('form',undefined,'border rounded p-2 mt-3'),label=node('label',t('task_writes.identity_search'),'form-label'),query=node('input',undefined,'form-control');query.type='search';query.maxLength=1024;label.append(query);
    const search=node('button',t('task_writes.find_candidates'),'btn btn-secondary mt-2');search.type='submit';search.dataset.writeReview='candidates';
    const results=node('div');picker.append(label,search,results);comparison.append(picker);let snapshot=null;
    picker.addEventListener('submit',event=>{event.preventDefault();run(async()=>{
      const response=await view.session.request(path+'/candidates?q='+encodeURIComponent(query.value));if(!valid()||review!==reviewGeneration)return;snapshot=response.data;results.replaceChildren();
      results.append(node('p',t('task_writes.candidates_count',{shown:snapshot.items.length,count:snapshot.count}),'hint mt-2'));
      if(!snapshot.items.length)return;
      const chooseLabel=node('label',t('task_writes.identity_choice'),'form-label'),choose=node('select',undefined,'form-select');chooseLabel.append(choose);choose.append(new Option(t('task_writes.choose_identity'),''));
      for(const item of snapshot.items)choose.append(new Option(item.fields.summary+' · '+item.externalId,item.externalId));
      const values=node('div'),bind=button(t('task_writes.link_identity'),()=>run(async()=>{
        if(!choose.value)return;await view.session.request(path+'/identity',{method:'POST',etag:reply.etag,body:{externalId:choose.value,scanEtag:snapshot.scanEtag,taskEtag:reply.data.currentTaskEtag}});
        if(!valid()||review!==reviewGeneration)return;++reviewGeneration;comparison.hidden=true;await reload();
      },bind));bind.dataset.writeAction='identity';bind.dataset.allowed='false';
      choose.addEventListener('change',()=>{values.replaceChildren();const chosen=snapshot.items.find(item=>item.externalId===choose.value);if(chosen){identityValue(values,chosen.externalId);changeValues(values,reply.data.localFields,t('task_writes.local'));changeValues(values,chosen.fields,t('task_writes.google'));}bind.dataset.allowed=String(Boolean(chosen));controls();});
      results.append(chooseLabel,values,bind);controls();
    },search,false);});
  }
  async function refresh(){
    if(!valid()||root.hidden)return;const current=++generation,reply=await view.session.request(base());if(!valid()||current!==generation)return;
    const next=JSON.stringify(reply.data.items);if(next===signature)return;signature=next;
    const focused=history.contains(document.activeElement)?document.activeElement?.dataset.writeReview:null;history.replaceChildren();
    for(const row of reply.data.items){
      const item=node('div',undefined,'border-top py-2'),status=node('p',row.title+' · '+t('task_writes.state_'+row.state),'text-break mb-1');
      const review=button(t('task_writes.inspect'),()=>run(async()=>{const reply=await view.session.request(base()+'/'+row.id);if(valid())show(reply);},review,false));review.dataset.writeReview=row.id;item.append(status,review);history.append(item);
    }
    if(focused)[...history.querySelectorAll('[data-write-review]')].find(pick=>pick.dataset.writeReview===focused)?.focus();controls();
  }
  return {controls,refresh,update(value){connection=value;root.hidden=value.providerId!=='google'||!value.pendingWrites&&(value.direction!=='TWO_WAY'||!value.selectedLists.length);details.hidden=value.direction!=='TWO_WAY';controls();},dispose(){disposed=true;++generation;++catalogGeneration;++reviewGeneration;view.clean(form);}};
}

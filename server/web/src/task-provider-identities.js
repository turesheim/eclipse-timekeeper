import {t} from './i18n.js';
import {instantText} from './model.js';
import {node,button} from './account-ui.js';

const fields=['summary','body','status','scheduledDate','completedAt'];
function values(parent,data,title){
  parent.append(node('h5',title,'h6 mt-3'));
  for(const field of fields)if(Object.hasOwn(data||{},field)){const value=data[field],label=t('task_writes.field_'+field);parent.append(node('p',label,'fw-semibold mb-1'));const text=value===null?t('task_writes.empty'):field==='body'?value.content:field==='status'?t('tasks.status.'+value):String(value),display=node('pre',text,'small text-break');display.style.whiteSpace='pre-wrap';parent.append(display);}
}

/** Explicit import or alias-preserving replacement; polling retains the captured comparison. */
export function taskProviderIdentities(view,root,{reload,controls:parentControls,projects}){
  let connection=null,generation=0,disposed=false,signature='';const valid=()=>!disposed&&view.valid();
  const heading=node('h3',t('task_identities.title'),'h5'),choices=node('div',undefined,'d-flex flex-wrap gap-2'),comparison=node('div',undefined,'mt-3'),feedback=node('p','','form-feedback');feedback.setAttribute('role','status');root.append(heading,choices,comparison,feedback);
  const controls=()=>{for(const pick of root.querySelectorAll('button,input,select'))pick.disabled=!valid()||view.busy||view.uncertain||pick.dataset.identityWrite==='true'&&(!view.canWrite||!connection?.enabled||connection?.paused||pick.dataset.allowed==='false');};
  const run=(work,pick,write=false)=>void view.run(async()=>{parentControls();controls();await work();},{submit:pick,feedback,write}).finally(()=>{if(valid()){parentControls();controls();}});
  async function load(path,query=''){
    const current=++generation;const [reply,tasks,lists]=await Promise.all([view.session.request(path+'?q='+encodeURIComponent(query)),view.session.page('/tasks'),view.session.page('/task-lists')]);if(!valid()||current!==generation)return;show(reply.data,path,query,tasks.items,lists.items);
  }
  function show(snapshot,path,query,tasks,lists){
    const review=++generation;comparison.replaceChildren();const title=node('h4',t('task_identities.comparison'),'h6');title.tabIndex=-1;comparison.append(title,node('p',t('task_reviews.observed',{date:instantText(snapshot.observedAt,'UTC')}),'hint'),node('p',t('task_identities.help'),'hint'));
    const search=node('form',undefined,'my-3'),queryLabel=node('label',t('task_identities.search'),'form-label d-block'),input=node('input',undefined,'form-control');input.type='search';input.maxLength=1024;input.value=query;queryLabel.append(input);const find=node('button',t('task_reviews.find'),'btn btn-secondary mt-2');find.type='submit';search.append(queryLabel,find);search.addEventListener('submit',event=>{event.preventDefault();run(()=>load(path,input.value),find);});comparison.append(search,node('p',t('task_reviews.count',{shown:snapshot.items.length,count:snapshot.count}),'hint'));
    const eligible=lists.filter(list=>list.connectionId===connection.id);
    for(const row of snapshot.items){
      const item=node('div',undefined,'border-top py-3');item.dataset.identityExternalId=row.externalId;item.append(node('h5',row.fields.summary,'h6 text-break'),node('p',row.externalId,'small text-break'));values(item,row.fields,t('task_writes.google'));
      const actions=node('div',undefined,'d-flex flex-wrap gap-2');const create=button(t('task_identities.import'),()=>run(()=>decide({decision:'IMPORT',externalId:row.externalId},row,path),create,true),'btn btn-secondary');create.dataset.identityWrite='true';create.dataset.allowed=String(row.canImport);actions.append(create);item.append(actions);
      if(row.blockedReason)item.append(node('p',t('task_identities.block_'+row.blockedReason),'hint'));
      const label=node('label',t('task_identities.existing'),'form-label d-block mt-3'),choose=node('select',undefined,'form-select');choose.append(new Option(t('task_identities.choose'),''));label.append(choose);item.append(label);const options=[];
      for(const task of tasks)if(!row.taskId||task.id===row.taskId)for(const ref of task.externalReferences||[]){const list=eligible.find(list=>list.id===ref.taskListId);if(ref.providerId==='google'&&list){const option={taskId:task.id,previous:{providerId:'google',repositoryId:ref.repositoryId,externalId:ref.externalId}};const value=JSON.stringify(option);options.push({value,option});choose.append(new Option(task.summary+' · '+list.name+' · '+ref.externalId,value));}}
      if(!options.length)item.append(node('p',t('task_identities.no_existing'),'hint'));
      const native=node('div'),ackLabel=node('label',undefined,'d-flex gap-2 align-items-start my-3'),ack=node('input',undefined,'form-check-input');ack.type='checkbox';ack.dataset.identityWrite='true';ackLabel.append(ack,node('span',t('task_identities.ack')));let selection=null,pickGeneration=0;
      const replace=button(t('task_identities.replace'),()=>run(()=>decide({decision:'REPLACE',externalId:row.externalId,taskId:selection.data.id,taskEtag:selection.etag,previous:selection.previous},row,path),replace,true),'btn btn-primary');replace.dataset.identityWrite='true';replace.dataset.allowed='false';
      const allowed=()=>{replace.dataset.allowed=String(Boolean(row.canReplace&&selection&&ack.checked));controls();};ack.addEventListener('change',allowed);
      choose.addEventListener('change',()=>{
        const current=++pickGeneration;selection=null;ack.checked=false;native.replaceChildren();allowed();const chosen=options.find(option=>option.value===choose.value);if(!chosen)return;
        void view.session.request('/tasks/'+chosen.option.taskId).then(reply=>{
          if(!valid()||review!==generation||current!==pickGeneration)return;const previous=chosen.option.previous;if(!(reply.data.externalReferences||[]).some(ref=>ref.providerId===previous.providerId&&ref.repositoryId===previous.repositoryId&&ref.externalId===previous.externalId)){feedback.textContent=t('task_identities.previous_changed');return;}
          selection={data:reply.data,etag:reply.etag,previous};values(native,reply.data,t('task_writes.local'));const project=projects.find(project=>project.id===reply.data.projectId);native.append(node('p',t('task_identities.project',{name:project?.label||t('task_providers.no_project')}),'hint'));allowed();
        }).catch(error=>{if(!valid()||review!==generation||current!==pickGeneration)return;if(error.status===401)view.signOut(error.message);else feedback.textContent=error.message;});
      });
      item.append(native,node('p',t('task_identities.hierarchy'),'hint'),ackLabel,replace);comparison.append(item);
    }
    const close=button(t('ui.close'),()=>{++generation;comparison.replaceChildren();heading.tabIndex=-1;heading.focus();});comparison.append(close);controls();title.focus();
  }
  async function decide(body,row,path){
    await view.session.request(path,{method:'POST',etag:row.reviewEtag,body});if(!valid())return;++generation;comparison.replaceChildren();await reload();const target=root.hidden?root.parentElement.firstElementChild:heading;target.tabIndex=-1;target.focus();
  }
  return {controls,update(value){connection=value;const values=value.identityReviews||[];root.hidden=value.providerId!=='google'||!values.length&&!comparison.childElementCount;const next=JSON.stringify(values);if(next!==signature){signature=next;choices.replaceChildren();for(const list of values){const name=value.availableLists.find(item=>item.id===list.remoteListId)?.name||list.remoteListId,path='/task-providers/'+value.id+'/identities/'+list.listId;const pick=button(t('task_identities.review',{name}),()=>run(()=>load(path),pick));choices.append(pick);}}controls();},dispose(){disposed=true;++generation;}};
}

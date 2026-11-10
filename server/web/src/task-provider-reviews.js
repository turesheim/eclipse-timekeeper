import {t} from './i18n.js';
import {instantText} from './model.js';
import {node,button} from './account-ui.js';

const text=value=>value===null||value===undefined?t('task_writes.empty'):typeof value==='object'?JSON.stringify(value,null,2):String(value);
const label=field=>{const key='task_reviews.field_'+field,value=t(key);return value===key?field:value;};
function valueAt(fields,field){return field.split('.').reduce((value,key)=>value?.[key],fields);}

/** The visible comparison remains fixed during polling; mutations submit both captured ETags. */
export function taskProviderReviews(view,root,{reload,controls:parentControls}){
  let connection=null,generation=0,disposed=false,signature='';const valid=()=>!disposed&&view.valid();
  const heading=node('h3',t('task_reviews.title'),'h5'),choices=node('div',undefined,'d-flex flex-wrap gap-2'),comparison=node('div',undefined,'mt-3'),feedback=node('p','','form-feedback');feedback.setAttribute('role','status');root.append(heading,choices,comparison,feedback);
  const source=()=>t('task_providers.'+connection.providerId);
  const controls=()=>{for(const pick of root.querySelectorAll('button'))pick.disabled=view.busy||view.uncertain||pick.dataset.mutation==='true'&&(!view.canWrite||connection?.paused||!connection?.enabled);};
  const run=(work,pick,write=false)=>void view.run(async()=>{parentControls();controls();await work();},{submit:pick,feedback,write}).finally(()=>{if(valid()){parentControls();controls();}});
  function show(snapshot,path,query){
    comparison.replaceChildren();const title=node('h4',t('task_reviews.comparison',{provider:source()}),'h6');title.tabIndex=-1;comparison.append(title,node('p',t('task_reviews.observed',{date:instantText(snapshot.observedAt,'UTC')}),'hint'),node('p',t('task_reviews.help',{provider:source()}),'hint'));
    const form=node('form',undefined,'mb-3'),searchLabel=node('label',t('task_reviews.search',{provider:source()}),'form-label'),search=node('input',undefined,'form-control');search.type='search';search.maxLength=1024;search.value=query;searchLabel.append(search);const find=node('button',t('task_reviews.find'),'btn btn-secondary mt-2');find.type='submit';form.append(searchLabel,find);form.addEventListener('submit',event=>{event.preventDefault();run(()=>load(path,search.value),find);});comparison.append(form,node('p',t('task_reviews.count',{shown:snapshot.items.length,count:snapshot.count}),'hint'));
    for(const row of snapshot.items){
      const item=node('div',undefined,'border-top py-3');item.dataset.incomingTask=row.taskId||'';
      item.append(node('h5',row.localFields?.summary||row.remoteFields?.summary||row.externalId,'h6 text-break'),node('p',row.externalId,'text-break small'));
      if(row.deleted)item.append(node('p',t('task_reviews.deleted',{provider:source()}),'alert alert-warning'));
      for(const decision of row.decisions||[]){
        if(!['CONFLICT','NO_BASELINE','REMOTE_ONLY'].includes(decision.outcome))continue;
        item.append(node('h6',label(decision.field),'fw-semibold'));
        for(const [caption,fields]of [['task_writes.local',row.localFields],['task_writes.google',row.remoteFields]]){
          item.append(node('p',caption==='task_writes.google'?source():t(caption),'small mb-1'));const value=node('pre',text(valueAt(fields,decision.field)),'small text-break');value.style.whiteSpace='pre-wrap';item.append(value);
        }
      }
      const actions=node('div',undefined,'d-flex flex-wrap gap-2');
      if(row.canResolve){
        const resolve=(decision,key)=>{const pick=button(t(key,{provider:source()}),()=>run(async()=>{await view.session.request(path+'/'+row.taskId,{method:'POST',etag:row.reviewEtag,body:{decision,taskEtag:row.taskEtag}});if(!valid())return;comparison.replaceChildren();await reload();const focus=root.hidden?root.parentElement.firstElementChild:heading;focus.tabIndex=-1;focus.focus();},pick,true),decision==='DELETE'?'btn btn-danger':'btn btn-secondary');pick.dataset.mutation='true';pick.dataset.incomingDecision=decision;actions.append(pick);};
        resolve('LOCAL',row.deleted?'task_reviews.keep_deleted':'task_writes.keep_local');if(!row.deleted)resolve('REMOTE','task_reviews.use_remote');else if(row.canDelete)resolve('DELETE','task_reviews.delete');
        else item.append(node('p',t('task_reviews.other_sources'),'hint'));
      }else item.append(node('p',t('task_reviews.unresolved'),'hint'));
      item.append(actions);comparison.append(item);
    }
    const close=button(t('ui.close'),()=>{++generation;comparison.replaceChildren();heading.tabIndex=-1;heading.focus();});comparison.append(close);controls();title.focus();
  }
  async function load(path,query=''){
    const current=++generation,reply=await view.session.request(path+'?q='+encodeURIComponent(query));if(!valid()||current!==generation)return;show(reply.data,path,query);
  }
  return {
    controls,
    update(value){
      connection=value;const values=value.reviews||[];root.hidden=!values.length&&!comparison.childElementCount;const next=JSON.stringify(values);if(next===signature){controls();return;}signature=next;choices.replaceChildren();
      for(const list of values){const name=value.availableLists.find(item=>item.id===list.remoteListId)?.name||list.remoteListId,path='/task-providers/'+value.id+'/reviews/'+list.listId;const pick=button(t('task_reviews.review',{name}),()=>run(()=>load(path),pick));pick.dataset.incomingList=list.listId;choices.append(pick);}controls();
    },
    dispose(){disposed=true;++generation;}
  };
}

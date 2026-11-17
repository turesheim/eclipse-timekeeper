import {clientChoices,projectChoices} from './client-state.js';
import {t} from './i18n.js';
import {taskPresentation} from './model.js';

/** Task-only controls retain the exact original timed deadline unless it is edited. */
export function taskEditorFields(field,value,data,id,homes=[]){
  const originalClient=value.clientId||data().projects.find(item=>item.id===value.projectId)?.clientId||'';
  const client=field(t('ui.client'),'clientId',originalClient,{choices:[]});
  const project=field(t('ui.project'),'projectId',value.projectId||'',{choices:[]});
  const parent=field(t('ui.parent_task'),'parentTaskId',value.parentTaskId||'',{choices:[]});
  const choices=(input,items,empty)=>{
    const selected=input.value;input.replaceChildren();
    for(const [id,label,disabled=false]of [['',empty],...items]){const option=document.createElement('option');option.value=id;option.textContent=label;option.disabled=disabled;input.append(option);}
    input.value=selected;if(input.selectedIndex<0)input.value='';
  };
  const refresh=(selectedParent=parent.value)=>{
    const current=data();choices(client,clientChoices(current.clients,[originalClient]).map(item=>[item.id,item.name]),t('ui.unassigned'));
    choices(project,projectChoices(current.projects,current.clients,[value.projectId]).filter(item=>!client.value||item.clientId===client.value).map(item=>[item.id,item.name]),t('ui.no_project'));
    const contextClient=current.projects.find(item=>item.id===project.value)?.clientId||client.value;
    const candidates=current.tasks.filter(item=>item.id!==id&&!['COMPLETED','CANCELLED'].includes(item.status)&&(item.projectId||'')===project.value&&(current.projects.find(project=>project.id===item.projectId)?.clientId||item.clientId||'')===contextClient).map(item=>[item.id,taskPresentation(item).title]);
    // A completed current parent is retained for unrelated edits, but cannot be
    // selected again or carried into a different client/project assignment.
    const retained=current.tasks.find(item=>item.id===selectedParent&&item.id===value.parentTaskId&&item.id!==id);
    if(retained&&client.value===originalClient&&project.value===(value.projectId||'')&&!candidates.some(([id])=>id===retained.id))candidates.push([retained.id,taskPresentation(retained).title+' · '+t('tasks.status.'+(retained.status||'NOT_STARTED')),true]);
    choices(parent,candidates,t('ui.no_parent'));parent.value=candidates.some(([id])=>id===selectedParent)?selectedParent:'';
  };
  // Install initial choices before selecting IDs, since an empty select drops its value.
  refresh();client.value=originalClient;refresh();project.value=value.projectId||'';refresh(value.parentTaskId||'');
  project.addEventListener('change',()=>{const selected=data().projects.find(item=>item.id===project.value);if(selected)client.value=selected.clientId;refresh();});
  client.addEventListener('change',()=>{const selected=data().projects.find(item=>item.id===project.value);if(selected&&selected.clientId!==client.value)project.value='';refresh();});
  const availableProjects=new Set(projectChoices(data().projects,data().clients).map(project=>project.id)),availableHomes=homes.filter(list=>list.id===value.syncListId||!list.projectId||availableProjects.has(list.projectId));
  const home=field(t('tasks.sync_list'),'syncListId',value.syncListId||'',{choices:[['',t('tasks.local_only')],...availableHomes.map(list=>[list.id,list.name])]});
  home.addEventListener('change',()=>{if(!id&&!project.value){const selected=homes.find(list=>list.id===home.value);if(selected?.projectId){project.value=selected.projectId;refresh();}}});
  parent.addEventListener('change',()=>{const ancestor=data().tasks.find(task=>task.id===parent.value);if(ancestor?.syncListId)home.value=ancestor.syncListId;});
  const status=field(t('tasks.status_label'),'status',value.status||'NOT_STARTED',{choices:['NOT_STARTED','IN_PROGRESS','COMPLETED','WAITING','DEFERRED'].map(key=>[key,t('tasks.status.'+key)])});
  const scheduled=field(t('tasks.scheduled_day'),'scheduledDate',value.scheduledDate||'',{type:'date'});
  const format=field(t('tasks.notes_format'),'bodyFormat',value.body?.format||'TEXT',{choices:['TEXT','HTML'].map(key=>[key,key])});
  const notes=field(t('tasks.notes'),'bodyContent',value.body?.content||'',{multiline:true,max:65536});
  const priority=field(t('tasks.priority'),'priority',value.priority||'UNSPECIFIED',{choices:['UNSPECIFIED','LOW','NORMAL','HIGH'].map(key=>[key,t('tasks.priority_'+key.toLowerCase())])});
  const order=field(t('tasks.sibling_order'),'siblingOrder',value.siblingOrder||0,{type:'number'});order.min='0';order.step='1';
  const categories=field(t('tasks.categories'),'categories',(value.metadata?.categories||[]).join('\n'),{multiline:true,hint:t('tasks.categories_help')});
  function moment(prefix,initial){
    const clockPrefix=prefix==='start'?'start':'deadline';
    const kind=field(t('tasks.'+prefix+'_type'),prefix+'Kind',initial?.kind||'',{choices:[['',t('tasks.no_date')],['DATE',t('tasks.deadline_date')],['DATE_TIME',t('tasks.deadline_time')]]});
    const date=field(t('tasks.'+prefix+'_label'),prefix+'Date',initial?.date||'',{type:'date'}),time=field(t('tasks.'+clockPrefix+'_clock'),prefix+'Time',(initial?.time||'').split('.')[0],{type:'time'}),zone=field(t('tasks.'+clockPrefix+'_zone'),prefix+'Zone',initial?.timeZone||data().profile.data.zoneId,{max:255});time.step='any';
    const visibility=()=>{date.closest('label').hidden=!kind.value;date.required=!!kind.value;for(const input of [time,zone]){input.closest('label').hidden=kind.value!=='DATE_TIME';input.required=kind.value==='DATE_TIME';}};
    kind.addEventListener('change',visibility);visibility();const original=[kind.value,date.value,time.value,zone.value];
    return ()=>{
      if([kind.value,date.value,time.value,zone.value].every((part,index)=>part===original[index]))return initial||null;
      return !kind.value?null:{kind:kind.value,date:date.value,...(kind.value==='DATE_TIME'?{time:time.value===original[2]&&initial?.kind==='DATE_TIME'?initial.time:time.value,timeZone:zone.value}:{})};
    };
  }
  const start=moment('start',value.start),due=moment('due',value.due);
  return {refresh,values:()=>({syncListId:home.value||null,status:status.value,scheduledDate:scheduled.value||null,
    completedAt:status.value==='COMPLETED'?(value.completedAt||null):null,body:{format:format.value,content:notes.value},priority:priority.value,siblingOrder:Number(order.value),
    metadata:{...(value.metadata||{tags:[],links:[]}),categories:[...new Set(categories.value.split('\n').map(value=>value.trim()).filter(Boolean))]},start:start(),due:due()})};
}

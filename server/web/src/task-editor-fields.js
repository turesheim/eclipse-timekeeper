import {t} from './i18n.js';
import {taskPresentation} from './model.js';

/** Task-only controls retain the exact original timed deadline unless it is edited. */
export function taskEditorFields(field,value,data,id){
  const client=field(t('ui.client'),'clientId',value.clientId||data().projects.find(item=>item.id===value.projectId)?.clientId||'',{choices:[]});
  const project=field(t('ui.project'),'projectId',value.projectId||'',{choices:[]});
  const parent=field(t('ui.parent_task'),'parentTaskId',value.parentTaskId||'',{choices:[]});
  const choices=(input,items,empty)=>{
    const selected=input.value;input.replaceChildren();
    for(const [id,label]of [['',empty],...items]){const option=document.createElement('option');option.value=id;option.textContent=label;input.append(option);}
    input.value=selected;
  };
  const refresh=()=>{
    const current=data();choices(client,current.clients.map(item=>[item.id,item.name]),t('ui.unassigned'));
    choices(project,current.projects.filter(item=>!client.value||item.clientId===client.value).map(item=>[item.id,item.name]),t('ui.no_project'));
    choices(parent,current.tasks.filter(item=>item.id!==id&&(item.projectId||'')===project.value).map(item=>[item.id,taskPresentation(item).title]),t('ui.no_parent'));
  };
  // Install initial choices before selecting IDs, since an empty select drops its value.
  refresh();client.value=value.clientId||data().projects.find(item=>item.id===value.projectId)?.clientId||'';refresh();project.value=value.projectId||'';refresh();parent.value=value.parentTaskId||'';
  project.addEventListener('change',()=>{const selected=data().projects.find(item=>item.id===project.value);if(selected)client.value=selected.clientId;refresh();});
  client.addEventListener('change',()=>{const selected=data().projects.find(item=>item.id===project.value);if(selected&&selected.clientId!==client.value)project.value='';refresh();});
  field(t('tasks.priority'),'priority',value.priority||'UNSPECIFIED',{choices:['UNSPECIFIED','LOW','NORMAL','HIGH'].map(key=>[key,t('tasks.priority_'+key.toLowerCase())])});
  const kind=field(t('tasks.deadline_type'),'dueKind',value.due?.kind||'',{choices:[['',t('tasks.no_deadline')],['DATE',t('tasks.deadline_date')],['DATE_TIME',t('tasks.deadline_time')]]});
  const date=field(t('tasks.due_label'),'dueDate',value.due?.date||'',{type:'date'}),time=field(t('tasks.deadline_clock'),'dueTime',value.due?.time||'',{type:'time'}),zone=field(t('tasks.deadline_zone'),'dueZone',value.due?.timeZone||data().profile.data.zoneId,{max:255});time.step='any';
  const visibility=()=>{date.closest('label').hidden=!kind.value;date.required=!!kind.value;for(const input of [time,zone]){input.closest('label').hidden=kind.value!=='DATE_TIME';input.required=kind.value==='DATE_TIME';}};
  kind.addEventListener('change',visibility);visibility();const original=[kind.value,date.value,time.value,zone.value];
  return {refresh,due:()=>{
    if([kind.value,date.value,time.value,zone.value].every((part,index)=>part===original[index]))return value.due||null;
    return !kind.value?null:{kind:kind.value,date:date.value,...(kind.value==='DATE_TIME'?{time:time.value,timeZone:zone.value}:{})};
  }};
}

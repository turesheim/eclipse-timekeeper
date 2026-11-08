import {t,getLocale} from './i18n.js';
import {dateInZone} from './model.js';
import {iconButton} from './icons.js';
import {calendarDescription,linkedText} from './tooltip-description.js';
import {taskRows,taskMatches} from './tasks-model.js';
import {calendarTasks} from './tasks-api.js';

const node=(tag,text,classes)=>{const result=document.createElement(tag);if(text!==undefined)result.textContent=text;if(classes)result.className=classes;return result;};
const button=(text,run,glyph)=>{const result=node('button',text,'btn btn-outline-secondary');result.type='button';if(glyph)iconButton(result,glyph,text);result.addEventListener('click',run);return result;};
const selectionKey=value=>JSON.stringify([value.kind,value.value]);

/** All sources remain in their original collections. Filters only change presentation. */
export class TasksView {
  constructor(root,callbacks){
    Object.assign(this,{root,callbacks,generation:0,sourceTasks:[],visibleLimit:50,filterButtons:new Map(),rowElements:new Map(),selection:{kind:'smart',value:'all'}});
    this.narrow=matchMedia('(max-width:760px)');
    const heading=node('div',undefined,'tasks-heading');this.title=node('h2',t('ui.tasks'));this.title.id='tasks-title';
    this.toggle=button(t('tasks.browse'),()=>this.navigationState(!root.classList.contains('tasks-navigation-open')),'layout-sidebar');this.toggle.setAttribute('aria-controls','tasks-navigation');
    this.create=button(t('tasks.new'),()=>{if(!this.writable)return;this.selection={kind:'smart',value:'all'};this.paint();callbacks.onNew();},'add');
    this.refresh=button(t('tasks.refresh'),()=>{callbacks.onRefresh?.();void this.load();},'refresh');const actions=node('div',undefined,'btn-group');actions.append(this.create,this.refresh);heading.append(this.toggle,this.title,actions);
    this.error=node('p','','alert alert-danger');this.error.hidden=true;this.error.setAttribute('role','alert');
    this.warning=node('p',t('tasks.source_warning'),'alert alert-warning');this.warning.hidden=true;this.warning.setAttribute('role','status');
    const layout=node('div',undefined,'tasks-layout');this.navigation=node('nav',undefined,'tasks-navigation card');this.navigation.id='tasks-navigation';this.navigation.setAttribute('aria-label',t('tasks.browse'));
    this.groups={};for(const [kind,label]of [['smart','tasks.categories'],['client','ui.clients'],['category','tasks.project_categories']]){
      const title=node('h3',t(label)),group=node('div',undefined,'tasks-filter-group');group.setAttribute('role','group');group.setAttribute('aria-label',t(label));this.navigation.append(title,group);this.groups[kind]=group;
    }
    this.navigation.addEventListener('keydown',event=>{if(event.key==='Escape'&&this.narrow.matches){event.preventDefault();this.navigationState(false);this.toggle.focus();}});
    const content=node('section',undefined,'tasks-results card');this.listTitle=node('h3',t('tasks.all'));this.listTitle.id='tasks-list-title';this.listTitle.tabIndex=-1;content.setAttribute('aria-labelledby',this.listTitle.id);
    this.count=node('p','','hint');this.count.setAttribute('role','status');this.list=node('ul',undefined,'tasks-list');this.list.id='tasks-list';this.empty=node('p',t('tasks.empty'),'hint');this.more=button(t('tasks.more'),()=>{this.visibleLimit+=50;this.paint();});this.more.hidden=true;content.append(this.listTitle,this.count,this.list,this.empty,this.more);layout.append(this.navigation,content);root.append(heading,this.error,this.warning,layout);
    this.detail=node('dialog',undefined,'app-dialog task-details');this.detail.id='task-details';this.detailTitle=node('h2');this.detailTitle.id='task-details-title';this.detail.setAttribute('aria-labelledby',this.detailTitle.id);
    const close=button(t('ui.close'),()=>this.detail.close(),'close');close.classList.add('task-details-close');this.detailBody=node('div');
    this.edit=button(t('ui.edit_task'),()=>{const row=this.rows?.find(row=>row.key===this.detailKey);if(row?.source==='task'&&this.writable){this.detail.close();callbacks.onEdit(row.id);}});
    this.detail.append(close,this.detailTitle,this.detailBody,this.edit);root.append(this.detail);
    this.detail.addEventListener('close',()=>{if(this.visible&&!document.querySelector('dialog[open]'))this.rowElements.get(this.detailKey)?.button.focus();});
    this.narrow.addEventListener('change',()=>{const focused=this.navigation.contains(document.activeElement);this.navigationState(false);if(focused&&this.narrow.matches)this.toggle.focus();});this.navigationState(false);this.controls();
  }
  controls(){this.create.disabled=!this.writable;this.refresh.disabled=!this.session||this.loading;this.edit.disabled=!this.writable;this.list.setAttribute('aria-busy',String(!!this.loading));if(this.loading){this.count.textContent=t('tasks.loading');this.empty.hidden=true;}}
  navigationState(open){this.root.classList.toggle('tasks-navigation-open',open);this.toggle.setAttribute('aria-expanded',String(!this.narrow.matches||open));}
  update(data,connected,session,writable){
    if(this.session&&session!==this.session)this.reset();const changed=this.data?.revision!==data.revision||this.data?.profile.data.zoneId!==data.profile.data.zoneId;
    this.data=data;this.session=session;this.connected=connected;this.writable=connected&&writable;this.controls();if(this.visible){this.paint();if(changed)void this.load();}
  }
  show(){this.visible=true;this.paint();void this.load();this.timer??=setInterval(()=>{this.paint();void this.load();},30000);}
  hide(){this.visible=false;clearInterval(this.timer);this.timer=null;this.controller?.abort();this.controller=null;this.loading=false;this.reloadRequested=false;++this.generation;this.detail.close();this.navigationState(false);this.controls();}
  reset(){this.hide();this.data=null;this.session=null;this.writable=false;this.sourceTasks=[];this.rows=[];this.selection={kind:'smart',value:'all'};this.filterButtons.clear();this.rowElements.clear();for(const group of Object.values(this.groups))group.replaceChildren();this.list.replaceChildren();this.detailBody.replaceChildren();this.detailTitle.textContent='';this.detailKey=null;this.error.hidden=this.warning.hidden=true;this.count.textContent='';this.listTitle.textContent=t('tasks.all');this.error.textContent='';this.visibleLimit=50;this.more.hidden=true;this.controls();}
  async load(){
    if(!this.visible||!this.session||!this.root.offsetParent)return;if(this.loading){this.reloadRequested=true;return;}const session=this.session,current=++this.generation,zone=this.data.profile.data.zoneId;this.controller=new AbortController();const signal=this.controller.signal;this.loading=true;this.controls();
    try{
      const reply=await calendarTasks(session,signal);if(session!==this.session||current!==this.generation||zone!==this.data.profile.data.zoneId)return;
      this.sourceTasks=reply.items;this.warning.hidden=!Object.keys(reply.errors).length;this.error.hidden=true;
    }catch(error){if(!signal.aborted&&session===this.session&&current===this.generation){this.error.textContent=error.message;this.error.hidden=false;if(error.status===401)this.callbacks.onUnauthorized?.(error);}}
    finally{if(session===this.session&&current===this.generation){this.loading=false;this.controller=null;this.paint();this.controls();if(this.reloadRequested){this.reloadRequested=false;void this.load();}}}
  }
  filter(group,specs,today,keys){
    specs.forEach((spec,index)=>{
      const key=selectionKey(spec);keys.add(key);let choice=this.filterButtons.get(key);
      if(!choice){choice=button('',()=>{this.selection={kind:spec.kind,value:spec.value};this.visibleLimit=50;this.paint();if(this.narrow.matches){this.navigationState(false);this.listTitle.focus();}});choice.className='tasks-filter';this.filterButtons.set(key,choice);}
      const count=this.rows.filter(row=>taskMatches(row,spec,today)).length,selected=key===selectionKey(this.selection),badge=node('span',String(count),'badge text-bg-secondary');badge.setAttribute('aria-hidden','true');
      choice.replaceChildren(node('span',spec.label),badge);choice.setAttribute('aria-label',spec.label);choice.setAttribute('aria-description',t('tasks.count',{count}));choice.setAttribute('aria-pressed',String(selected));choice.classList.toggle('active',selected);
      if(group.children[index]!==choice)group.insertBefore(choice,group.children[index]||null);
    });
    for(const child of [...group.children])if(!specs.some(spec=>this.filterButtons.get(selectionKey(spec))===child))child.remove();
  }
  paint(){
    if(!this.data)return;const today=dateInZone(Date.now(),this.data.profile.data.zoneId),collator=new Intl.Collator(getLocale(),{numeric:true,sensitivity:'base'});this.rows=taskRows(this.data,this.sourceTasks);
    const smart=[['today','tasks.today'],['all','tasks.all'],['critical','tasks.critical']].map(([value,label])=>({kind:'smart',value,label:t(label)})),clients=this.data.clients.map(value=>({kind:'client',value:value.id,label:value.name}));
    if(this.rows.some(row=>!row.clientId))clients.push({kind:'client',value:null,label:t('ui.unassigned')});
    const categories=[...new Set(this.rows.flatMap(row=>row.categories))].sort(collator.compare).map(value=>({kind:'category',value,label:value})),all=[...smart,...clients,...categories];
    if(!all.some(value=>selectionKey(value)===selectionKey(this.selection)))this.selection={kind:'smart',value:'all'};
    const keys=new Set();this.filter(this.groups.smart,smart,today,keys);this.filter(this.groups.client,clients,today,keys);this.filter(this.groups.category,categories,today,keys);
    for(const [key,choice]of this.filterButtons)if(!keys.has(key)){choice.remove();this.filterButtons.delete(key);}
    this.listTitle.textContent=all.find(value=>selectionKey(value)===selectionKey(this.selection)).label;
    const visible=this.rows.filter(row=>taskMatches(row,this.selection,today)).sort((a,b)=>Number(['COMPLETED','CANCELLED'].includes(a.status))-Number(['COMPLETED','CANCELLED'].includes(b.status))||(a.dueDate||'9999').localeCompare(b.dueDate||'9999')||collator.compare(a.title,b.title)||a.key.localeCompare(b.key));
    this.count.textContent=t('tasks.count',{count:visible.length});this.empty.hidden=!!visible.length;this.more.hidden=visible.length<=this.visibleLimit;const visibleKeys=new Set();
    visible.slice(0,this.visibleLimit).forEach((row,index)=>{
      visibleKeys.add(row.key);let entry=this.rowElements.get(row.key);
      if(!entry){const item=node('li'),open=button('',()=>this.open(row.key)),title=node('strong'),context=node('span',undefined,'tasks-context'),timing=node('span',undefined,'tasks-timing'),status=node('span',undefined,'tasks-status');open.className='tasks-open';open.append(title,context,timing,status);item.append(open);item.dataset.taskKey=row.key;entry={item,button:open,title,context,timing,status};this.rowElements.set(row.key,entry);}
      entry.button.setAttribute('aria-label',t('tasks.open',{title:row.title}));entry.title.textContent=row.title;
      const client=this.data.clients.find(client=>client.id===row.clientId)?.name||t('ui.unassigned');entry.context.textContent=[client,row.projectName,row.source==='calendar'?row.record.calendarName:t('tasks.native')].filter(Boolean).join(' · ');
      entry.timing.textContent=[row.dueDate?t('tasks.due',{date:row.dueDate}):'',row.urgent?t('tasks.urgent'):'',row.recurring?t('tasks.recurring'):'',row.unresolvedTiming?t('tasks.unresolved'):''].filter(Boolean).join(' · ');entry.status.textContent=t('tasks.status.'+row.status);
      if(this.list.children[index]!==entry.item)this.list.insertBefore(entry.item,this.list.children[index]||null);
    });
    for(const [key,entry]of this.rowElements)if(!visibleKeys.has(key)){entry.item.remove();this.rowElements.delete(key);}
  }
  open(key){
    const row=this.rows.find(value=>value.key===key);if(!row)return;this.detailKey=key;this.detailTitle.textContent=row.title;this.detailBody.replaceChildren();
    const fields=node('dl',undefined,'task-metadata'),add=(label,text)=>{if(text){fields.append(node('dt',t(label)),node('dd',text));}};
    add('tasks.source',row.source==='calendar'?row.record.calendarName:(row.record.externalReferences||[]).map(value=>value.providerId).join(', ')||t('tasks.native'));
    add('ui.client',this.data.clients.find(client=>client.id===row.clientId)?.name||t('ui.unassigned'));add('ui.project',row.projectName);add('tasks.status_label',t('tasks.status.'+row.status));add('tasks.project_categories',row.categories.join(', '));
    const moment=value=>value?value.date+(value.kind==='DATE_TIME'?' '+value.time+' · '+value.timeZone:''):'';
    add('tasks.scheduled',row.scheduledDate);add('tasks.start',moment(row.record.start));add('tasks.due_label',moment(row.record.due));this.detailBody.append(fields);
    if(row.source==='calendar')this.detailBody.append(node('p',t('calendar.todo_read_only'),'hint'));
    if(row.unresolvedTiming)this.detailBody.append(node('p',t('tasks.unresolved_help'),'hint'));
    if(row.url){const link=node('a',t('tasks.open_source'));link.href=row.url;link.target='_blank';link.rel='noopener noreferrer';this.detailBody.append(link);}
    const body=row.source==='calendar'?row.record.description:row.record.body?.content||'';
    if(body){if(row.source==='calendar'||row.record.body?.format==='HTML')this.detailBody.append(calendarDescription(body));else{const text=node('p',undefined,'task-plain-body');text.append(linkedText(body));this.detailBody.append(text);}}
    this.edit.hidden=row.source!=='task';this.edit.disabled=!this.writable;this.detail.showModal();
  }
}

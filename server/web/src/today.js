import {dateTimeFormat} from './formatters.js';
import {getLocale,t} from './i18n.js';
import {addDays,dateInZone,instantNanos,instantText,nanosText,taskPresentation} from './model.js';
import {monthCalendar,monthSessions,shiftMonth} from './month-model.js';
import {iconButton} from './icons.js';
import {TrackDayNotes} from './track-day-notes.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const byId=id=>document.getElementById(id);

/** A selected civil day, with the journal in the main column. */
export class TodayView {
  constructor(root,callbacks){
    this.root=root;this.callbacks=callbacks;this.dateInput=byId('today-date');this.grid=byId('today-month-grid');this.monthTitle=byId('today-month-title');
    this.journal=new TrackDayNotes(byId('today-journal'),this.grid,this.dateInput,{onUnauthorized:callbacks.onUnauthorized,onChanged:callbacks.onNotesChanged},{inline:true,prefix:'today',statusAnchor:byId('today-note-status-anchor')});
    this.events=[];this.generation=0;this.visible=false;
    this.dateInput.addEventListener('change',()=>this.select(this.dateInput.value));
    for(const [id,days] of [['today-previous',-1],['today-next',1]])byId(id).addEventListener('click',()=>this.select(addDays(this.date,days)));
    byId('today-current').addEventListener('click',()=>this.select(dateInZone(Date.now(),this.data.profile.data.zoneId)));
    for(const [id,months] of [['today-month-previous',-1],['today-month-next',1]]){
      iconButton(byId(id),months<0?'chevron-left':'chevron-right',t(months<0?'ui.previous_month':'ui.next_month'));
      byId(id).addEventListener('click',()=>{this.month=shiftMonth(this.month,months);this.paintMonth();});
    }
  }
  initialize(date){this.date=date;this.month=date.slice(0,7);this.dateInput.value=date;}
  reset(){++this.generation;this.visible=false;this.scope=null;this.data=null;this.events=[];this.eventKey=null;this.journal.reset();for(const id of ['today-sessions','today-plans','today-planned-tasks','today-active-tasks'])byId(id).replaceChildren();}
  show(){this.visible=true;if(this.data){this.paint();this.eventKey=this.date+'|'+this.data.revision;void this.loadEvents();}}
  hide(){this.visible=false;++this.generation;}
  update(data,connected,scope){
    if(scope!==this.scope){const visible=this.visible;this.reset();this.visible=visible;this.scope=scope;}
    this.data=data;this.connected=connected;
    if(!this.date)this.initialize(dateInZone(data.report.generatedAt,data.profile.data.zoneId));
    if(this.visible){this.paint();const key=this.date+'|'+data.revision;if(key!==this.eventKey){this.eventKey=key;void this.loadEvents();}}
  }
  select(date){
    if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(Date.parse(date+'T12:00:00Z'))||new Date(date+'T12:00:00Z').toISOString().slice(0,10)!==date){this.dateInput.value=this.date;return;}
    if(date===this.date){this.dateInput.value=this.date;return;}
    if(!this.journal.canLeaveInline()){this.dateInput.value=this.date;return;}
    this.date=date;this.month=date.slice(0,7);this.dateInput.value=date;this.events=[];this.eventKey=null;++this.generation;
    this.paint();void this.loadEvents();
  }
  paint(){
    if(!this.data||!this.date)return;
    const zone=this.data.profile.data.zoneId;
    byId('today-zone').textContent=dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(this.date+'T12:00:00Z'))+' · '+zone;
    this.paintMonth();this.paintSessions();this.paintPlans();this.paintTasks();
    this.journal.showInline(this.date,this.scope,this.connected&&this.callbacks.canWrite());
  }
  paintMonth(){
    if(!this.data||!this.month)return;
    const calendar=monthCalendar(this.month,this.data.profile.data.zoneId),today=dateInZone(Date.now(),this.data.profile.data.zoneId);
    const totals=monthSessions(calendar,this.data.workSessions,this.data.report.generatedAt);
    this.monthTitle.textContent=dateTimeFormat(getLocale(),{month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(this.month+'-15T12:00:00Z'));
    const focus=this.grid.contains(document.activeElement)?document.activeElement.dataset.date:null;
    this.grid.replaceChildren();
    for(let day=0;day<7;day++)this.grid.append(node('span',dateTimeFormat(getLocale(),{weekday:'narrow',timeZone:'UTC'}).format(new Date(calendar.days[day].date+'T12:00:00Z')),'today-weekday'));
    for(const day of totals.days){
      const pick=node('button',String(Number(day.date.slice(-2))),'btn today-month-day');pick.type='button';pick.dataset.date=day.date;
      pick.classList.toggle('outside',!day.inMonth);pick.classList.toggle('selected',day.date===this.date);pick.classList.toggle('current',day.date===today);
      pick.setAttribute('aria-label',dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(day.date+'T12:00:00Z')));
      pick.setAttribute('aria-pressed',String(day.date===this.date));if(day.total>0n)pick.append(node('span',undefined,'today-month-dot'));
      pick.addEventListener('click',()=>this.select(day.date));this.grid.append(pick);
    }
    if(focus)this.grid.querySelector(`[data-date="${focus}"]`)?.focus({preventScroll:true});
  }
  paintSessions(){
    const root=byId('today-sessions'),zone=this.data.profile.data.zoneId,day=monthCalendar(this.date.slice(0,7),zone).days.find(value=>value.date===this.date),from=day.from,to=day.to;
    const sessions=this.data.workSessions.filter(value=>instantNanos(value.start)<to&&instantNanos(value.end||this.data.report.generatedAt)>from).sort((a,b)=>a.start.localeCompare(b.start));
    root.replaceChildren();if(!sessions.length){root.append(node('p',t('today.no_sessions'),'hint'));return;}
    const list=node('ul',undefined,'today-list');
    for(const session of sessions){
      const client=this.data.clients.find(value=>value.id===session.clientId),project=this.data.projects.find(value=>value.id===session.projectId);
      const start=instantNanos(session.start),end=instantNanos(session.end||this.data.report.generatedAt),duration=(end<to?end:to)-(start>from?start:from);
      const item=node('li',undefined,'today-session'),action=node('button',undefined,'btn btn-outline-secondary today-session-action');action.type='button';
      action.style.setProperty('--today-client-color',client?.color||'var(--accent)');
      action.append(node('strong',session.summary||project?.name||client?.name||t('ui.work_session_21aa04')),node('span',[(client?.name||t('ui.unassigned')),project?.name].filter(Boolean).join(' · '),'hint'));
      const timing=node('span',instantText(session.start,zone)+' → '+(session.end?instantText(session.end,zone):t('ui.in_progress')),'hint');action.append(timing,node('span',nanosText(duration),'today-duration'));
      action.addEventListener('click',()=>this.callbacks.onEditSession(session.id));item.append(action);list.append(item);
    }
    root.append(list);
  }
  paintPlans(){
    const root=byId('today-plans');root.replaceChildren();
    const events=this.events.filter(value=>value.allDay&&value.firstDate<=this.date&&this.date<value.endDate);
    if(!events.length){root.append(node('p',t('today.no_plans'),'hint'));return;}
    const list=node('ul',undefined,'today-list');for(const event of events){const item=node('li',undefined,'today-plan');item.append(node('strong',event.title||t('calendar.untitled')),node('span',event.clientName+' · '+event.calendarName,'hint'));if(event.description)item.append(node('p',event.description,'today-plan-description'));list.append(item);}root.append(list);
  }
  paintTasks(){
    const planned=byId('today-planned-tasks'),active=byId('today-active-tasks');planned.replaceChildren();active.replaceChildren();
    // Task dates belong to the provider-compatible task model in issue #413.
    planned.append(node('p',t('today.planning_unavailable'),'hint'));
    const current=this.data.active.data,task=current&&this.data.tasks.find(value=>value.id===current.taskId);
    this.taskList(active,task?[task]:[],t('today.no_active_tasks'));
  }
  taskList(root,tasks,empty){
    if(!tasks.length){root.append(node('p',empty,'hint'));return;}
    const list=node('ul',undefined,'today-list');for(const task of tasks){const item=node('li',undefined,'today-task'),presentation=taskPresentation(task),title=node(presentation.url?'a':'span',presentation.title);if(presentation.url){title.href=presentation.url;title.target='_blank';title.rel='noopener noreferrer';}item.append(title);const project=this.data.projects.find(value=>value.id===task.projectId);if(project)item.append(node('span',project.name,'hint'));list.append(item);}root.append(list);
  }
  async loadEvents(){
    if(!this.visible||!this.scope||!this.date)return;
    const generation=++this.generation,scope=this.scope,date=this.date;
    byId('today-plans').replaceChildren(node('p',t('calendar.loading'),'hint'));
    try{
      const reply=await scope.request('/calendar-events?'+new URLSearchParams({fromDate:date,toDate:addDays(date,1)}));
      if(generation!==this.generation||scope!==this.scope||date!==this.date)return;
      this.events=reply.data.items;this.paintPlans();
    }catch(error){
      if(generation!==this.generation||scope!==this.scope)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      byId('today-plans').replaceChildren(node('p',error.message,'alert alert-warning'));
    }
  }
}

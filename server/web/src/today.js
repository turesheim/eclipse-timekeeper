import {dailyNoteDateFromSearch,dailyNoteHref} from './journal-links.js';
import {focusIncludes} from './focus-model.js';
import {calendarTodo,calendarItemStatus,calendarItemWhen} from './calendar-item.js';
import {EventNoteEditor,eventNoteButton,eventNoteIndicator} from './event-note-editor.js';
import {dateTimeFormat} from './formatters.js';
import {getLocale,t} from './i18n.js';
import {addDays,dateInZone,instantNanos,instantText,nanosText,taskPresentation} from './model.js';
import {clientColors} from './calendar-model.js';
import {monthCalendar,monthSessions,shiftMonth} from './month-model.js';
import {iconButton,isProjectIcon,projectIcon} from './icons.js';
import {TrackDayNotes} from './track-day-notes.js';
import {WeekNoteEditor,isoWeek} from './week-note-editor.js';
import {workSummaryCard} from './work-summary-card.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const byId=id=>document.getElementById(id);

/** A selected civil day, with the journal in the main column. */
export class TodayView {
  constructor(root,callbacks){
    this.root=root;this.callbacks=callbacks;this.dateInput=byId('today-date');this.grid=byId('today-month-grid');this.monthTitle=byId('today-month-title');this.journalRoot=byId('today-journal');
    this.journal=new TrackDayNotes(byId('today-journal'),this.grid,this.dateInput,{onUnauthorized:callbacks.onUnauthorized,onChanged:callbacks.onNotesChanged},{inline:true,prefix:'today',statusAnchor:byId('today-note-status-anchor'),labelRoot:byId('today-day-label')});
    this.weekEditor=new WeekNoteEditor(root,callbacks);
    this.eventDetails=node('dialog',undefined,'app-dialog shadow-lg calendar-event-details');this.eventDetails.id='today-event-details';this.eventDetails.setAttribute('aria-labelledby','today-event-title');root.append(this.eventDetails);
    this.eventDetails.addEventListener('close',()=>{const origin=this.eventOrigin;this.eventOrigin=null;this.eventDetails.replaceChildren();if(this.visible&&origin?.isConnected)origin.focus();});
    this.eventNoteEditor=new EventNoteEditor(root,{...callbacks,onNotesChanged:()=>{void this.loadEvents();callbacks.onNotesChanged();}},'today');
    this.events=[];this.generation=0;this.visible=false;this.daySessions=new Map();this.noteDates=new Set();this.noteWeeks=new Set();this.noteGeneration=0;this.monthNoteKey=null;
    this.picker=node('dialog',undefined,'app-dialog shadow-lg');this.picker.id='today-session-picker';
    const pickerTitle=node('h2',t('ui.choose_work_session'));pickerTitle.id='today-session-picker-title';this.picker.setAttribute('aria-labelledby',pickerTitle.id);
    this.pickerContext=node('p');this.choices=node('div',undefined,'d-grid gap-2');
    const pickerActions=node('div',undefined,'dialog-actions'),cancel=node('button',t('ui.cancel'),'btn btn-outline-secondary');cancel.type='button';cancel.addEventListener('click',()=>this.closePicker());pickerActions.append(cancel);
    this.picker.append(pickerTitle,this.pickerContext,this.choices,pickerActions);root.append(this.picker);
    this.picker.addEventListener('close',()=>{const origin=this.pickerOrigin;this.pickerOrigin=null;this.picking=null;this.choices.replaceChildren();if(origin&&this.visible&&!document.querySelector('#editor[open]'))root.querySelector(`[data-today-client="${origin}"]`)?.focus();});
    this.dateInput.addEventListener('change',()=>this.select(this.dateInput.value));
    iconButton(byId('today-date-choose'),'calendar',t('today.choose_date')).addEventListener('click',()=>{
      try {if(typeof this.dateInput.showPicker==='function')this.dateInput.showPicker();else this.dateInput.focus();}
      catch {this.dateInput.focus();}
    });
    for(const [id,glyph,label] of [['today-previous','left','today.previous_day'],['today-current','clock','ui.today'],['today-next','right','today.next_day']])iconButton(byId(id),glyph,t(label));
    for(const [id,days] of [['today-previous',-1],['today-next',1]])byId(id).addEventListener('click',()=>this.select(addDays(this.date,days)));
    byId('today-current').addEventListener('click',()=>this.select(dateInZone(Date.now(),this.data.profile.data.zoneId)));
    for(const [id,months] of [['today-month-previous',-1],['today-month-next',1]]){
      iconButton(byId(id),months<0?'chevron-left':'chevron-right',t(months<0?'ui.previous_month':'ui.next_month'));
      byId(id).addEventListener('click',()=>{this.month=shiftMonth(this.month,months);this.paintMonth();void this.loadMonthNotes();});
    }
    window.addEventListener('resize',()=>this.sizeEditor());
  }
  initialize(date){this.date=date;this.month=date.slice(0,7);this.dateInput.value=date;}
  reset(){clearInterval(this.eventTimer);this.eventTimer=null;this.eventWarning=false;++this.generation;++this.noteGeneration;this.visible=false;this.scope=null;this.data=null;this.events=[];this.eventKey=null;this.monthNoteKey=null;this.noteDates.clear();this.noteWeeks.clear();this.daySessions.clear();this.closePicker();this.closeEvent();this.weekEditor.close(true);this.eventNoteEditor.close(true);this.journal.reset();for(const id of ['today-sessions','today-events','today-planned-tasks'])byId(id).replaceChildren();}
  pollEvents(){if(!this.eventTimer&&this.scope&&this.visible)this.eventTimer=setInterval(()=>void this.loadEvents(true),30000);}
  show(){this.visible=true;this.pollEvents();this.sizeEditor();if(this.data){this.paint();this.eventKey=this.date+'|'+this.data.revision;void this.loadEvents();}}
  hide(){clearInterval(this.eventTimer);this.eventTimer=null;this.visible=false;++this.generation;++this.noteGeneration;this.monthNoteKey=null;this.closePicker();this.closeEvent();this.weekEditor.close();this.eventNoteEditor.close();}
  update(data,connected,scope){
    if(scope!==this.scope){const visible=this.visible;this.reset();this.visible=visible;this.scope=scope;}
    this.data=data;this.connected=connected;
    if(!this.date)this.initialize(dateInZone(data.report.generatedAt,data.profile.data.zoneId));
    if(this.visible){this.pollEvents();this.paint();const key=this.date+'|'+data.revision;if(key!==this.eventKey){this.eventKey=key;void this.loadEvents();}}
  }
  select(date,{confirmed=false}={}){
    if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(Date.parse(date+'T12:00:00Z'))||new Date(date+'T12:00:00Z').toISOString().slice(0,10)!==date){this.dateInput.value=this.date;return false;}
    if(date===this.date){this.dateInput.value=this.date;return true;}
    if(!confirmed&&!this.journal.canLeaveInline()){this.dateInput.value=this.date;return false;}
    this.closePicker();this.closeEvent();this.date=date;this.month=date.slice(0,7);this.dateInput.value=date;this.eventWarning=false;this.events=[];this.eventKey=null;++this.generation;
    this.paint();void this.loadEvents();if(dailyNoteDateFromSearch(location.search))history.replaceState(history.state,'',dailyNoteHref(date));return true;
  }
  paint(){
    if(!this.data||!this.date)return;
    byId('today-title').textContent=dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(this.date+'T12:00:00Z'));
    this.paintMonth();void this.loadMonthNotes();this.paintSessions();this.paintEvents();this.paintTasks();
    this.journal.showInline(this.date,this.scope,this.connected&&this.callbacks.canWrite());this.sizeEditor();
  }
  sizeEditor(){
    if(!this.visible||!this.root.offsetParent)return;
    const top=this.journalRoot.getBoundingClientRect().top+scrollY,navHeight=byId('workspace-navigation').getBoundingClientRect().height;
    this.root.style.setProperty('--today-editor-height',Math.max(0,innerHeight-top-navHeight)+'px');
  }
  paintMonth(){
    if(!this.data||!this.month)return;
    const calendar=monthCalendar(this.month,this.data.profile.data.zoneId),today=dateInZone(Date.now(),this.data.profile.data.zoneId);
    this.monthTitle.textContent=dateTimeFormat(getLocale(),{month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(this.month+'-15T12:00:00Z'));
    const focus=this.grid.contains(document.activeElement)?document.activeElement.dataset.date:null;
    this.grid.replaceChildren();
    this.grid.append(node('span',t('today.week_short'),'today-weekday'));
    for(let day=0;day<7;day++)this.grid.append(node('span',dateTimeFormat(getLocale(),{weekday:'narrow',timeZone:'UTC'}).format(new Date(calendar.days[day].date+'T12:00:00Z')),'today-weekday'));
    for(let index=0;index<calendar.days.length;index++){
      const day=calendar.days[index];
      if(index%7===0){const week=isoWeek(day.date),pick=node('button',String(Number(week.slice(-2))),'btn today-week-number');pick.type='button';pick.dataset.week=week;pick.setAttribute('aria-label',t('today.week_note',{week}));pick.title=t('today.week_note',{week});if(this.noteWeeks.has(week))pick.append(node('span',undefined,'today-month-dot'));pick.addEventListener('click',()=>void this.weekEditor.open(week,this.scope,this.connected&&this.callbacks.canWrite()));this.grid.append(pick);}
      const pick=node('button',String(Number(day.date.slice(-2))),'btn today-month-day');pick.type='button';pick.dataset.date=day.date;
      pick.classList.toggle('outside',!day.inMonth);pick.classList.toggle('selected',day.date===this.date);pick.classList.toggle('current',day.date===today);
      pick.setAttribute('aria-label',dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(day.date+'T12:00:00Z')));
      pick.setAttribute('aria-pressed',String(day.date===this.date));if(this.noteDates.has(day.date))pick.append(node('span',undefined,'today-month-dot'));
      pick.addEventListener('click',()=>this.select(day.date));this.grid.append(pick);
    }
    if(focus)this.grid.querySelector(`[data-date="${focus}"]`)?.focus({preventScroll:true});
  }
  async loadMonthNotes(){
    if(!this.visible||!this.scope||!this.data||!this.month)return;
    const key=this.month+'|'+this.data.revision;if(key===this.monthNoteKey)return;
    this.monthNoteKey=key;this.noteDates.clear();this.noteWeeks.clear();this.paintMonth();
    const generation=++this.noteGeneration,scope=this.scope,calendar=monthCalendar(this.month,this.data.profile.data.zoneId);
    const first=calendar.days[0].date,last=calendar.days.at(-1).date;
    try{
      const [reply,weeks]=await Promise.all([scope.request('/day-notes?'+new URLSearchParams({fromDate:first,toDate:last})),scope.request('/week-notes?'+new URLSearchParams({fromWeek:isoWeek(first),toWeek:isoWeek(last)}))]);
      if(generation!==this.noteGeneration||scope!==this.scope)return;
      this.noteDates=new Set(reply.data.items.filter(note=>note.hasBody).map(note=>note.date));this.noteWeeks=new Set(weeks.data.items.filter(note=>note.hasBody).map(note=>note.week));this.paintMonth();
    }catch(error){
      if(generation!==this.noteGeneration||scope!==this.scope)return;
      if(error.status===401)this.callbacks.onUnauthorized(error);
    }
  }
  paintSessions(){
    const root=byId('today-sessions'),zone=this.data.profile.data.zoneId,calendar=monthCalendar(this.date.slice(0,7),zone);
    const day=monthSessions(calendar,this.data.workSessions,this.data.report.generatedAt).days.find(value=>value.date===this.date);
    const sessionsById=new Map(this.data.workSessions.map(session=>[session.id,session]));
    this.daySessions=new Map([...day.clients.values()].map(entry=>[entry.clientId,entry.sessionIds.map(id=>sessionsById.get(id)).filter(Boolean).sort((a,b)=>a.start.localeCompare(b.start)||a.id.localeCompare(b.id))]));
    root.replaceChildren();if(!day.clients.size){root.append(node('p',t('today.no_sessions'),'hint'));this.paintPicker();return;}
    const list=node('ul',undefined,'today-list');
    const entries=[...day.clients.values()].sort((a,b)=>{
      const name=id=>this.data.clients.find(client=>client.id===id)?.name||t('ui.client');
      return name(a.clientId).localeCompare(name(b.clientId),getLocale())||a.clientId.localeCompare(b.clientId);
    });
    for(const entry of entries){
      const client=this.data.clients.find(value=>value.id===entry.clientId),sessions=this.daySessions.get(entry.clientId),writable=this.connected&&this.callbacks.canWrite();
      const item=node('li',undefined,'today-session'),{card:action,line:details,duration}=workSummaryCard({tag:writable?'button':'div',className:'today-session-action',title:client?.name||t('ui.client'),color:client?.color});
      action.dataset.todayClient=entry.clientId;if(writable)action.type='button';else action.setAttribute('role','group');
      details.classList.add('today-session-details');duration.classList.add('today-duration');const icons=node('span',undefined,'track-project-icons'),projects=new Map();
      for(const session of sessions){const project=this.data.projects.find(value=>value.id===session.projectId);if(isProjectIcon(project?.icon)){const names=projects.get(project.icon)||new Set();names.add(project.name);projects.set(project.icon,names);}}
      for(const [id,names] of projects)icons.append(projectIcon({icon:id,name:[...names].join(', ')}));icons.setAttribute('aria-hidden','true');
      duration.textContent=nanosText(entry.duration);details.append(icons);
      action.title=(client?.name||t('ui.client'))+'\n'+t('count.work_sessions',{count:sessions.length})+'\n'+sessions.map(session=>[session.summary||t('ui.work_session_21aa04'),instantText(session.start,zone)+' → '+(session.end?instantText(session.end,zone):t('ui.in_progress'))].join(' · ')).join('\n');
      if(writable)action.addEventListener('click',()=>this.openSessions(entry.clientId));item.append(action);list.append(item);
    }
    root.append(list);this.paintPicker();
  }
  openSessions(clientId){
    if(!this.connected||!this.callbacks.canWrite())return;
    const sessions=this.daySessions.get(clientId)||[];
    if(sessions.length===1){this.callbacks.onEditSession(sessions[0].id);return;}
    if(!sessions.length)return;
    this.picking=clientId;this.pickerOrigin=clientId;this.paintPicker();this.picker.showModal();this.choices.querySelector('button')?.focus();
  }
  closePicker(){if(this.picker.open)this.picker.close();this.picking=null;this.choices.replaceChildren();}
  paintPicker(){
    if(!this.picking)return;
    const clientId=this.picking,sessions=this.daySessions.get(clientId)||[];
    if(!sessions.length){this.closePicker();return;}
    const focused=document.activeElement?.dataset.todaySessionChoice,zone=this.data.profile.data.zoneId;
    this.pickerContext.textContent=`${this.data.clients.find(client=>client.id===clientId)?.name||t('ui.client')} · ${this.date}`;
    this.choices.replaceChildren();
    for(const session of sessions){
      const choice=node('button',undefined,'btn btn-outline-secondary text-start text-break w-100');choice.type='button';choice.dataset.todaySessionChoice=session.id;
      choice.append(node('strong',session.summary||t('ui.work_session_21aa04'),'d-block'));
      const project=this.data.projects.find(value=>value.id===session.projectId);if(project){const line=node('span',undefined,'d-flex align-items-center gap-2 text-body-secondary'),glyph=projectIcon(project);if(glyph){glyph.setAttribute('aria-hidden','true');line.append(glyph);}line.append(node('span',project.name));choice.append(line);}
      choice.append(node('small',instantText(session.start,zone)+' → '+(session.end?instantText(session.end,zone):t('ui.in_progress')),'d-block text-body-secondary'));
      choice.addEventListener('click',()=>{if(!this.connected||!this.callbacks.canWrite()||!this.daySessions.get(clientId)?.some(value=>value.id===session.id))return;this.closePicker();this.callbacks.onEditSession(session.id);});
      this.choices.append(choice);
    }
    if(focused)([...this.choices.children].find(choice=>choice.dataset.todaySessionChoice===focused)||this.choices.firstElementChild)?.focus();
  }
  paintEvents(){
    const root=byId('today-events'),focused=root.contains(document.activeElement)?document.activeElement.closest('[data-today-event]')?.dataset.todayEvent:null;root.replaceChildren();
    if(this.eventWarning)root.append(node('p',t('calendar.refresh_errors'),'alert alert-warning'));
    const zone=this.data.profile.data.zoneId,day=monthCalendar(this.date.slice(0,7),zone).days.find(value=>value.date===this.date);
    const events=this.events.filter(value=>focusIncludes(this.data,value.clientId)).filter(value=>value.allDay?value.firstDate<=this.date&&this.date<value.endDate:instantNanos(value.start)<day.to&&instantNanos(value.end)>=day.from)
      .sort((a,b)=>Number(b.allDay)-Number(a.allDay)||(a.start||'').localeCompare(b.start||''));
    if(!events.length){root.append(node('p',t('today.no_events'),'hint'));return;}
    const when=event=>calendarItemWhen(event,zone,true);
    const list=node('ul',undefined,'today-list');for(const event of events){
      const item=node('li',undefined,'today-plan'),action=node('button',undefined,'calendar-summary-card today-event-action');action.type='button';action.dataset.todayEvent=event.key;
      const client=this.data.clients.find(value=>value.id===event.clientId);action.style.setProperty('--client-color',clientColors(client?.color||event.color).background);
      const title=node('strong',event.title||t('calendar.untitled')),marker=eventNoteIndicator(event);if(marker)title.append(' ',marker);action.append(title);
      action.append(node('span',when(event),'calendar-summary-time'));
      if(calendarTodo(event))action.append(node('span',calendarItemStatus(event)));
      action.append(node('span',client?.name||event.clientName));
      action.title=[event.title||t('calendar.untitled'),when(event),client?.name||event.clientName].join('\n');
      action.addEventListener('click',()=>this.openEvent(event,action));item.append(action);list.append(item);
    }root.append(list);
    if(focused&&document.activeElement===document.body)root.querySelector(`[data-today-event="${focused}"]`)?.focus({preventScroll:true});
  }
  closeEvent(){this.eventOrigin=null;if(this.eventDetails.open)this.eventDetails.close();this.eventDetails.replaceChildren();}
  openEvent(event,origin){
    this.eventOrigin=origin;const details=this.eventDetails;details.replaceChildren();
    const title=node('h2',event.title||t('calendar.untitled'));title.id='today-event-title';
    const when=calendarItemWhen(event,this.data.profile.data.zoneId);
    const client=this.data.clients.find(value=>value.id===event.clientId);
    details.append(title,node('p',when),node('p',[client?.name||event.clientName,event.calendarName].filter(Boolean).join(' · '),'hint'));
    if(calendarTodo(event))details.append(node('p',calendarItemStatus(event)));
    if(event.location)details.append(node('p',t('calendar.location',{location:event.location})));
    if(event.description)details.append(node('p',event.description,'calendar-description'));
    const actions=node('div',undefined,'dialog-actions'),close=node('button',t('ui.close'),'btn btn-outline-secondary');close.type='button';close.addEventListener('click',()=>details.close());actions.append(eventNoteButton(event,()=>void this.eventNoteEditor.open(event,this.scope,this.connected&&this.callbacks.canWrite(),this.data.profile.data.zoneId)),close);details.append(actions);
    details.showModal();close.focus();
  }
  paintTasks(){
    const planned=byId('today-planned-tasks');planned.replaceChildren();
    const tasks=this.data.tasks.filter(task=>task.status!=='COMPLETED');
    this.taskList(planned,tasks.filter(task=>task.scheduledDate===this.date),t('today.no_planned_tasks'));
  }
  taskList(root,tasks,empty){
    if(!tasks.length){root.append(node('p',empty,'hint'));return;}
    const list=node('ul',undefined,'today-list');for(const task of tasks){const item=node('li',undefined,'today-task'),presentation=taskPresentation(task),title=node(presentation.url?'a':'span',presentation.title);if(presentation.url){title.href=presentation.url;title.target='_blank';title.rel='noopener noreferrer';}item.append(title);const project=this.data.projects.find(value=>value.id===task.projectId);if(project)item.append(node('span',project.name,'hint'));list.append(item);}root.append(list);
  }
  async loadEvents(background=false){
    if(!this.visible||!this.scope||!this.date)return;
    const generation=++this.generation,scope=this.scope,date=this.date;
    if(!background)byId('today-events').replaceChildren(node('p',t('calendar.loading'),'hint'));
    try{
      const [reply,subscriptions]=await Promise.all([scope.request('/calendar-events?'+new URLSearchParams({fromDate:date,toDate:addDays(date,1)})),scope.request('/calendar-subscriptions')]);
      if(generation!==this.generation||scope!==this.scope||date!==this.date)return;
      this.events=reply.data.items;this.eventWarning=Object.keys(reply.data.errors||{}).length>0||subscriptions.data.items.some(item=>item.visible&&item.lastError);this.paintEvents();
    }catch(error){
      if(generation!==this.generation||scope!==this.scope)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      byId('today-events').replaceChildren(node('p',error.message,'alert alert-warning'));
    }
  }
}

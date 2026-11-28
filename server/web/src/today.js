import {NoteContents} from './note-contents.js';
import {calendarSourceFailure} from './calendar-source-status.js';
import {SidebarActions} from './sidebar-actions.js';
import {SidebarResize} from './sidebar-resize.js';
import {calendarDescription,calendarLocation} from './tooltip-description.js';
import {richTooltip} from './tooltips.js';
import {workSessionsTooltip,calendarEventTooltip} from './tooltip-content.js';
import {dailyNoteDateFromSearch,dailyNoteHref} from './journal-links.js';
import {focusIncludes} from './focus-model.js';
import {calendarTodo,calendarItemStatus,calendarItemWhen} from './calendar-item.js';
import {EventNoteEditor,eventNoteButton,eventNoteIndicator} from './event-note-editor.js';
import {dateTimeFormat} from './formatters.js';
import {getLocale,t} from './i18n.js';
import {publicHolidayCalendar,selectedHolidayCountry,weeklyHoliday} from './holidays.js';
import {addDays,dateInZone,instantNanos,instantText,nanosText,taskPresentation} from './model.js';
import {clientColors} from './calendar-model.js';
import {monthCalendar,monthSessions,shiftMonth} from './month-model.js';
import {iconButton,isProjectIcon,projectIcon} from './icons.js';
import {TrackDayNotes} from './track-day-notes.js';
import {TodayJournalViewport} from './today-journal-viewport.js';
import {WeekNoteEditor,isoWeek,isoWeekStart,savedWeeks} from './week-note-editor.js';
import {workSummaryCard} from './work-summary-card.js';
import {taskMomentDate} from './tasks-model.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const byId=id=>document.getElementById(id);

/** A selected civil day, with the journal in the main column. */
export class TodayView {
  constructor(root,callbacks){
    this.root=root;this.callbacks=callbacks;this.title=byId('today-title');this.grid=byId('today-month-grid');this.monthTitle=byId('today-month-title');this.journalRoot=byId('today-journal');
    const phone=matchMedia('(max-width:650px)'),mainActions=byId('today-main-actions'),dayLabel=byId('today-day-label'),calendar=dayLabel.parentElement;
    this.sidebar=root.querySelector('.today-sidebar');this.sidebar.id='today-sidebar';
    this.sidebarResize=new SidebarResize(root,this.sidebar,{variable:'--today-sidebar-width',label:t('today.resize_sidebar'),width:196,changed:()=>this.sizeEditor(),focusFallback:()=>this.title,className:'today-resize'}).element;
    this.sidebar.after(this.sidebarResize);
    this.stacked=matchMedia('(max-width:1050px)');
    this.dayLabel=dayLabel;
    this.dayActions=node('div',undefined,'today-document-actions');
    this.weekActions=node('div',undefined,'today-document-actions');
    this.weekActions.hidden=true;
    mainActions.append(this.dayActions,this.weekActions);
    this.journalNavigation=0;
    this.journal=new TrackDayNotes(byId('today-journal'),this.grid,this.title,{onUnauthorized:callbacks.onUnauthorized,onChanged:callbacks.onNotesChanged,onContentState:()=>this.contents?.update()},{mainActions:this.dayActions,compactTools:phone,inline:true,prefix:'today',statusAnchor:byId('today-note-status-anchor'),labelRoot:dayLabel});
    this.journalViewport=new TodayJournalViewport(root,phone,this.journal.markdown,()=>this.visible);
    this.contents=new NoteContents(byId('today-outline'),this.journal.markdown);
    this.contentsResize=new SidebarResize(root,byId('today-contents'),{side:'right',variable:'--today-contents-width',label:t('today.resize_contents'),changed:()=>this.sizeEditor(),focusFallback:()=>byId('today-main-actions-toggle-right-sidebar'),className:'today-contents-resize'}).element;
    byId('today-contents').before(this.contentsResize);
    this.sidebarActions=new SidebarActions(mainActions,root.querySelector('.today-layout'),{
      left:{panel:this.sidebar,separator:this.sidebarResize,expandedInFixedLayout:true},
      right:{panel:byId('today-contents'),separator:this.contentsResize,collapseOnNarrow:true}
    },()=>this.sizeEditor(),{fixedLayout:phone,focusFallback:()=>this.title});
    this.weekEditor=new WeekNoteEditor(this.journalRoot,{...callbacks,
      onContentState:()=>this.contents.update(),
      onClosed:restoreFocus=>{
        this.syncJournal();
        if(!restoreFocus||!this.visible)return;
        const week=this.weekEditor.context.week,origin=this.grid.querySelector(`[data-week="${week}"]`);
        (origin?.getClientRects().length?origin:this.title).focus({preventScroll:!this.stacked.matches});
      },
    },{phone,actions:this.weekActions});
    this.journalViewport.watch(this.weekEditor.markdown);
    this.eventDetails=node('dialog',undefined,'app-dialog shadow-lg calendar-event-details');this.eventDetails.id='today-event-details';this.eventDetails.setAttribute('aria-labelledby','today-event-title');root.append(this.eventDetails);
    this.eventDetails.addEventListener('close',()=>{const origin=this.eventOrigin;this.eventOrigin=null;this.eventDetails.replaceChildren();if(this.visible&&origin?.isConnected)origin.focus();});
    this.eventNoteEditor=new EventNoteEditor(root,{...callbacks,onNotesChanged:()=>{void this.loadEvents();callbacks.onNotesChanged();}},'today');
    this.events=[];this.generation=0;this.visible=false;this.daySessions=new Map();this.noteDates=new Set();this.dayLabels=new Map();this.noteWeeks=new Set();this.noteGeneration=0;this.monthNoteKey=null;
    this.picker=node('dialog',undefined,'app-dialog shadow-lg');this.picker.id='today-session-picker';
    const pickerTitle=node('h2',t('ui.choose_work_session'));pickerTitle.id='today-session-picker-title';this.picker.setAttribute('aria-labelledby',pickerTitle.id);
    this.pickerContext=node('p');this.choices=node('div',undefined,'d-grid gap-2');
    const pickerActions=node('div',undefined,'dialog-actions'),cancel=node('button',t('ui.cancel'),'btn btn-outline-secondary');cancel.type='button';cancel.addEventListener('click',()=>this.closePicker());pickerActions.append(cancel);
    this.picker.append(pickerTitle,this.pickerContext,this.choices,pickerActions);root.append(this.picker);
    this.picker.addEventListener('close',()=>{const origin=this.pickerOrigin;this.pickerOrigin=null;this.picking=null;this.choices.replaceChildren();if(origin&&this.visible&&!document.querySelector('#editor[open]'))root.querySelector(`[data-today-client="${origin}"]`)?.focus();});
    const applyPhoneLayout=()=>{
      if(phone.matches)mainActions.before(dayLabel);
      else calendar.append(dayLabel);
      this.sizeEditor();
    };
    phone.addEventListener('change',applyPhoneLayout);
    applyPhoneLayout();
    byId('today-current').addEventListener('click',()=>{
      if(this.data)void this.selectCalendarDay(dateInZone(Date.now(),this.data.profile.data.zoneId));
    });
    for(const [id,months] of [['today-month-previous',-1],['today-month-next',1]]){
      iconButton(byId(id),months<0?'chevron-left':'chevron-right',t(months<0?'ui.previous_month':'ui.next_month'));
      byId(id).addEventListener('click',()=>{this.month=shiftMonth(this.month,months);this.paintMonth();void this.loadMonthNotes();});
    }
    window.addEventListener('resize',()=>this.sizeEditor());
    // Fonts, translated headings and session details can resize without a
    // viewport event. Both columns keep their own distance to the fixed dock.
    this.layoutObserver=new ResizeObserver(()=>this.sizeEditor());
    for(const target of [root.querySelector('.today-toolbar'),byId('work-session'),byId('workspace-navigation'),document.querySelector('.app-header'),document.querySelector('.tracking'),byId('today-contents')])if(target)this.layoutObserver.observe(target);
  }
  initialize(date){this.date=date;this.month=date.slice(0,7);}
  reset(){clearInterval(this.eventTimer);this.eventTimer=null;this.eventWarning=false;this.eventFailures=[];this.eventsLoaded=false;this.eventRenderKey=null;++this.generation;++this.noteGeneration;++this.journalNavigation;this.visible=false;this.journalViewport.reset();this.scope=null;this.data=null;this.events=[];this.eventKey=null;this.eventRange=null;delete byId('today-planned-tasks').dataset.groups;this.monthNoteKey=null;this.monthGridKey=null;this.monthHolidays=null;this.grid.replaceChildren();this.noteDates.clear();this.dayLabels?.clear();this.noteWeeks.clear();this.daySessions.clear();this.plannedRows?.clear();this.closePicker();this.closeEvent();this.weekEditor.reset();this.eventNoteEditor.reset();this.journal.reset();this.contents.reset();this.sidebarActions.reset();for(const id of ['today-sessions','today-events','today-planned-tasks'])byId(id).replaceChildren();}
  pollEvents(){if(!this.eventTimer&&this.scope&&this.visible)this.eventTimer=setInterval(()=>void this.loadEvents(true),30000);}
  show(){this.visible=true;this.contents.show();this.pollEvents();this.sizeEditor();if(this.data){if(this.connected&&this.journal.editor?.date===this.date)void this.journal.refreshClean();this.eventKey=null;this.paint();}}
  hide(){this.contents.hide();clearInterval(this.eventTimer);this.eventTimer=null;this.visible=false;this.journalViewport.reset();++this.generation;++this.noteGeneration;++this.journalNavigation;this.monthNoteKey=null;this.closePicker();this.closeEvent();this.eventNoteEditor.close();}
  setConnected(value){this.connected=value;const writable=this.callbacks.canWrite();this.journal.setWritable(value&&writable,writable);this.weekEditor.writable=value&&writable;this.weekEditor.controls();}
  update(data,connected,scope){
    if(scope!==this.scope){const visible=this.visible;this.reset();this.visible=visible;this.scope=scope;if(visible)this.contents.show();}
    this.data=data;this.connected=connected;
    if(!this.date)this.initialize(dateInZone(data.report.generatedAt,data.profile.data.zoneId));
    if(this.visible){this.pollEvents();this.paint();}
  }
  isDayOpen(date){return date===this.date&&!this.weekEditor.opened;}
  canLeaveJournal(){
    return this.weekEditor.opened?this.weekEditor.canLeave():this.journal.canLeaveInline();
  }
  async openWeek(week){
    const scope=this.scope,navigation=++this.journalNavigation;
    if(!scope||this.weekEditor.context?.week===week&&this.weekEditor.opened)return;
    if(!await this.canLeaveJournal()||scope!==this.scope||navigation!==this.journalNavigation)return;
    void this.weekEditor.close(true);
    const opening=this.weekEditor.open(week,scope,this.connected&&this.callbacks.canWrite());
    this.syncJournal();
    this.focusJournalFromCalendar();
    await opening;
  }
  syncJournal(){
    const weekly=this.weekEditor.opened,field=weekly?this.weekEditor.markdown:this.journal.markdown;
    this.journal.dialog.hidden=this.dayLabel.hidden=this.dayActions.hidden=weekly;
    this.weekActions.hidden=!weekly;
    if(this.contents.field!==field){
      this.contents.reset();this.contents.field=field;
      if(this.visible)this.contents.show();
      this.journalViewport.use(field);
    }
    if(!this.date||!this.data)return;
    this.title.dataset.date=this.date;
    this.title.textContent=weekly?this.weekEditor.title.textContent:
      dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(this.date+'T12:00:00Z'));
    this.paintMonth();this.refreshSidebar();this.sizeEditor();
  }
  focusJournalFromCalendar(){
    if(!this.visible||!this.stacked.matches)return;
    this.title.focus({preventScroll:true});
    this.title.scrollIntoView({block:'nearest',behavior:'instant'});
  }
  async selectCalendarDay(date){
    if(await this.select(date))this.focusJournalFromCalendar();
  }
  async select(date,{confirmed=false}={}){
    if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(Date.parse(date+'T12:00:00Z'))||new Date(date+'T12:00:00Z').toISOString().slice(0,10)!==date)return false;
    const navigation=++this.journalNavigation,scope=this.scope,month=date.slice(0,7);
    if(this.isDayOpen(date)){
      if(this.month!==month){
        this.month=month;
        this.paintMonth();void this.loadMonthNotes();
      }
      return true;
    }
    if(!confirmed&&!await this.canLeaveJournal())return false;
    if(scope!==this.scope||navigation!==this.journalNavigation)return false;
    const changingDate=date!==this.date;
    this.month=month;
    void this.weekEditor.close(true);
    if(changingDate){
      this.closePicker();this.closeEvent();this.date=date;
      this.paint();if(dailyNoteDateFromSearch(location.search))history.replaceState(history.state,'',dailyNoteHref(date));
    }else void this.loadMonthNotes();
    return true;
  }
  paint(){
    if(!this.data||!this.date)return;
    this.journal.showInline(this.date,this.scope,this.connected&&this.callbacks.canWrite(),this.callbacks.canWrite(),this.data.profile.data);
    this.syncJournal();void this.loadMonthNotes();
  }
  sizeEditor(){
    if(!this.visible||!this.root.offsetParent)return;
    const workspace=byId('workspace'),dock=workspace.dataset.navigation==='side'?document.querySelector('.app-header'):byId('workspace-navigation');
    const top=this.journalRoot.getBoundingClientRect().top+scrollY,navHeight=dock.getBoundingClientRect().height;
    this.root.style.setProperty('--today-editor-height',Math.max(0,innerHeight-top-navHeight)+'px');
    const sidebarTop=this.root.querySelector('.today-sidebar').getBoundingClientRect().top+scrollY;
    this.root.style.setProperty('--today-sidebar-height',Math.max(0,innerHeight-sidebarTop-navHeight)+'px');
    const contentsTop=byId('today-contents').getBoundingClientRect().top+scrollY;
    this.root.style.setProperty('--today-contents-height',Math.max(0,innerHeight-contentsTop-navHeight)+'px');
    if(this.sidebarResize.offsetParent)this.sidebarResize.setAttribute('aria-valuenow',String(Math.round(this.sidebar.getBoundingClientRect().width)));
  }
  paintMonth(){
    if(!this.data||!this.month)return;
    const zone=this.data.profile.data.zoneId,key=JSON.stringify([this.month,zone,getLocale()]),rebuild=key!==this.monthGridKey;
    const focus=this.grid.contains(document.activeElement)?{date:document.activeElement.dataset.date,week:document.activeElement.dataset.week}:null;
    if(rebuild){
      this.monthGridKey=key;this.grid.dataset.month=this.month;const calendar=this.monthCalendar=monthCalendar(this.month,zone);
      this.monthTitle.textContent=dateTimeFormat(getLocale(),{month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(this.month+'-15T12:00:00Z'));
      this.grid.replaceChildren();this.grid.append(node('span',t('today.week_short'),'today-weekday'));
      for(let day=0;day<7;day++)this.grid.append(node('span',dateTimeFormat(getLocale(),{weekday:'narrow',timeZone:'UTC'}).format(new Date(calendar.days[day].date+'T12:00:00Z')),'today-weekday'));
      for(let index=0;index<calendar.days.length;index++){
        const day=calendar.days[index];
        if(index%7===0){const week=isoWeek(day.date),pick=node('button',String(Number(week.slice(-2))),'btn today-week-number');pick.type='button';pick.dataset.week=week;pick.setAttribute('aria-label',Number(week.slice(-2))+' · '+t('today.week_note',{week}));pick.title=t('today.week_note',{week});pick.addEventListener('click',()=>void this.openWeek(week));this.grid.append(pick);}
        const pick=node('button',String(Number(day.date.slice(-2))),'btn today-month-day');pick.type='button';pick.dataset.date=day.date;pick.classList.toggle('outside',!day.inMonth);
        pick.dataset.fullDate=dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(day.date+'T12:00:00Z'));pick.addEventListener('click',()=>void this.selectCalendarDay(day.date));this.grid.append(pick);
      }
    }
    this.loadMonthHolidays();
    // Note refreshes update decorations without disconnecting a pressed or focused button.
    const week=this.weekEditor.opened?this.weekEditor.context.week:null;
    const today=dateInZone(Date.now(),zone),dot=(pick,present)=>{const previous=pick.querySelector('.today-month-dot');if(present&&!previous)pick.append(node('span',undefined,'today-month-dot'));else if(!present&&previous)previous.remove();};
    for(const pick of this.grid.querySelectorAll('[data-date]')){
      const date=pick.dataset.date,label=this.dayLabels.get(date),holidays=this.monthHolidays.data?.days.get(date);
      const restDay=weeklyHoliday(date,getLocale());
      const names=holidays?.map(holiday=>holiday.name).join(' · ');
      pick.classList.toggle('selected',!week&&date===this.date);
      pick.classList.toggle('current',date===today);
      if(date===today)pick.setAttribute('aria-current','date');else pick.removeAttribute('aria-current');
      pick.classList.toggle('holiday',!!holidays||restDay);
      pick.classList.toggle('labeled',!!label);
      pick.setAttribute('aria-pressed',String(!week&&date===this.date));
      const details=[names??(restDay?t('holidays.weekly_rest'):null),label].filter(Boolean);
      pick.title=details.join(' · ');
      pick.setAttribute('aria-label',[pick.dataset.fullDate,...details].join(' · '));
      dot(pick,this.noteDates.has(date));
    }
    for(const pick of this.grid.querySelectorAll('[data-week]')){
      pick.setAttribute('aria-pressed',String(pick.dataset.week===week));dot(pick,this.noteWeeks.has(pick.dataset.week));
    }
    if(rebuild&&focus){const selector=focus.date?`[data-date="${focus.date}"]`:focus.week?`[data-week="${focus.week}"]`:null;if(selector)this.grid.querySelector(selector)?.focus({preventScroll:true});}
  }
  loadMonthHolidays(){
    const locale=getLocale(),country=selectedHolidayCountry(this.data.profile.data.holidayCountry);
    const key=JSON.stringify([this.monthGridKey,country]);
    if(this.monthHolidays?.key===key)return;
    const pending=this.monthHolidays={key,data:null};
    if(!country)return;
    void publicHolidayCalendar(this.monthCalendar,locale,country).then(data=>{
      if(this.monthHolidays!==pending)return;
      pending.data=data;
      if(this.visible)this.paintMonth();
    }).catch(()=>{
      if(this.monthHolidays===pending)this.monthHolidays=null;
    });
  }
  async loadMonthNotes(){
    if(!this.visible||!this.scope||!this.data||!this.month)return;
    const key=this.month+'|'+this.data.revision;if(key===this.monthNoteKey)return;
    this.monthNoteKey=key;this.noteDates.clear();this.dayLabels.clear();this.noteWeeks.clear();this.paintMonth();
    const generation=++this.noteGeneration,scope=this.scope,calendar=monthCalendar(this.month,this.data.profile.data.zoneId);
    const first=calendar.days[0].date,last=calendar.days.at(-1).date;
    try{
      const [reply,weeks]=await Promise.all([scope.request('/day-notes?'+new URLSearchParams({fromDate:first,toDate:last})),savedWeeks(scope,first,last)]);
      if(generation!==this.noteGeneration||scope!==this.scope)return;
      this.dayLabels=new Map(reply.data.items.filter(note=>note.text).map(note=>[note.date,note.text]));
      this.noteDates=new Set(reply.data.items.filter(note=>note.hasBody).map(note=>note.date));this.noteWeeks=weeks;this.paintMonth();
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
      action.dataset.todayClient=entry.clientId;if(writable)action.type='button';else {action.setAttribute('role','group');action.tabIndex=0;}
      details.classList.add('today-session-details');duration.classList.add('today-duration');const icons=node('span',undefined,'track-project-icons'),projects=new Map();
      for(const session of sessions){const project=this.data.projects.find(value=>value.id===session.projectId);if(isProjectIcon(project?.icon)){const names=projects.get(project.icon)||new Set();names.add(project.name);projects.set(project.icon,names);}}
      for(const [id,names] of projects)icons.append(projectIcon({icon:id,name:[...names].join(', ')}));icons.setAttribute('aria-hidden','true');
      duration.textContent=nanosText(entry.duration);details.append(icons);
      richTooltip(action,()=>workSessionsTooltip({client,sessions,projects:this.data.projects,zone,asOf:this.data.report.generatedAt,total:entry.duration,date:this.date}));
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
  sidebarRange(){
    const week=this.weekEditor.opened,first=week?isoWeekStart(this.weekEditor.context.week):this.date;
    return {week,first,end:addDays(first,week?7:1)};
  }
  refreshSidebar(){
    const range=this.sidebarRange(),{week,first,end}=range;
    byId('today-sessions').closest('section').hidden=week;
    byId('today-events-title').textContent=t(week?'today.week_events':'today.events');
    byId('today-planned-tasks-title').textContent=t(week?'today.week_tasks':'today.planned_tasks');
    if(!week)this.paintSessions();
    else this.closePicker();
    this.paintTasks(range);
    const eventRange=JSON.stringify([first,end,this.data.profile.data.zoneId]);
    const key=JSON.stringify([eventRange,this.data.revision]);
    if(this.eventRange!==eventRange){
      ++this.generation;
      this.eventRange=eventRange;this.events=[];this.eventFailures=[];this.eventWarning=false;this.eventsLoaded=false;
      this.eventRenderKey=null;this.closeEvent();
    }
    if(this.eventsLoaded)this.paintEvents(range);
    if(this.visible&&this.scope&&key!==this.eventKey){
      this.eventKey=key;
      // A forced close and subsequent open can happen in the same turn.
      queueMicrotask(()=>{if(this.eventKey===key)void this.loadEvents();});
    }
  }
  groupHeading(date){
    const heading=node('h4',dateTimeFormat(getLocale(),{weekday:'long',day:'numeric',month:'short',timeZone:'UTC'})
      .format(new Date(date+'T12:00:00Z')),'today-group-heading');
    return heading;
  }
  paintEvents({week,first,end}=this.sidebarRange()){
    const zone=this.data.profile.data.zoneId,visible=this.events.filter(value=>focusIncludes(this.data,value.clientId));
    const failures=(this.eventFailures||[]).filter(value=>focusIncludes(this.data,value.clientId));
    const key=JSON.stringify([first,end,zone,getLocale(),this.eventWarning,failures,visible.map(event=>{
      const client=this.data.clients.find(value=>value.id===event.clientId);
      return [event,client?.name,client?.color];
    })]);
    if(key===this.eventRenderKey)return;
    this.eventRenderKey=key;
    const root=byId('today-events'),focused=root.contains(document.activeElement)?document.activeElement.closest('[data-today-event]')?.dataset.todayEventInstance:null;
    root.replaceChildren();
    if(failures.length){const warning=node('div',undefined,'alert alert-warning');warning.setAttribute('role','status');warning.append(...failures.map(value=>calendarSourceFailure(value,t('calendar.error.'+value.lastError))));root.append(warning);}
    else if(this.eventWarning)root.append(node('p',t('calendar.refresh_errors'),'alert alert-warning'));
    const ordered=visible.filter(value=>!week||!value.undated)
      .sort((a,b)=>Number(b.allDay)-Number(a.allDay)||(a.start||'').localeCompare(b.start||''));
    let hasEvents=false;
    const days=monthCalendar(first.slice(0,7),zone).days.filter(day=>day.date>=first&&day.date<end);
    for(const day of days){
      const events=ordered.filter(value=>value.allDay?value.firstDate<=day.date&&day.date<value.endDate:
        day.from<day.to&&instantNanos(value.start)<day.to&&
        (!week||value.start===value.end?instantNanos(value.end)>=day.from:instantNanos(value.end)>day.from));
      if(!events.length)continue;
      hasEvents=true;
      const list=node('ul',undefined,'today-list');
      if(week)root.append(this.groupHeading(day.date));
      for(const event of events){
        const item=node('li',undefined,'today-plan'),action=node('button',undefined,'calendar-summary-card today-event-action');
        action.type='button';action.dataset.todayEvent=event.key;action.dataset.todayEventInstance=JSON.stringify([day.date,event.subscriptionId,event.key]);
        const client=this.data.clients.find(value=>value.id===event.clientId);action.style.setProperty('--client-color',clientColors(client?.color||event.color).background);
        const title=node('strong',event.title||t('calendar.untitled')),marker=eventNoteIndicator(event);if(marker)title.append(' ',marker);action.append(title);
        action.append(node('span',calendarItemWhen(event,zone,true),'calendar-summary-time'));
        if(calendarTodo(event))action.append(node('span',calendarItemStatus(event)));
        action.append(node('span',client?.name||event.clientName));
        richTooltip(action,()=>calendarEventTooltip(event,zone));
        action.addEventListener('click',()=>this.openEvent(event,action));item.append(action);list.append(item);
      }
      root.append(list);
    }
    if(!hasEvents)root.append(node('p',t(week?'today.no_week_events':'today.no_events'),'hint'));
    if(focused&&document.activeElement===document.body)
      [...root.querySelectorAll('[data-today-event]')].find(node=>node.dataset.todayEventInstance===focused)?.focus({preventScroll:true});
  }
  closeEvent(){this.eventOrigin=null;if(this.eventDetails.open)this.eventDetails.close();this.eventDetails.replaceChildren();}
  openEvent(event,origin){
    this.eventOrigin=origin;const details=this.eventDetails;details.replaceChildren();
    const title=node('h2',event.title||t('calendar.untitled'));title.id='today-event-title';
    const when=calendarItemWhen(event,this.data.profile.data.zoneId);
    const client=this.data.clients.find(value=>value.id===event.clientId);
    details.append(title,node('p',when),node('p',[client?.name||event.clientName,event.calendarName].filter(Boolean).join(' · '),'hint'));
    if(calendarTodo(event))details.append(node('p',calendarItemStatus(event)));
    if(event.location){const location=node('p');location.append(calendarLocation(t('calendar.location',{location:event.location})));details.append(location);}
    if(event.description){const description=calendarDescription(event.description);description.classList.add('calendar-description');details.append(description);}
    const actions=node('div',undefined,'dialog-actions'),close=node('button',t('ui.close'),'btn btn-outline-secondary');close.type='button';close.addEventListener('click',()=>details.close());actions.append(eventNoteButton(event,()=>void this.eventNoteEditor.open(event,this.scope,this.connected&&this.callbacks.canWrite(),this.data.profile.data.zoneId)),close);details.append(actions);
    details.showModal();close.focus();
  }
  paintTasks({week,first,end}){
    const root=byId('today-planned-tasks'),groups=new Map(),zone=this.data.profile.data.zoneId;
    for(const task of this.data.tasks){
      if(task.status==='COMPLETED')continue;
      const date=week?taskMomentDate(task.due,zone).date:task.scheduledDate;
      if(!date||date<first||date>=end)continue;
      if(!groups.has(date))groups.set(date,[]);
      groups.get(date).push(task);
    }
    const dates=[...groups.keys()].sort(),key=JSON.stringify([week,dates,getLocale()]);
    const focused=root.contains(document.activeElement)?document.activeElement.dataset.todayTask:null;
    if(root.dataset.groups!==key){
      root.dataset.groups=key;root.replaceChildren();this.plannedRows?.clear();
      for(const date of dates){
        if(week)root.append(this.groupHeading(date));
        const list=node('ul',undefined,'today-list');list.dataset.taskGroup=date;root.append(list);
      }
      if(!dates.length)root.append(node('p',t(week?'today.no_week_tasks':'today.no_planned_tasks'),'hint'));
    }
    if(!dates.length)return;
    this.plannedRows??=new Map();
    const keys=new Set();
    for(const list of root.querySelectorAll('[data-task-group]'))groups.get(list.dataset.taskGroup).forEach((task,index)=>{
      keys.add(task.id);let entry=this.plannedRows.get(task.id);
      if(!entry){
        const item=node('li',undefined,'today-task'),action=node('button',undefined,'calendar-summary-card today-planned-task'),title=node('strong'),context=node('span');
        action.type='button';action.dataset.todayTask=task.id;action.append(title,context);item.append(action);
        action.addEventListener('click',()=>this.callbacks.onOpenTask(task.id,action));entry={item,action,title,context};this.plannedRows.set(task.id,entry);
      }
      const presentation=taskPresentation(task),project=this.data.projects.find(value=>value.id===task.projectId),client=this.data.clients.find(value=>value.id===(project?.clientId||task.clientId));
      if(entry.title.textContent!==presentation.title)entry.title.textContent=presentation.title;
      entry.action.setAttribute('aria-label',t('tasks.open',{title:presentation.title}));entry.action.style.setProperty('--client-color',clientColors(client?.color).background);
      const context=[client?.name,project?.name].filter(Boolean).join(' · ');if(entry.context.textContent!==context)entry.context.textContent=context;if(entry.context.hidden!==!context)entry.context.hidden=!context;
      if(list.children[index]!==entry.item)list.insertBefore(entry.item,list.children[index]||null);
    });
    for(const [id,entry]of this.plannedRows)if(!keys.has(id)){entry.item.remove();this.plannedRows.delete(id);}
    if(focused&&document.activeElement===document.body)this.plannedRows.get(focused)?.action.focus({preventScroll:true});
  }
  async loadEvents(background=false){
    if(!this.visible||!this.scope||!this.date)return;
    const generation=++this.generation,scope=this.scope,range=this.sidebarRange();
    if(!background&&!this.eventsLoaded){this.eventRenderKey=null;byId('today-events').replaceChildren(node('p',t('calendar.loading'),'hint'));}
    try{
      const [reply,subscriptions]=await Promise.all([scope.request('/calendar-events?'+new URLSearchParams({fromDate:range.first,toDate:range.end})),scope.request('/calendar-subscriptions')]);
      if(generation!==this.generation||scope!==this.scope)return;
      this.eventsLoaded=true;this.events=reply.data.items;this.eventFailures=subscriptions.data.items.filter(item=>item.visible&&(item.lastError||reply.data.errors?.[item.id])).map(item=>({...item,lastError:item.lastError||reply.data.errors[item.id]}));this.eventWarning=Object.keys(reply.data.errors||{}).length>0||this.eventFailures.length>0;this.paintEvents();
    }catch(error){
      if(generation!==this.generation||scope!==this.scope)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      this.eventRenderKey=null;byId('today-events').replaceChildren(node('p',error.message,'alert alert-warning'));
    }
  }
}

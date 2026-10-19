import {dateInZone,instantNanos,instantText} from './model.js';
import {monthCalendar,monthSessions,shiftMonth} from './month-model.js';
import {iconButton,projectIcon,isProjectIcon} from './icons.js';
import {MonthPicker} from './month-picker.js';

const node=(tag,text,className)=> {const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
function compactTime(value) {
  const seconds=value/1000000000n,hours=seconds/3600n,minutes=seconds/60n%60n;
  return hours ? `${hours}h${minutes||''}` : minutes ? `${minutes}m` : seconds ? `${seconds}s` : '<1s';
}
export class MonthTrack {
  constructor(root,callbacks) {
    this.callbacks=callbacks;
    this.root=root;this.input=root.querySelector('#track-month');
    this.grid=root.querySelector('#track-calendar');this.totals=root.querySelector('#track-clients');
    this.monthPicker=new MonthPicker(root,()=>this.currentMonth(),month=>this.select(month));
    for(const [id,glyph,label,offset] of [['track-previous','left','Previous month',-1],['track-next','right','Next month',1]]) {
      const button=root.querySelector('#'+id);iconButton(button,glyph,label);
      button.addEventListener('click',()=>this.select(shiftMonth(this.currentMonth(),offset)));
    }
    iconButton(root.querySelector('#track-today'),'clock','Current month').addEventListener('click',()=>this.select(null));
    const choose=report=> {
      try {
        if(this.input.value===this.currentMonth()){this.input.setCustomValidity('');return;}
        this.calendar=monthCalendar(this.input.value,this.snapshot.profile.data.zoneId);this.select(this.input.value);
      } catch {if(report){this.input.setCustomValidity('Choose a valid month.');this.input.reportValidity();}}
    };
    this.input.addEventListener('input',()=>choose(false));
    this.input.addEventListener('change',()=>choose(true));
    this.narrow=matchMedia('(max-width: 767px)');this.narrow.addEventListener('change',()=>{if(this.snapshot)this.paint(false);});
    this.picker=node('dialog',undefined,'app-dialog shadow-lg');this.picker.id='track-session-picker';
    const title=node('h2','Choose work session');title.id='track-session-picker-title';this.picker.setAttribute('aria-labelledby',title.id);
    this.pickerContext=node('p');this.choices=node('div',undefined,'d-grid gap-2');
    const actions=node('div',undefined,'dialog-actions'),cancel=node('button','Cancel','btn btn-outline-secondary');cancel.type='button';cancel.addEventListener('click',()=>this.closePicker());actions.append(cancel);
    this.picker.append(title,this.pickerContext,this.choices,actions);root.append(this.picker);
    this.picker.addEventListener('close',()=>{
      if(this.picker.open)return;
      const origin=this.pickerOrigin;this.pickerOrigin=null;this.picking=null;this.choices.replaceChildren();
      if(origin && this.snapshot && !this.root.hidden && !document.querySelector('#editor[open]'))(this.writable && this.dailyTimes.get(origin.date+'/'+origin.clientId)?.summary || this.input).focus();
    });
  }
  currentDate() {return dateInZone(Number(this.asOf/1000000n),this.snapshot.profile.data.zoneId);}
  currentMonth() {return this.month||this.currentDate().slice(0,7);}
  select(month) {this.closePicker();this.monthPicker.close();this.month=month;this.input.setCustomValidity('');this.paint(true);}
  update(snapshot,asOf,format,writable) {
    this.snapshot=snapshot;this.sessionsById=new Map(snapshot.workSessions.map(session=>[session.id,session]));this.asOf=asOf;this.format=format;this.setWritable(writable);this.paint(true);
  }
  setWritable(writable) {
    this.writable=writable;
    for(const button of this.grid.querySelectorAll('button')) button.disabled=!writable;
    if(!writable)this.closePicker();
  }
  hide() {this.closePicker();this.monthPicker.close();}
  closePicker() {this.picking=null;if(this.picker.open)this.picker.close();this.choices.replaceChildren();}
  sessions(date,clientId) {
    const ids=new Set(this.days?.find(day=>day.date===date)?.clients.get(clientId)?.sessionIds || []);
    return [...ids].map(id=>this.sessionsById.get(id)).filter(Boolean).sort((a,b)=>{const first=instantNanos(a.start),second=instantNanos(b.start);return first<second ? -1:first>second ? 1:a.id.localeCompare(b.id);});
  }
  details(date,clientId) {
    const sessions=this.sessions(date,clientId),zone=this.snapshot.profile.data.zoneId;
    return `${sessions.length} work ${sessions.length===1 ? 'session':'sessions'}\n`+sessions.map(session=>{
      const project=this.snapshot.projects.find(project=>project.id===session.projectId);
      const labels=(session.labelIds || []).map(id=>this.snapshot.labels.find(label=>label.id===id)?.name).filter(Boolean);
      return [session.summary || 'Work session',project?.name,labels.join(', '),
        `${instantText(session.start,zone)} → ${session.end ? instantText(session.end,zone) : 'In progress'}`,
        session.end ? this.format(instantNanos(session.end)-instantNanos(session.start)):null].filter(Boolean).join('\n');
    }).join('\n\n');
  }
  open(date,clientId) {
    if(!this.writable)return;
    const sessions=this.sessions(date,clientId);
    if(sessions.length===1){this.callbacks.onEditSession(sessions[0].id);return;}
    if(!sessions.length)return;
    this.picking={date,clientId};this.pickerOrigin=this.picking;this.paintPicker();this.picker.showModal();this.choices.querySelector('button')?.focus();
  }
  paintPicker() {
    if(!this.picking)return;
    const {date,clientId}=this.picking,sessions=this.sessions(date,clientId);
    if(!sessions.length){this.closePicker();return;}
    const focused=document.activeElement?.dataset.trackSessionChoice;
    this.pickerContext.textContent=`${this.snapshot.clients.find(client=>client.id===clientId)?.name || 'Client'} · ${date}`;
    this.choices.replaceChildren();
    for(const session of sessions) {
      const choice=node('button',undefined,'btn btn-outline-secondary text-start text-break w-100');choice.type='button';choice.dataset.trackSessionChoice=session.id;
      choice.append(node('strong',session.summary || 'Work session','d-block'));
      const project=this.snapshot.projects.find(project=>project.id===session.projectId);
      if(project)choice.append(node('span',project.name,'d-block text-body-secondary'));
      choice.append(node('small',`${instantText(session.start,this.snapshot.profile.data.zoneId)} → ${session.end ? instantText(session.end,this.snapshot.profile.data.zoneId) : 'In progress'}`,'d-block text-body-secondary'));
      choice.addEventListener('click',()=>{
        if(!this.writable || !this.sessions(date,clientId).some(value=>value.id===session.id))return;
        this.closePicker();this.callbacks.onEditSession(session.id);
      });
      this.choices.append(choice);
    }
    if(focused)([...this.choices.children].find(choice=>choice.dataset.trackSessionChoice===focused)||this.choices.firstElementChild)?.focus();
  }
  reset() {
    this.monthPicker.close();
    this.closePicker();this.days=null;this.sessionsById?.clear();this.writable=false;this.pickerOrigin=null;this.pickerContext.textContent='';
    this.snapshot=null;this.month=null;this.calendar=null;this.layout=null;this.grid.replaceChildren();this.totals.replaceChildren();
    this.dailyTimes?.clear();this.clientTimes?.clear();this.input.value='';this.input.setCustomValidity('');
    this.root.querySelector('#track-total').textContent='';this.root.querySelector('#track-zone').textContent='';this.root.querySelector('#track-title').textContent='Your month';
  }
  tick(asOf) {
    if(!this.snapshot||this.root.hidden)return;
    this.asOf=asOf;
    if(this.snapshot.activeWorkSession.data||this.currentDate()!==this.date)this.paint(false);
  }
  paint(force) {
    if(!this.snapshot)return;
    const month=this.currentMonth(),zone=this.snapshot.profile.data.zoneId,today=this.currentDate();
    if(this.calendar?.month!==month||this.calendar?.zone!==zone)this.calendar=monthCalendar(month,zone);
    const result=monthSessions(this.calendar,this.snapshot.workSessions,this.asOf);
    this.days=result.days;
    const layout=month+'|'+zone+'|'+today+'|'+[...result.clients.keys()].sort().join(',')+'|'+result.days.map(day=>[...day.clients.keys()].sort().join(',')).join(';');
    if(force||this.layout!==layout) {
      const active=document.activeElement,focused=active?.dataset.trackDailyClient && this.grid.contains(active) ? {date:active.closest('[data-track-date]').dataset.trackDate,client:active.dataset.trackDailyClient}:null;
      this.layout=layout;this.date=today;this.input.value=month;
      const title=new Intl.DateTimeFormat(undefined,{month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(month+'-15T12:00:00Z'));
      this.root.querySelector('#track-title').textContent=title.charAt(0).toLocaleUpperCase()+title.slice(1);
      this.root.querySelector('#track-zone').textContent=zone;
      this.grid.replaceChildren();this.totals.replaceChildren();this.dailyTimes=new Map();this.clientTimes=new Map();
      const table=node('table',undefined,'track-month'),head=node('thead'),row=node('tr'),body=node('tbody');
      table.setAttribute('aria-label','Work hours by client and day');
      for(let index=0;index<7;index++) {
        const date=new Date(Date.UTC(2024,0,1+index)),th=node('th',new Intl.DateTimeFormat(undefined,{weekday:'short',timeZone:'UTC'}).format(date));
        th.scope='col';th.title=new Intl.DateTimeFormat(undefined,{weekday:'long',timeZone:'UTC'}).format(date);row.append(th);
      }
      head.append(row);table.append(head,body);this.grid.append(table);
      const clientsById=new Map(this.snapshot.clients.map(client=>[client.id,client]));
      const projectsById=new Map(this.snapshot.projects.map(project=>[project.id,project]));
      let week;
      for(const [index,day] of result.days.entries()) {
        if(index%7===0){week=node('tr');body.append(week);}
        const cell=node('td',undefined,day.inMonth ? 'track-day':'track-day track-outside');cell.dataset.trackDate=day.date;
        const date=node('time',String(Number(day.date.slice(-2))),'track-date');date.dateTime=day.date;
        date.title=new Intl.DateTimeFormat(undefined,{dateStyle:'full',timeZone:'UTC'}).format(new Date(day.date+'T12:00:00Z'));
        if(day.date===today){date.classList.add('track-today');date.setAttribute('aria-current','date');}
        cell.append(date);week.append(cell);
        const entries=[...day.clients.values()].sort((a,b)=>{
          const name=id=>clientsById.get(id)?.name || 'Client';
          return name(a.clientId).localeCompare(name(b.clientId)) || a.clientId.localeCompare(b.clientId);
        });
        for(const entry of entries) {
          const client=clientsById.get(entry.clientId);
          const summary=node(this.writable ? 'button':'div',undefined,'track-client-day');summary.dataset.trackDailyClient=entry.clientId;
          if(this.writable){summary.type='button';summary.setAttribute('aria-haspopup','dialog');summary.addEventListener('click',()=>this.open(day.date,entry.clientId));}
          else summary.setAttribute('role','group');
          summary.setAttribute('aria-label',`${client?.name || 'Client'} · ${day.date}`);
          if(/^#[0-9a-f]{6}$/i.test(client?.color)) summary.style.setProperty('--client-color',client.color);
          const duration=node('span',undefined,'track-client-day-time'),short=node('span',undefined,'track-client-day-time-short');short.setAttribute('aria-hidden','true');
          this.dailyTimes.set(day.date+'/'+entry.clientId,{duration,short,summary,name:client?.name || 'Client',details:this.details(day.date,entry.clientId)});
          const line=node('span',undefined,'track-client-day-total'),icons=node('span',undefined,'track-project-icons'),projects=new Map();
          for(const session of this.sessions(day.date,entry.clientId)) {
            const project=projectsById.get(session.projectId);
            if(isProjectIcon(project?.icon)) {const names=projects.get(project.icon)||new Set();names.add(project.name);projects.set(project.icon,names);}
          }
          for(const [id,names] of projects)icons.append(projectIcon({icon:id,name:[...names].join(', ')}));
          icons.setAttribute('aria-hidden','true');line.append(duration,short,icons);summary.append(node('strong',client?.name || 'Client'),' ',line);
          if(entry.running) summary.append(node('span','Running','visually-hidden'));
          cell.append(summary);
        }
      }
      const clients=this.snapshot.clients.filter(client=>result.clients.has(client.id)).sort((a,b)=>a.name.localeCompare(b.name));
      for(const client of clients) {
        const card=node('div',undefined,'card track-client'),label=node('span',client.name,'client-color'),total=node('strong');
        if(/^#[0-9a-f]{6}$/i.test(client.color))label.style.setProperty('--client-color',client.color);
        card.dataset.trackClient=client.id;card.append(label,total);this.totals.append(card);this.clientTimes.set(client.id,total);
      }
      if(!clients.length)this.totals.append(node('p','No work sessions this month.','hint'));
      if(focused)this.dailyTimes.get(focused.date+'/'+focused.client)?.summary.focus();
      this.paintPicker();
    }
    this.root.querySelector('#track-total').textContent=this.format(result.total);
    for(const [client,total] of this.clientTimes)total.textContent=this.format(result.clients.get(client)||0n);
    for(const day of result.days)for(const entry of day.clients.values()) {
      const {duration,short,summary,name,details}=this.dailyTimes.get(day.date+'/'+entry.clientId);
      summary.setAttribute('aria-label',`${name} ${this.narrow.matches ? compactTime(entry.duration):this.format(entry.duration)} · ${day.date}${this.narrow.matches ? ' · '+this.format(entry.duration):''}${entry.running ? ' · Running':''}`);
      summary.title=`${name} · ${day.date} · ${this.format(entry.duration)}\n${details}`;
      duration.textContent=this.format(entry.duration);
      short.textContent=compactTime(entry.duration);
    }
  }
}

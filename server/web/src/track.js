import {dateInZone,instantNanos} from './model.js';
import {monthCalendar,monthSessions,shiftMonth} from './month-model.js';
import {iconButton} from './icons.js';

const node=(tag,text,className)=> {const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
function compactTime(value) {
  const seconds=value/1000000000n,hours=seconds/3600n,minutes=seconds/60n%60n;
  return hours ? `${hours}h${minutes||''}` : minutes ? `${minutes}m` : seconds ? `${seconds}s` : '<1s';
}
export class MonthTrack {
  constructor(root) {
    this.root=root;this.input=root.querySelector('#track-month');
    this.grid=root.querySelector('#track-calendar');this.totals=root.querySelector('#track-clients');
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
  }
  currentDate() {return dateInZone(Number(this.asOf/1000000n),this.snapshot.profile.data.zoneId);}
  currentMonth() {return this.month||this.currentDate().slice(0,7);}
  select(month) {this.month=month;this.input.setCustomValidity('');this.paint(true);}
  update(snapshot,asOf,format,writable) {
    this.snapshot=snapshot;this.asOf=asOf;this.format=format;this.writable=writable;this.paint(true);
  }
  setWritable() {} // Daily summaries have no write actions, including in read-only sessions.
  reset() {
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
    const layout=month+'|'+zone+'|'+today+'|'+result.days.map(day=>[...day.clients.keys()].sort().join(',')).join(';');
    if(force||this.layout!==layout) {
      this.layout=layout;this.date=today;this.input.value=month;
      this.root.querySelector('#track-title').textContent=new Intl.DateTimeFormat(undefined,{month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(month+'-15T12:00:00Z'));
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
          const summary=node('div',undefined,'track-client-day');summary.dataset.trackDailyClient=entry.clientId;
          summary.setAttribute('role','group');summary.setAttribute('aria-label',`${client?.name || 'Client'} · ${day.date}`);
          if(/^#[0-9a-f]{6}$/i.test(client?.color)) summary.style.setProperty('--client-color',client.color);
          const duration=node('span',undefined,'track-client-day-time'),short=node('span',undefined,'track-client-day-time-short');short.setAttribute('aria-hidden','true');
          this.dailyTimes.set(day.date+'/'+entry.clientId,{duration,short,summary,name:client?.name || 'Client'});
          summary.append(node('strong',client?.name || 'Client'),' ',duration,short);
          if(entry.running) summary.append(node('span','Running','visually-hidden'));
          cell.append(summary);
        }
      }
      const clients=[...this.snapshot.clients].sort((a,b)=>a.name.localeCompare(b.name));
      for(const client of clients) {
        const card=node('div',undefined,'card track-client'),label=node('span',client.name,'client-color'),total=node('strong');
        if(/^#[0-9a-f]{6}$/i.test(client.color))label.style.setProperty('--client-color',client.color);
        card.dataset.trackClient=client.id;card.append(label,total);this.totals.append(card);this.clientTimes.set(client.id,total);
      }
      if(!clients.length)this.totals.append(node('p','No clients yet.','hint'));
    }
    this.root.querySelector('#track-total').textContent=this.format(result.total);
    for(const [client,total] of this.clientTimes)total.textContent=this.format(result.clients.get(client)||0n);
    for(const day of result.days)for(const entry of day.clients.values()) {
      const {duration,short,summary,name}=this.dailyTimes.get(day.date+'/'+entry.clientId);
      summary.setAttribute('aria-label',`${name} · ${day.date} · ${this.format(entry.duration)}${entry.running ? ' · Running':''}`);
      duration.textContent=this.format(entry.duration);
      short.textContent=compactTime(entry.duration);
    }
  }
}

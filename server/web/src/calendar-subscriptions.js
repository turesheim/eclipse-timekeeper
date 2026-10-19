import {getLocale,t} from './i18n.js';
import {addDays,instantText} from './model.js';
import {icon,iconButton} from './icons.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(label,action,classes='btn btn-outline-secondary')=>{const value=node('button',label,classes);value.type='button';value.addEventListener('click',action);return value;};
const errorText=error=>error?.message||t('calendar.error.CALENDAR_CONNECTION_FAILED');
export const calendarFailure=code=>t('calendar.error.'+code);

/** Authenticated calendar loading and dialogs never replace an open activity draft. */
export class CalendarSubscriptions {
  constructor(root,callbacks) {
    this.root=root;this.callbacks=callbacks;this.generation=0;this.rows=[];this.events=[];this.sources={};this.expanded=null;this.busy=new Set();
    this.panel=node('section',undefined,'card calendar-subscriptions');this.panel.setAttribute('aria-labelledby','calendar-subscriptions-title');
    const heading=node('div',undefined,'calendar-subscriptions-heading'),title=node('h3');title.id='calendar-subscriptions-title';
    this.toggle=button(t('calendar.subscriptions'),()=>{this.expanded=!(this.expanded??!this.rows.length);this.paint();},'btn btn-link');this.toggle.id='calendar-subscriptions-toggle';this.toggle.setAttribute('aria-controls','calendar-subscriptions-content');title.append(this.toggle);
    this.add=button(t('calendar.add'),()=>this.run(()=>this.edit()),'btn btn-primary');this.add.id='calendar-subscription-add';this.add.dataset.calendarAction='add';this.add.prepend(icon('calendar-plus'),' ');
    heading.append(title,this.add);this.list=node('ul',undefined,'calendar-subscription-list');this.list.id='calendar-subscription-list';
    this.message=node('p',undefined,'hint');this.message.id='calendar-subscriptions-message';this.message.setAttribute('role','status');
    this.content=node('div',undefined,'calendar-subscriptions-content');this.content.id='calendar-subscriptions-content';this.content.append(node('p',t('calendar.explanation'),'hint'),this.list);this.panel.append(heading,this.content,this.message);
    this.allDay=node('section',undefined,'calendar-all-day-plans');this.allDay.hidden=true;this.allDay.setAttribute('aria-labelledby','calendar-all-day-title');
    root.querySelector('.calendar-scroll').before(this.panel,this.allDay);
    this.dialog=node('dialog',undefined,'app-dialog shadow-lg');this.dialog.id='calendar-subscription-editor';this.dialog.setAttribute('aria-labelledby','calendar-subscription-editor-title');
    this.form=node('form');this.form.addEventListener('submit',event=>{event.preventDefault();void this.save();});
    this.formTitle=node('h2');this.formTitle.id='calendar-subscription-editor-title';this.fields=node('div');
    this.formError=node('p',undefined,'error');this.formError.setAttribute('role','alert');this.formHint=node('p',undefined,'hint');
    this.submit=node('button',t('ui.save'),'btn btn-primary');this.submit.type='submit';this.submit.id='calendar-subscription-save';
    this.cancel=button(t('ui.cancel'),()=>this.cancelEditor());const actions=node('div',undefined,'dialog-actions');actions.append(this.cancel,this.submit);
    this.form.append(this.formTitle,this.fields,this.formHint,this.formError,actions);this.dialog.append(this.form);root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{event.preventDefault();this.cancelEditor();});
    this.dialog.addEventListener('close',()=>{const origin=this.draft?.origin;this.draft=null;this.fields.replaceChildren();this.formError.textContent='';this.formHint.textContent='';if(origin&&!root.hidden)this.findAction(origin)?.focus();});
    this.details=node('dialog',undefined,'app-dialog shadow-lg calendar-event-details');this.details.id='calendar-event-details';this.details.setAttribute('aria-labelledby','calendar-event-title');root.append(this.details);
    this.details.addEventListener('close',()=>{if(this.details.open)return;const event=this.selected,date=this.originDate;this.selected=null;this.originDate=null;this.details.replaceChildren();if(event&&!root.hidden&&!document.querySelector('dialog[open]'))(root.querySelector(`[data-calendar-event-key="${event.key}"][data-calendar-subscription-id="${event.subscriptionId}"]${date?`[data-date="${date}"]`:''}`)||root.querySelector('#calendar-date'))?.focus();});
    window.addEventListener('beforeunload',event=>{if(this.dirty()){event.preventDefault();event.returnValue='';}});
  }
  findAction(origin){return [...this.panel.querySelectorAll('[data-calendar-action]')].find(value=>value.dataset.calendarAction===origin);}
  run(operation){const session=this.scope;void Promise.resolve().then(()=>{if(session===this.scope)return operation();}).catch(error=>{if(session===this.scope)this.failure(error);});}
  update(data,connected,scope) {
    if(this.scope!==scope){this.reset();this.scope=scope;}
    this.data=data;this.connected=connected;this.writable=connected&&this.callbacks.canWrite();
    const key=data.dates.first+'|'+data.profile.data.zoneId+'|'+data.revision,changed=key!==this.key;this.key=key;
    this.paint();if(!this.root.hidden){this.poll();if(changed)void this.load();}
  }
  setConnected(value){this.connected=value;this.writable=value&&this.callbacks.canWrite();this.paint();}
  poll(){if(!this.timer&&this.scope&&this.data)this.timer=setInterval(()=>void this.load(),30000);}
  show(){if(!this.scope||!this.data)return;this.poll();void this.load();}
  hide(){clearInterval(this.timer);this.timer=null;clearTimeout(this.burst);this.details.close();}
  reset(){
    ++this.generation;clearInterval(this.timer);this.timer=null;clearTimeout(this.burst);this.scope=null;this.data=null;this.key=null;this.rows=[];this.events=[];this.sources={};this.expanded=null;this.busy.clear();this.draft=null;this.selected=null;
    this.dialog.close();this.details.close();this.fields.replaceChildren();this.details.replaceChildren();this.list.replaceChildren();this.allDay.replaceChildren();this.allDay.hidden=true;this.message.textContent='';
  }
  async load() {
    if(!this.scope||!this.data||this.root.hidden)return;
    const session=this.scope,generation=++this.generation,from=this.data.dates.first,to=addDays(from,7),key=this.key;
    if(!this.rows.length)this.message.textContent=t('calendar.loading');
    try {
      const [subscriptions,events]=await Promise.all([session.request('/calendar-subscriptions'),session.request('/calendar-events?'+new URLSearchParams({fromDate:from,toDate:to}))]);
      if(session!==this.scope||generation!==this.generation||key!==this.key)return;
      this.rows=subscriptions.data.items;this.events=events.data.items;this.sources=events.data.activitySources;this.errors=events.data.errors;this.message.textContent=this.rows.some(value=>value.lastError||this.errors?.[value.id])?t('calendar.refresh_errors'):'';
      this.paint();this.paintAllDay();this.callbacks.onEvents({items:this.events,sources:this.sources});this.reviewDetails();
      clearTimeout(this.burst);
      if(this.rows.some(value=>!value.lastError&&(!value.lastSuccess&&Date.now()-Date.parse(value.createdAt)<90000||value.lastAttempt&&(!value.lastSuccess||Date.parse(value.lastAttempt)>Date.parse(value.lastSuccess))&&Date.now()-Date.parse(value.lastAttempt)<90000)))this.burst=setTimeout(()=>void this.load(),2000);
    }catch(error){if(session===this.scope&&generation===this.generation)this.failure(error);}
  }
  failure(error){if(error?.status===401){this.callbacks.onUnauthorized(error);return;}this.message.textContent=errorText(error);}
  date(value){return value?new Intl.DateTimeFormat(getLocale(),{dateStyle:'medium',timeStyle:'short',timeZone:this.data.profile.data.zoneId}).format(new Date(value)):t('calendar.never');}
  paint() {
    if(!this.data)return;
    this.content.hidden=!(this.expanded??!this.rows.length);this.toggle.setAttribute('aria-expanded',String(!this.content.hidden));this.toggle.replaceChildren(icon(this.content.hidden?'chevron-right':'chevron-down'),' ',this.rows.length?t('calendar.subscriptions_count',{count:this.rows.length}):t('calendar.subscriptions'));
    const active=document.activeElement,focus=this.panel.contains(active)?active.dataset.calendarAction:null;
    this.add.disabled=!this.writable||!this.data.clients.length;this.list.replaceChildren();
    if(!this.rows.length)this.list.append(node('li',this.data.clients.length?t('calendar.none'):t('calendar.client_required'),'hint'));
    for(const value of this.rows) {
      const row=node('li',undefined,'calendar-subscription-row'),label=node('label',undefined,'calendar-subscription-choice'),input=node('input',undefined,'form-check-input');input.type='checkbox';input.checked=value.visible;input.disabled=!this.writable||this.busy.has(value.id);input.dataset.calendarAction='visible:'+value.id;input.setAttribute('aria-label',t('calendar.show_name',{name:value.name}));
      input.addEventListener('change',()=>void this.visibility(value,input.checked));
      const text=node('span');text.append(node('strong',value.name),node('small',this.data.clients.find(client=>client.id===value.clientId)?.name||t('ui.client')));label.append(input,text);
      const status=node('div',undefined,'calendar-subscription-status hint');status.append(node('span',t('calendar.last_success',{date:this.date(value.lastSuccess)})));
      const error=value.lastError||this.errors?.[value.id];if(error)status.append(node('span',calendarFailure(error),'error'));
      else if(value.lastAttempt&&(!value.lastSuccess||Date.parse(value.lastAttempt)>Date.parse(value.lastSuccess))&&Date.now()-Date.parse(value.lastAttempt)<90000)status.append(node('span',t('calendar.refreshing')));
      const actions=node('div',undefined,'calendar-subscription-actions');
      for(const [action,glyph,key,operation]of [['refresh','refresh','calendar.refresh_name',()=>this.refresh(value)],['edit','edit','calendar.edit_name',()=>this.edit(value.id)],['delete','trash','calendar.remove_name',()=>this.edit(value.id,true)]]) {
        const control=button('',()=>this.run(operation),action==='delete'?'btn btn-outline-danger':'btn btn-outline-secondary');iconButton(control,glyph,t(key,{name:value.name}));control.disabled=!this.writable||this.busy.has(value.id);control.dataset.calendarAction=action+':'+value.id;actions.append(control);
      }
      row.append(label,status,actions);this.list.append(row);
    }
    if(focus&&document.activeElement===document.body)(this.findAction(focus)||this.add)?.focus();
    if(this.draft&&!this.draft.busy)this.submit.disabled=!this.writable||this.draft.uncertain&&!this.draft.key;
  }
  async visibility(value,visible) {
    const session=this.scope;if(!this.writable||this.busy.has(value.id))return;this.busy.add(value.id);this.paint();
    try {
      const current=await session.request('/calendar-subscriptions/'+value.id);if(session!==this.scope)return;
      await session.request('/calendar-subscriptions/'+value.id,{method:'PUT',etag:current.etag,body:{name:current.data.name,clientId:current.data.clientId,visible}});
      if(session===this.scope)await this.load();
    }catch(error){if(session===this.scope)this.failure(error);}finally{if(session===this.scope){this.busy.delete(value.id);this.paint();if(document.activeElement===document.body)this.findAction('visible:'+value.id)?.focus();}}
  }
  async refresh(value) {
    const session=this.scope;if(!this.writable||this.busy.has(value.id))return;this.busy.add(value.id);this.paint();
    try{await session.request('/calendar-subscriptions/'+value.id+'/refresh',{method:'POST',timeout:30000});if(session===this.scope)await this.load();}
    finally{if(session===this.scope){this.busy.delete(value.id);this.paint();if(document.activeElement===document.body)this.findAction('refresh:'+value.id)?.focus();}}
  }
  field(label,name,value,{required=false,type='text'}={}) {
    const wrap=node('label',undefined,'form-label');wrap.append(node('span',label));const input=node('input',undefined,'form-control');input.id='calendar-subscription-'+name;input.name=name;input.type=type;input.value=value||'';input.required=required;input.maxLength=name==='url'?8192:1000;input.autocomplete='off';input.spellcheck=false;wrap.append(input);this.fields.append(wrap);return input;
  }
  async edit(id=null,remove=false) {
    if(!this.writable||this.dialog.open)return;const session=this.scope;
    const loaded=id?await session.request('/calendar-subscriptions/'+id):{data:{name:'',clientId:this.data.clients[0]?.id,visible:true},etag:null};
    if(session!==this.scope||!this.writable||this.dialog.open)return;
    const value=loaded.data;this.fields.replaceChildren();this.formError.textContent='';this.formHint.textContent=remove?t('calendar.remove_explanation'):t('calendar.url_hint');
    this.draft={session,id,remove,etag:loaded.etag,original:value,key:!id?crypto.randomUUID():null,origin:id?(remove?'delete:':'edit:')+id:'add'};
    this.formTitle.textContent=t(remove?'calendar.remove_title':id?'calendar.edit':'calendar.add');
    this.submit.textContent=t(remove?'ui.delete':'ui.save');this.submit.className='btn '+(remove?'btn-danger':'btn-primary');this.submit.disabled=false;this.cancel.disabled=false;
    if(remove)this.fields.append(node('p',t('calendar.remove_confirm',{name:value.name})));
    else {
      this.field(t('ui.name'),'name',value.name,{required:true});const wrap=node('label',undefined,'form-label');wrap.append(node('span',t('ui.client')));const select=node('select',undefined,'form-select');select.id='calendar-subscription-client';select.name='clientId';select.required=true;
      for(const client of this.data.clients){const option=node('option',client.name);option.value=client.id;select.append(option);}select.value=value.clientId||'';wrap.append(select);this.fields.append(wrap);
      this.field(t(id?'calendar.url_keep':'calendar.url'),'url','',{required:!id,type:'url'});
    }
    this.draft.baseline=JSON.stringify(this.formValue());this.dialog.showModal();(this.fields.querySelector('input,select')||this.cancel).focus();
  }
  formValue(){const get=name=>this.fields.querySelector(`[name="${name}"]`)?.value||'';return {name:get('name'),clientId:get('clientId'),url:get('url'),visible:this.draft?.original.visible??true};}
  dirty(){return !!this.draft&&(this.draft.busy||!this.draft.remove&&JSON.stringify(this.formValue())!==this.draft.baseline);}
  cancelEditor(){if(this.draft?.busy)return;if(this.dirty()&&!window.confirm(t('calendar.discard')))return;this.dialog.close();void this.load();}
  async save() {
    const draft=this.draft;if(!draft||draft.busy||draft.session!==this.scope||!this.writable||!this.form.reportValidity())return;
    draft.busy=true;this.submit.disabled=true;this.cancel.disabled=true;this.formError.textContent='';
    const payload=draft.uncertain?draft.payload:this.formValue();draft.payload=payload;for(const input of this.fields.querySelectorAll('input,select'))input.disabled=true;
    try {
      await draft.session.request('/calendar-subscriptions'+(draft.id?'/'+draft.id:''),{method:draft.remove?'DELETE':draft.id?'PUT':'POST',body:draft.remove?undefined:payload,etag:draft.etag,key:draft.key});
      if(draft!==this.draft||draft.session!==this.scope)return;draft.busy=false;this.dialog.close();await this.load();
    }catch(error){
      if(draft!==this.draft||draft.session!==this.scope)return;if(error.status===401){this.failure(error);return;}
      this.formError.textContent=errorText(error);draft.uncertain=error.uncertain===true;
      if(draft.uncertain){this.formHint.textContent=t(draft.key?'calendar.retry_exact':'calendar.review_unknown');this.submit.textContent=t('ui.retry_same_command');}
      else for(const input of this.fields.querySelectorAll('input,select'))input.disabled=false;
    }finally{if(draft===this.draft){draft.busy=false;this.submit.disabled=!this.writable||draft.uncertain&&!draft.key;this.cancel.disabled=false;}}
  }
  eventDate(event) {
    if(!event.allDay)return instantText(event.start,this.data.profile.data.zoneId)+' → '+instantText(event.end,this.data.profile.data.zoneId);
    const format=date=>new Intl.DateTimeFormat(getLocale(),{dateStyle:'medium',timeZone:'UTC'}).format(new Date(date+'T12:00:00Z'));
    const last=addDays(event.endDate,-1);return t('calendar.all_day')+' · '+format(event.firstDate)+(last!==event.firstDate?' – '+format(last):'');
  }
  paintAllDay() {
    const active=document.activeElement,key=this.allDay.contains(active)?active.dataset.calendarEventKey:null,values=this.events.filter(value=>value.allDay);this.allDay.replaceChildren();this.allDay.hidden=!values.length;
    if(!values.length)return;const title=node('h3',t('calendar.all_day_plans'));title.id='calendar-all-day-title';this.allDay.append(title);
    for(const value of values){const pick=button('',()=>this.openEvent(value),'btn btn-outline-secondary calendar-all-day-event');pick.dataset.calendarEventKey=value.key;pick.dataset.calendarSubscriptionId=value.subscriptionId;pick.style.borderLeftColor=value.color;pick.append(icon('calendar-day'),node('strong',value.title||t('calendar.untitled')),node('span',this.eventDate(value)),node('small',value.clientName+' · '+value.calendarName));this.allDay.append(pick);}
    if(key&&document.activeElement===document.body)this.allDay.querySelector(`[data-calendar-event-key="${key}"]`)?.focus();
  }
  openEvent(event,date=null) {
    if(!this.details.open)this.originDate=date;
    this.selected=event;this.detailZone=this.data.profile.data.zoneId;this.details.replaceChildren();const title=node('h2',event.title||t('calendar.untitled'));title.id='calendar-event-title';
    this.detailNotice=node('p',undefined,'hint');this.detailNotice.setAttribute('role','status');
    this.convert=button(t('calendar.create_activity'),()=>{const selected=this.selected;if(!selected||!this.writable||selected.activityIds?.length)return;const range={fromDate:this.data.dates.first,toDate:addDays(this.data.dates.first,7)};this.details.close();this.callbacks.onConvert(selected,range);},'btn btn-primary');
    this.convert.disabled=!this.writable||!!event.activityIds?.length;
    if(event.activityIds?.length)this.detailNotice.textContent=t('calendar.already_recorded');else if(!this.writable)this.detailNotice.textContent=t('calendar.read_only');
    const actions=node('div',undefined,'dialog-actions');actions.append(button(t('ui.close'),()=>this.details.close()),this.convert);
    this.details.append(title,node('p',this.eventDate(event)),node('p',event.clientName+' · '+event.calendarName,'hint'));
    if(event.location)this.details.append(node('p',t('calendar.location',{location:event.location})));
    if(event.description)this.details.append(node('p',event.description,'calendar-description'));
    this.details.append(this.detailNotice,actions);if(!this.details.open)this.details.showModal();actions.querySelector('button').focus();
  }
  reviewDetails() {
    if(!this.details.open||!this.selected)return;const current=this.events.find(event=>event.subscriptionId===this.selected.subscriptionId&&event.key===this.selected.key);
    if(!current||current.eventVersion!==this.selected.eventVersion||this.detailZone!==this.data.profile.data.zoneId){
      this.convert.disabled=true;this.detailNotice.replaceChildren(node('span',t('calendar.event_changed')));
      if(current)this.detailNotice.append(' ',button(t('calendar.review_updated'),()=>this.openEvent(current),'btn btn-link'));
    }else if(current.activityIds?.length){this.convert.disabled=true;this.detailNotice.textContent=t('calendar.already_recorded');}
    else this.convert.disabled=!this.writable;
  }
}

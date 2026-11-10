import {JournalNoteEditor} from './journal-note-editor.js';
import {calendarDescription,calendarLocation} from './tooltip-description.js';
import {calendarItemWhen} from './calendar-item.js';
import {t} from './i18n.js';

export const calendarSearchNavigation='timekeeper:calendar-search-navigation';
const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};

/** Live calendar details alongside the shared editor for a real saved note, when present. */
export class CalendarSearchEditor extends JournalNoteEditor {
  constructor(root,callbacks,{inline=false}={}){
    const prefix=inline?'calendar-search-workspace':'calendar-search-popup';
    super(root,callbacks,prefix,prefix,{vault:true,inline});
    this.details=node('div',undefined,'calendar-search-details');this.error.before(this.details);
  }
  async openHit(hit,session,writable,zone,origin={}){
    if(this.opened)return;
    const event=hit.calendarEvent;this.preparing=false;this.details.replaceChildren();
    this.details.append(node('p',event.clientName+' · '+event.calendarName,'hint'));
    if(event.seriesDefinition)this.details.append(node('p',t('notes.search_event_series'),'hint'));
    else if(event.start&&event.end)this.details.append(node('p',calendarItemWhen(event,zone)));
    if(event.location){const location=node('p');location.append(calendarLocation(t('calendar.location',{location:event.location})));this.details.append(location);}
    if(event.description){const description=calendarDescription(event.description);description.classList.add('calendar-description');this.details.append(description);}
    if(!hit.entry)this.details.append(node('p',t('notes.search_event_no_note'),'hint'));
    this.markdown.root.hidden=!hit.entry;this.status.parentElement.hidden=!hit.entry;this.dialog.classList.toggle('calendar-search-no-note',!hit.entry);
    await super.open({...origin,hit,entry:hit.entry,detailsOnly:!hit.entry,path:hit.entry?'/notes/entries/'+hit.entry.id+'/content':null,title:event.title||t('calendar.untitled')},session,!!hit.entry&&writable);
  }
  readNote(session=this.session,path=this.path){return this.context.entry?super.readNote(session,path):Promise.resolve({data:{body:''},etag:null});}
  controls(){
    super.controls();if(!this.context?.hit)return;
    this.status.parentElement.hidden=this.context.detailsOnly&&!this.preparing;
    if(this.preparing)this.status.textContent=t('notes.search_preparing');
  }
  async maximize(){
    if(this.inline||this.maximizeButton.disabled)return;
    const session=this.session,generation=this.generation,current=()=>session===this.session&&generation===this.generation;
    this.maximizing=true;this.preparing=false;this.error.hidden=true;this.controls();
    const finished=()=>{if(current()){this.maximizing=this.preparing=false;this.controls();}};
    if(this.dialog.dispatchEvent(new CustomEvent(calendarSearchNavigation,{bubbles:true,cancelable:true,detail:{id:this.context.hit.calendarEvent.id,maximize:true,guards:[current],leave:[],pending:()=>{if(current()){this.preparing=true;this.controls();}},finished}})))finished();
  }
  reset(){super.reset();this.preparing=false;this.details.replaceChildren();this.resources.invalidate();this.context=this.path=this.origin=null;this.title.textContent='';this.error.textContent='';this.error.hidden=true;this.status.textContent='';}
}

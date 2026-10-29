import {addDays,dateInZone} from './model.js';
import {t} from './i18n.js';
import {icon,iconButton} from './icons.js';
import {JournalNoteEditor} from './journal-note-editor.js';

export function eventNoteIndicator(event){
  if(!event.hasNote)return null;
  const marker=icon('journal-text');marker.classList.add('calendar-event-note-indicator');marker.title=t('event_notes.open');return marker;
}
/** The same note affordance in Week and Today event details. */
export function eventNoteButton(event,action){
  const button=document.createElement('button');button.type='button';button.dataset.eventNote=event.key;
  iconButton(button,event.hasNote?'journal-text':'journal-plus',t(event.hasNote?'event_notes.open':'event_notes.add'));
  button.addEventListener('click',action);return button;
}
export class EventNoteEditor extends JournalNoteEditor {
  constructor(root,callbacks,prefix){super(root,callbacks,prefix+'-event-note');}
  open(event,session,writable,zone){
    const fromDate=event.allDay?event.firstDate:dateInZone(event.start,zone);
    return super.open({path:'/event-notes/'+event.subscriptionId+'/'+event.key,title:t('event_notes.title',{title:event.title||t('calendar.untitled')}),deleteConfirm:t('event_notes.delete_confirm'),input:{fromDate,toDate:addDays(fromDate,1)}},session,writable);
  }
}

import {addDays,monday} from './model.js';
import {t} from './i18n.js';
import {buildCompactActions} from './markdown-actions.js';
import {JournalNoteEditor} from './journal-note-editor.js';

/** ISO week-based year and week number, including weeks crossing calendar years. */
export function isoWeek(date){
  const start=monday(date),thursday=new Date(start+'T12:00:00Z');thursday.setUTCDate(thursday.getUTCDate()+3);
  const year=thursday.getUTCFullYear(),first=monday(String(year).padStart(4,'0')+'-01-04');
  const number=Math.round((Date.parse(start+'T12:00:00Z')-Date.parse(first+'T12:00:00Z'))/604800000)+1;
  return String(year).padStart(4,'0')+'-W'+String(number).padStart(2,'0');
}

export function isoWeekStart(week){
  const [year,number]=week.split('-W');
  return addDays(monday(year+'-01-04'),(Number(number)-1)*7);
}

export async function savedWeeks(session,first,last){
  const range=new URLSearchParams({fromWeek:isoWeek(first),toWeek:isoWeek(last)});
  const {data}=await session.request('/week-notes?'+range);
  return new Set(data.items.filter(note=>note.hasBody).map(note=>note.week));
}

export class WeekNoteEditor extends JournalNoteEditor {
  constructor(root,callbacks,{phone,actions,inline=true}={}){
    const prefix=inline?'today':'track';
    super(root,callbacks,prefix+'-week-note',inline?'weekly-journal':'track-weekly-journal',{inline,journalCard:inline,mainActions:actions});
    if(!inline)return;
    this.title.classList.add('visually-hidden');
    buildCompactActions(this.markdown,phone,actions);
    this.dialog.addEventListener('keydown',event=>{
      if(event.key!=='Escape'||event.defaultPrevented||event.target.closest('dialog'))return;
      event.preventDefault();void this.close();
    });
  }
  open(week,session,writable){return super.open({week,originSelector:this.inline?`[data-week="${week}"]`:`[data-track-week-note="${week}"]`,path:'/week-notes/'+week,title:t('today.week_note',{week}),deleteConfirm:t('week_notes.delete_confirm',{week})},session,writable);}
}

import {monday} from './model.js';
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

export class WeekNoteEditor extends JournalNoteEditor {
  constructor(root,callbacks,phone,actions){
    super(root,callbacks,'today-week-note','weekly-journal',{inline:true,journalCard:true,mainActions:actions});
    this.title.classList.add('visually-hidden');
    buildCompactActions(this.markdown,phone,actions);
    this.dialog.addEventListener('keydown',event=>{
      if(event.key!=='Escape'||event.defaultPrevented||event.target.closest('dialog'))return;
      event.preventDefault();void this.close();
    });
  }
  open(week,session,writable){return super.open({week,originSelector:`[data-week="${week}"]`,path:'/week-notes/'+week,title:t('today.week_note',{week}),deleteConfirm:t('week_notes.delete_confirm',{week})},session,writable);}
}

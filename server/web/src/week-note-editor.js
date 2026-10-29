import {monday} from './model.js';
import {t} from './i18n.js';
import {JournalNoteEditor} from './journal-note-editor.js';

/** ISO week-based year and week number, including weeks crossing calendar years. */
export function isoWeek(date){
  const start=monday(date),thursday=new Date(start+'T12:00:00Z');thursday.setUTCDate(thursday.getUTCDate()+3);
  const year=thursday.getUTCFullYear(),first=monday(String(year).padStart(4,'0')+'-01-04');
  const number=Math.round((Date.parse(start+'T12:00:00Z')-Date.parse(first+'T12:00:00Z'))/604800000)+1;
  return String(year).padStart(4,'0')+'-W'+String(number).padStart(2,'0');
}

export class WeekNoteEditor extends JournalNoteEditor {
  constructor(root,callbacks){super(root,callbacks,'today-week-note','weekly-journal');}
  open(week,session,writable){return super.open({originSelector:`[data-week="${week}"]`,path:'/week-notes/'+week,title:t('today.week_note',{week}),deleteConfirm:t('week_notes.delete_confirm',{week})},session,writable);}
}

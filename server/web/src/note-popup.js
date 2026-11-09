import {JournalNoteEditor} from './journal-note-editor.js';
import {noteTitle} from './note-title.js';

/** The ordinary vault content protocol, hosted by the shared guarded editor. */
export class NotePopup extends JournalNoteEditor {
  constructor(root,callbacks){super(root,callbacks,'note-popup','note-popup',{vault:true});}
  async openEntry(entry,session,writable,{heading='',originSelector,originElement}={}){
    await super.open({entry,originSelector,originElement,path:'/notes/entries/'+entry.id+'/content',title:noteTitle(entry,entry.path)},session,writable);
    if(this.dialog.open&&heading){await this.markdown.showFormatted(false);this.markdown.scrollToHeading(heading);}
  }
}

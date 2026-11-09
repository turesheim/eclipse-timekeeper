import {JournalNoteEditor} from './journal-note-editor.js';
import {noteTitle} from './note-title.js';

/** The ordinary vault content protocol, hosted by the shared guarded editor. */
export class NotePopup extends JournalNoteEditor {
  constructor(root,callbacks){super(root,callbacks,'note-popup','note-popup',{vault:true});}
  async openEntry(entry,session,writable,{heading='',originSelector,originElement}={}){
    const context={entry,originSelector,originElement,path:'/notes/entries/'+entry.id+'/content',title:noteTitle(entry,entry.path)};
    await super.open(context,session,writable);
    const current=()=>this.context===context&&this.session===session&&this.dialog.open;
    if(heading&&current()){await this.markdown.showFormatted(false);if(current())this.markdown.scrollToHeading(heading);}
  }
  reset(){
    super.reset();this.resources.invalidate();this.context=this.path=this.origin=null;this.title.textContent='';this.error.textContent='';this.error.hidden=true;this.status.textContent='';
  }
}

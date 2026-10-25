import {dateTimeFormat} from './formatters.js';
import {getLocale,t} from './i18n.js';
import {icon} from './icons.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(text,className)=>{const value=node('button',text,className);value.type='button';return value;};

/** Optional web metadata has its own read state and never changes timed work. */
export class TrackDayNotes {
  constructor(root,grid,monthInput,callbacks){
    Object.assign(this,{root,grid,monthInput,callbacks,notes:new Map(),bodyDates:new Set(),generation:0});
    this.status=node('p',undefined,'hint track-note-status');this.status.setAttribute('role','status');this.message=node('span');
    this.retry=button(t('ui.retry'),'btn btn-link btn-sm');this.retry.hidden=true;this.retry.addEventListener('click',()=>{this.key=null;this.load();});this.status.append(this.message,' ',this.retry);grid.after(this.status);this.status.hidden=true;
    this.dialog=node('dialog',undefined,'app-dialog shadow-lg');this.dialog.id='track-note-editor';this.dialog.setAttribute('aria-labelledby','track-note-title');
    this.form=node('form');this.title=node('h2');this.title.id='track-note-title';
    const label=node('label',t('day_notes.text'),'form-label');label.htmlFor='track-note-text';this.text=node('input',undefined,'form-control');this.text.id='track-note-text';this.text.maxLength=80;this.text.autocomplete='off';this.text.setAttribute('aria-describedby','track-note-hint');
    const hint=node('p',t('day_notes.hint'),'hint');hint.id='track-note-hint';this.error=node('p','','alert alert-danger');this.error.setAttribute('role','alert');
    const actions=node('div',undefined,'dialog-actions');this.cancel=button(t('ui.cancel'),'btn btn-outline-secondary');this.save=button(t('ui.save'),'btn btn-primary');this.save.type='submit';actions.append(this.cancel,this.save);
    this.form.append(this.title,label,this.text,hint,this.error,actions);this.dialog.append(this.form);root.append(this.dialog);
    this.cancel.addEventListener('click',()=>{if(!this.editor?.busy)this.closeEditor();});
    // Focus returns before the native close event is delivered. Release editor
    // state synchronously so the next keyboard action can open the day menu.
    this.dialog.addEventListener('cancel',event=>{event.preventDefault();if(!this.editor?.busy)this.closeEditor();});
    this.dialog.addEventListener('close',()=>this.finishEditor());
    this.form.addEventListener('submit',event=>{event.preventDefault();void this.commit();});
    this.buildMenu();
  }
  closeEditor(){this.dialog.close();this.finishEditor();}
  finishEditor(){
    if(this.dialog.open)return;
    const date=this.editor?.date,uncertain=this.editor?.uncertain;this.editor=null;this.text.value='';this.error.textContent='';
    if(date)this.focusDate(date);
    if(uncertain&&this.session){this.key=null;this.load();}
  }
  buildMenu(){
    this.menu=node('div',undefined,'track-note-menu dropdown-menu shadow');this.menu.id='track-note-menu';this.menu.popover='manual';this.menu.setAttribute('role','menu');this.menu.setAttribute('aria-label',t('day_notes.actions'));
    this.menuItem=button('', 'dropdown-item');this.menuItem.setAttribute('role','menuitem');this.menu.append(this.menuItem);this.root.append(this.menu);
    // Automatic popovers dismiss on the release of the touch that opened them.
    // Keep this menu open through release and dismiss on the next outside press.
    document.addEventListener('pointerdown',event=>{if(!this.menu.contains(event.target))this.closeMenu();},true);
    document.addEventListener('keydown',event=>{if(event.key==='Escape'&&this.menu.matches(':popover-open')){event.preventDefault();this.closeMenu();this.focusDate(this.menuDate);}});
    this.menu.addEventListener('contextmenu',event=>event.preventDefault());
    this.menu.addEventListener('pointerdown',()=>{this.suppressClickUntil=0;},{passive:true});
    this.menuItem.addEventListener('click',()=>{const date=this.menuDate;this.closeMenu();void this.open(date);});
    this.menu.addEventListener('keydown',event=>{
      if(event.key==='Tab'){this.closeMenu();this.focusDate(this.menuDate);}
      if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();this.menuItem.focus();}
      if(event.key==='Escape'){event.preventDefault();this.closeMenu();this.focusDate(this.menuDate);}
    });
    this.grid.addEventListener('contextmenu',event=>{
      const cell=event.target.closest('[data-track-date]');if(!cell||!this.writable||!this.loaded)return;
      event.preventDefault();const touch=!!this.hold||event.pointerType==='touch'||event.pointerType==='pen';if(touch)this.suppressClickUntil=Infinity;this.cancelHold();this.showMenu(cell.dataset.trackDate,event.clientX,event.clientY,!touch);
    });
    this.grid.addEventListener('keydown',event=>{
      if(event.key==='ContextMenu'||event.key==='F10'&&event.shiftKey){
        const cell=event.target.closest('[data-track-date]');if(!cell||!this.writable||!this.loaded)return;
        event.preventDefault();const rect=event.target.getBoundingClientRect();this.showMenu(cell.dataset.trackDate,rect.left,rect.bottom);
      }
    });
    this.grid.addEventListener('pointerdown',event=>{
      this.cancelHold();this.suppressClickUntil=0;
      if(!event.isPrimary||!['touch','pen'].includes(event.pointerType)||!this.writable||!this.loaded)return;
      const cell=event.target.closest('[data-track-date]');if(!cell||!getSelection().isCollapsed)return;
      const hold={id:event.pointerId,date:cell.dataset.trackDate,x:event.clientX,y:event.clientY};this.hold=hold;
      this.holdTimer=setTimeout(()=>{if(this.hold!==hold)return;this.hold=null;this.suppressClickUntil=Infinity;this.showMenu(hold.date,hold.x,hold.y,false);},450);
    },{passive:true});
    this.grid.addEventListener('pointermove',event=>{if(this.hold&&(Math.hypot(event.clientX-this.hold.x,event.clientY-this.hold.y)>10||event.pointerId!==this.hold.id))this.cancelHold();},{passive:true});
    this.grid.addEventListener('touchstart',event=>{if(event.touches.length!==1)this.cancelHold();},{passive:true});
    for(const name of ['pointerup','pointercancel'])document.addEventListener(name,()=>{this.cancelHold();if(this.suppressClickUntil===Infinity){this.suppressClickUntil=performance.now()+700;if(this.menu.matches(':popover-open'))this.menuItem.focus({preventScroll:true});}},{passive:true});
    document.addEventListener('scroll',()=>this.cancelHold(),true);
    document.addEventListener('wheel',()=>this.closeMenu(),{passive:true});
    window.addEventListener('resize',()=>{this.cancelHold();this.closeMenu();});
    // Release can synthesize a click on the date or the newly opened menu.
    // A fresh menu press clears this guard so the next deliberate tap works.
    const suppressHeldClick=event=>{if(event.detail&&performance.now()<this.suppressClickUntil){event.preventDefault();event.stopImmediatePropagation();this.suppressClickUntil=0;}};
    this.grid.addEventListener('click',suppressHeldClick,true);this.menu.addEventListener('click',suppressHeldClick,true);
  }
  cancelHold(){clearTimeout(this.holdTimer);this.hold=null;}
  showMenu(date,x,y,focus=true){
    if(!this.writable||!this.loaded||this.editor||!this.cells?.has(date))return;
    this.menuDate=date;this.menuItem.textContent=t(this.notes.has(date)?'day_notes.menu_edit':'day_notes.menu_add');
    if(!this.menu.matches(':popover-open'))this.menu.showPopover();
    this.menu.style.left=Math.max(8,Math.min(x,innerWidth-this.menu.offsetWidth-8))+'px';
    this.menu.style.top=Math.max(8,Math.min(y,innerHeight-this.menu.offsetHeight-8))+'px';if(focus)this.menuItem.focus({preventScroll:true});
  }
  closeMenu(){if(this.menu.matches(':popover-open'))this.menu.hidePopover();}
  focusDate(date){if(!this.root.hidden)(this.grid.querySelector(`button[data-track-note-date="${date}"]`)||this.monthInput).focus();}
  dateLabel(date){return dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(date+'T12:00:00Z'));}
  update(calendar,cells,session,revision,writable,focused){
    if(session!==this.session){this.reset();this.session=session;}
    Object.assign(this,{calendar,cells,revision,writable});
    this.paint(focused);this.load();
  }
  setWritable(value){this.writable=value;if(!value){this.cancelHold();this.closeMenu();}this.controls();this.paint();}
  controls(){
    const editor=this.editor;if(!editor)return;
    this.text.disabled=editor.loading||editor.busy||editor.uncertain||!this.writable;
    this.save.disabled=editor.loading||editor.busy||editor.uncertain||editor.stale||!editor.etag||!this.writable;
    this.cancel.disabled=editor.busy;
  }
  reset(){
    this.cancelHold();this.closeMenu();++this.generation;this.session=null;this.key=null;this.range=null;this.loaded=false;this.notes.clear();this.bodyDates.clear();this.loadingFocus=null;this.cells=null;this.calendar=null;this.editor=null;
    this.closeEditor();this.text.value='';this.error.textContent='';this.status.hidden=true;this.message.textContent='';this.retry.hidden=true;
  }
  hide(){this.cancelHold();this.closeMenu();if(!this.editor?.busy)this.closeEditor();}
  load(){
    if(!this.session||!this.calendar)return;
    const first=this.calendar.days[0].date,last=this.calendar.days.at(-1).date,key=first+'|'+last+'|'+this.revision;
    if(key===this.key)return;
    const range=first+'|'+last;if(range!==this.range)this.loaded=false;this.range=range;this.key=key;this.loadingFocus=this.grid.contains(document.activeElement)?document.activeElement?.dataset.trackNoteDate:this.loadingFocus;this.paint();this.status.hidden=false;this.message.textContent=t('day_notes.loading');this.retry.hidden=true;
    const generation=++this.generation,session=this.session;
    void session.request('/day-notes?'+new URLSearchParams({fromDate:first,toDate:last})).then(reply=>{
      if(generation!==this.generation||session!==this.session)return;
      this.notes=new Map(reply.data.items.map(note=>[note.date,note.text]));this.bodyDates=new Set(reply.data.items.filter(note=>note.hasBody).map(note=>note.date));this.loaded=true;this.paint(document.activeElement===document.body?this.loadingFocus:null);this.loadingFocus=null;this.message.textContent='';this.status.hidden=true;
    }).catch(error=>{
      if(generation!==this.generation||session!==this.session)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      this.message.textContent=t('day_notes.unavailable');this.retry.hidden=false;
    });
  }
  paint(focused){
    if(!this.cells)return;
    focused=focused|| (this.grid.contains(document.activeElement)?document.activeElement?.dataset.trackNoteDate:null);
    for(const [date,cell] of this.cells){
      const heading=cell.querySelector('.track-date-row'),previous=heading.querySelector('.track-day-note'),text=this.notes.get(date),hasBody=this.bodyDates.has(date);
      if(!this.writable&&!text&&!hasBody){previous?.remove();continue;}
      const value=node(this.writable?'button':'span',undefined,'track-day-note'+(text||hasBody?'':' is-empty'));value.dataset.trackNoteDate=date;
      if(this.writable){value.type='button';value.classList.add('btn','btn-link','btn-sm');value.disabled=!this.loaded;value.setAttribute('aria-haspopup','dialog');value.addEventListener('click',()=>void this.open(date));}
      value.title=text||(hasBody?t('day_notes.body_present'):t('day_notes.add',{date:this.dateLabel(date)}));value.setAttribute('aria-label',t(text||hasBody?(this.writable?'day_notes.edit':'day_notes.view'):'day_notes.add',{date:this.dateLabel(date),text:text||(hasBody?t('day_notes.body_present'):'')}));
      if(text)value.append(node('span',text,'badge rounded-pill bg-info-subtle text-info-emphasis'));else value.append(icon('sticky'));
      if(previous)previous.replaceWith(value);else heading.append(value);
    }
    if(focused&&!this.root.hidden)this.grid.querySelector(`button[data-track-note-date="${focused}"]`)?.focus({preventScroll:true});
  }
  async open(date){
    if(!this.writable||!this.session||this.editor||!this.cells?.has(date))return;
    const editor={date,session:this.session,loading:true,busy:false,uncertain:false};this.editor=editor;this.error.textContent='';this.text.value=this.notes.get(date)||'';
    this.title.textContent=t('day_notes.title',{date:this.dateLabel(date)});this.controls();this.dialog.showModal();
    try{
      const reply=await editor.session.request('/day-notes/'+date);
      if(this.editor!==editor||editor.session!==this.session)return;
      this.text.value=reply.data.text;editor.etag=reply.etag;editor.loading=false;this.controls();this.text.focus();
    }catch(error){
      if(this.editor!==editor||editor.session!==this.session)return;
      editor.loading=false;this.error.textContent=error.message;this.controls();if(error.status===401)this.callbacks.onUnauthorized(error);
    }
  }
  async commit(){
    const editor=this.editor;if(!editor||editor.busy||editor.loading||editor.uncertain||editor.stale||!editor.etag||!this.writable||editor.session!==this.session)return;
    editor.busy=true;this.error.textContent='';this.controls();
    try{
      const reply=await editor.session.request('/day-notes/'+editor.date,{method:'PUT',etag:editor.etag,body:{text:this.text.value.trim()}});
      if(this.editor!==editor||editor.session!==this.session)return;
      ++this.generation;this.key=null;
      if(reply.data.hasBody)this.bodyDates.add(editor.date);else this.bodyDates.delete(editor.date);
      if(reply.data.text||reply.data.hasBody)this.notes.set(editor.date,reply.data.text);else this.notes.delete(editor.date);
      this.paint();this.closeEditor();this.callbacks.onChanged();
    }catch(error){
      if(this.editor!==editor||editor.session!==this.session)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      editor.uncertain=error.uncertain;editor.stale=error.status===412;this.error.textContent=error.uncertain?t('day_notes.uncertain'):error.message;
    }finally{if(this.editor===editor){editor.busy=false;this.controls();}}
  }
}

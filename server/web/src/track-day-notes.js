import {dateTimeFormat} from './formatters.js';
import {getLocale,t} from './i18n.js';
import {icon} from './icons.js';
import {MarkdownField} from './markdown-field.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(text,className)=>{const value=node('button',text,className);value.type='button';return value;};

/** Optional web metadata has its own read state and never changes timed work. */
export class TrackDayNotes {
  constructor(root,grid,monthInput,callbacks,options={}){
    Object.assign(this,{prefix:'track',headingSelector:'.track-date-row',alwaysShow:false,compact:false,inline:false},options,{root,grid,monthInput,callbacks,notes:new Map(),bodyDates:new Set(),generation:0});
    this.noteAttr='data-'+this.prefix+'-note-date';this.cellAttr='data-'+this.prefix+'-date';
    this.status=node('p',undefined,'hint '+this.prefix+'-note-status');this.status.setAttribute('role','status');this.message=node('span');
    this.retry=button(t('ui.retry'),'btn btn-link btn-sm');this.retry.hidden=true;this.retry.addEventListener('click',()=>{this.key=null;this.load();});this.status.append(this.message,' ',this.retry);if(this.statusAnchor)this.statusAnchor.before(this.status);else grid.after(this.status);this.status.hidden=true;
    this.dialog=node(this.inline?'section':'dialog',undefined,this.inline?'card journal-inline':'app-dialog journal-dialog shadow-lg');this.dialog.id=this.prefix+'-note-editor';this.dialog.setAttribute('aria-labelledby',this.prefix+'-note-title');
    this.form=node('form');this.form.id=this.prefix+'-note-form';this.title=node('h2');this.title.id=this.prefix+'-note-title';if(this.inline)this.title.classList.add('visually-hidden');
    const label=node('label',t('day_notes.text'),'form-label');label.id=this.prefix+'-note-text-label';label.htmlFor=this.prefix+'-note-text';this.text=node('input',undefined,'form-control journal-note-label');this.text.id=this.prefix+'-note-text';this.text.maxLength=80;this.text.autocomplete='off';
    this.error=node('p','','alert alert-danger');this.error.setAttribute('role','alert');
    this.savedStatus=node('p','','hint');this.savedStatus.setAttribute('role','status');
    const markdown=node('div');this.markdown=new MarkdownField(markdown,()=>this.changed(),()=>this.controls(),
      this.prefix==='track'?'journal':this.prefix+'-journal',(file,image)=>this.uploadAsset(file,image));
    this.reload=button(t('journal.reload'),'btn btn-outline-secondary');this.reload.addEventListener('click',()=>void this.reloadEditor());
    const actions=node('div',undefined,'dialog-actions');this.cancel=button(t('ui.cancel'),'btn btn-outline-secondary');this.delete=button(t('journal.delete_note'),'btn btn-outline-secondary');this.delete.addEventListener('click',()=>void this.deleteNote());this.save=button(t('ui.save'),'btn btn-primary');this.save.type='submit';actions.append(this.savedStatus,this.cancel,this.delete,this.save);
    this.recovery=node('div',undefined,'journal-recovery');this.recovery.append(this.reload);
    this.content=node('div',undefined,'journal-content');this.content.append(this.title);
    if(this.labelRoot){this.text.setAttribute('form',this.form.id);this.text.disabled=true;label.classList.add('visually-hidden');const marker=icon('tag');marker.title=t('day_notes.text');this.labelRoot.append(label,this.text,marker);}else this.content.append(label,this.text);
    this.content.append(this.error,markdown,this.recovery);this.form.append(this.content,actions);this.dialog.append(this.form);root.append(this.dialog);
    this.text.addEventListener('input',()=>this.changed());
    this.cancel.addEventListener('click',()=>this.requestClose());
    // Focus returns before the native close event is delivered. Release editor
    // state synchronously so the next keyboard action can open the day menu.
    if(!this.inline){this.dialog.addEventListener('cancel',event=>{event.preventDefault();this.requestClose();});this.dialog.addEventListener('close',()=>this.finishEditor());}
    this.form.addEventListener('submit',event=>{event.preventDefault();void this.commit();});
    window.addEventListener('beforeunload',event=>{if(this.editor&&(this.dirty()||this.editor.busy||this.editor.uncertain)){event.preventDefault();event.returnValue='';}});
    if(!this.inline)this.buildMenu();
  }
  dirty(){return !!this.editor?.etag&&(this.text.value.trim()!==this.editor.originalText||this.markdown.value()!==this.editor.originalBody);}
  async uploadAsset(file,image){
    const editor=this.editor;
    if(!editor||!this.writable||file.size>5_000_000||image&&!['image/png','image/jpeg','image/gif','image/webp'].includes(file.type))throw new Error('Invalid attachment');
    const imageExtensions={'image/png':'png','image/jpeg':'jpg','image/gif':'gif','image/webp':'webp'};
    const extension=image?imageExtensions[file.type]:(file.name.match(/\.([a-zA-Z0-9]{1,10})$/)?.[1]||'bin').toLowerCase();
    const filename=crypto.randomUUID()+'.'+extension;
    const bytes=new Uint8Array(await file.arrayBuffer());
    if(this.editor!==editor)throw new Error('Editor closed');
    let binary='';for(let offset=0;offset<bytes.length;offset+=8192)binary+=String.fromCharCode(...bytes.subarray(offset,offset+8192));
    await editor.session.request('/journal-assets/'+filename,{method:'PUT',noneMatch:'*',body:{data:btoa(binary)},emptyResponse:true,timeout:60000});
    if(this.editor!==editor)throw new Error('Editor closed');
    return 'assets/'+filename;
  }
  changed(){if(this.editor)this.savedStatus.textContent=t(this.dirty()?'journal.unsaved':'journal.saved');}
  requestClose(){if(this.editor?.busy||this.dirty()&&!confirm(t('journal.discard')))return;if(this.inline){if(this.editor)void this.loadEditor(this.editor);}else this.closeEditor();}
  closeEditor(){if(!this.inline)this.dialog.close();this.finishEditor();}
  finishEditor(){
    if(!this.inline&&this.dialog.open)return;
    const date=this.editor?.date,uncertain=this.editor?.uncertain;this.editor=null;this.text.value='';if(this.labelRoot)this.text.disabled=true;this.error.textContent='';this.savedStatus.textContent='';this.markdown.clear();
    if(date)this.focusDate(date);
    if(uncertain&&this.session){this.key=null;this.load();}
  }
  buildMenu(){
    this.menu=node('div',undefined,'track-note-menu dropdown-menu shadow');this.menu.id=this.prefix+'-note-menu';this.menu.popover='manual';this.menu.setAttribute('role','menu');this.menu.setAttribute('aria-label',t('day_notes.actions'));
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
      const cell=event.target.closest('['+this.cellAttr+']');if(!cell||!this.writable||!this.loaded)return;
      event.preventDefault();const touch=!!this.hold||event.pointerType==='touch'||event.pointerType==='pen';if(touch)this.suppressClickUntil=Infinity;this.cancelHold();this.showMenu(cell.getAttribute(this.cellAttr),event.clientX,event.clientY,!touch);
    });
    this.grid.addEventListener('keydown',event=>{
      if(event.key==='ContextMenu'||event.key==='F10'&&event.shiftKey){
        const cell=event.target.closest('['+this.cellAttr+']');if(!cell||!this.writable||!this.loaded)return;
        event.preventDefault();const rect=event.target.getBoundingClientRect();this.showMenu(cell.getAttribute(this.cellAttr),rect.left,rect.bottom);
      }
    });
    this.grid.addEventListener('pointerdown',event=>{
      this.cancelHold();this.suppressClickUntil=0;
      if(!event.isPrimary||!['touch','pen'].includes(event.pointerType)||!this.writable||!this.loaded)return;
      const cell=event.target.closest('['+this.cellAttr+']');if(!cell||!getSelection().isCollapsed)return;
      const hold={id:event.pointerId,date:cell.getAttribute(this.cellAttr),x:event.clientX,y:event.clientY};this.hold=hold;
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
  closeMenu(){if(this.menu?.matches(':popover-open'))this.menu.hidePopover();}
  focusDate(date){if(!this.root.hidden)(this.grid.querySelector(`button[${this.noteAttr}="${date}"]`)||this.monthInput).focus();}
  dateLabel(date){return dateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(date+'T12:00:00Z'));}
  canLeaveInline(){return !this.editor?.busy&&(!this.dirty()||confirm(t('journal.discard')));}
  showInline(date,session,writable){
    if(!this.inline)return;
    if(session!==this.session){this.reset();this.session=session;}
    this.writable=writable;
    if(this.editor?.date===date){this.controls();return;}
    if(this.editor)this.closeEditor();
    void this.open(date);
  }
  update(calendar,cells,session,revision,writable,focused){
    if(session!==this.session){this.reset();this.session=session;}
    Object.assign(this,{calendar,cells,revision,writable});
    this.paint(focused);this.load();
  }
  setWritable(value){this.writable=value;if(!value){this.cancelHold();this.closeMenu();}this.controls();this.paint();}
  controls(){
    const editor=this.editor;if(!editor)return;
    this.text.disabled=editor.loading||editor.busy||editor.uncertain||!editor.etag;this.text.readOnly=!this.writable;
    const readOnly=editor.loading||editor.busy||editor.uncertain||!editor.etag||!this.writable;
    if(this.markdown&&this.markdown.readOnly!==readOnly)this.markdown.setReadOnly(readOnly);
    this.save.disabled=editor.loading||editor.failedRead||editor.busy||this.markdown?.loading||editor.uncertain||editor.stale||!editor.etag||!this.writable;
    this.delete.disabled=editor.loading||editor.failedRead||editor.busy||this.markdown?.loading||editor.uncertain||editor.stale||!editor.etag||!editor.present||!this.writable;
    this.cancel.disabled=editor.busy;
    if(this.reload){this.reload.hidden=!(editor.failedRead||editor.stale||editor.uncertain);this.reload.disabled=editor.loading||editor.busy||this.markdown.loading;this.reload.textContent=t(editor.uncertain?'journal.check_result':'journal.reload');}
    if(this.recovery){
      if(editor.failedRead||editor.stale||editor.uncertain){if(this.markdown.root.previousSibling!==this.recovery)this.markdown.root.before(this.recovery);}
      else if(this.markdown.root.nextSibling!==this.recovery)this.markdown.root.after(this.recovery);
    }
    this.recovery.hidden=this.reload.hidden;
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
    const range=first+'|'+last;if(range!==this.range)this.loaded=false;this.range=range;this.key=key;this.loadingFocus=this.grid.contains(document.activeElement)?document.activeElement?.getAttribute(this.noteAttr):this.loadingFocus;this.paint();this.status.hidden=false;this.message.textContent=t('day_notes.loading');this.retry.hidden=true;
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
    focused=focused|| (this.grid.contains(document.activeElement)?document.activeElement?.getAttribute(this.noteAttr):null);
    for(const [date,cell] of this.cells){
      const heading=cell.querySelector(this.headingSelector),previous=heading.querySelector('.track-day-note'),text=this.notes.get(date),hasBody=this.bodyDates.has(date);
      if(!this.alwaysShow&&!this.writable&&!text&&!hasBody){previous?.remove();continue;}
      const interactive=this.alwaysShow||this.writable||hasBody;
      const value=node(interactive?'button':'span',undefined,'track-day-note'+(this.alwaysShow?' calendar-day-note':'')+(text||hasBody?'':' is-empty'));value.setAttribute(this.noteAttr,date);
      if(interactive){value.type='button';value.classList.add('btn','btn-link','btn-sm');value.disabled=!this.loaded||!this.writable&&!text&&!hasBody;value.setAttribute('aria-haspopup','dialog');value.addEventListener('click',()=>void this.open(date));}
      value.title=text||(hasBody?t('day_notes.body_present'):t('day_notes.add',{date:this.dateLabel(date)}));value.setAttribute('aria-label',t(text||hasBody?(this.writable?'day_notes.edit':'day_notes.view'):'day_notes.add',{date:this.dateLabel(date),text:text||(hasBody?t('day_notes.body_present'):'')}));
      if(text&&!this.compact)value.append(node('span',text,'badge rounded-pill bg-info-subtle text-info-emphasis'));
      value.append(icon(hasBody?'journal-text':'journal-plus'));value.classList.toggle('has-body',hasBody);
      if(previous)previous.replaceWith(value);else heading.append(value);
    }
    if(focused&&!this.root.hidden)this.grid.querySelector(`button[${this.noteAttr}="${focused}"]`)?.focus({preventScroll:true});
  }
  async open(date){
    if(!this.session||this.editor||!this.inline&&!this.cells?.has(date))return;
    const editor={date,session:this.session,loading:true,busy:false,uncertain:false};this.editor=editor;this.error.textContent='';this.text.value=this.notes.get(date)||'';
    this.title.textContent=this.inline?t('day_notes.count'):t('day_notes.title',{date:this.dateLabel(date)});this.controls();if(!this.inline)this.dialog.showModal();this.content.scrollTop=0;
    await this.loadEditor(editor);
  }
  async loadEditor(editor){
    editor.loading=true;this.controls();this.savedStatus.textContent=t('day_notes.loading');
    try{
      const reply=await editor.session.request('/day-entries/'+editor.date);
      if(this.editor!==editor||editor.session!==this.session)return;
      this.text.value=reply.data.text;editor.originalText=reply.data.text;editor.originalBody=reply.data.body;editor.etag=reply.etag;editor.noteEtag=reply.data.noteEtag;editor.noteVersion=reply.data.version;editor.labelVersion=reply.data.labelVersion;editor.present=reply.data.version>0&&!reply.data.deleted;editor.loading=false;editor.failedRead=false;editor.stale=false;editor.uncertain=false;
      this.markdown.setValue(reply.data.body);this.error.textContent='';this.savedStatus.textContent=t(this.writable?'journal.saved':'journal.read_only');this.controls();this.content.scrollTop=0;if(!this.inline)this.text.focus({preventScroll:true});void this.markdown.showFormatted(false);
    }catch(error){
      if(this.editor!==editor||editor.session!==this.session)return;
      editor.loading=false;editor.failedRead=true;this.error.textContent=error.message;this.savedStatus.textContent='';this.controls();this.error.scrollIntoView({block:'nearest'});if(error.status===401)this.callbacks.onUnauthorized(error);
    }
  }
  async reloadEditor(){
    const editor=this.editor;if(!editor||editor.loading||editor.busy||this.markdown.loading)return;
    if(editor.uncertain){
      editor.loading=true;this.controls();
      try{
        const reply=await editor.session.request('/day-entries/'+editor.date);if(this.editor!==editor||editor.session!==this.session)return;
        if(editor.sent?.delete?reply.data.deleted:(!Object.hasOwn(editor.sent,'text')||reply.data.text===editor.sent.text)&&(!Object.hasOwn(editor.sent,'body')||reply.data.body===editor.sent.body)){this.acceptSaved(reply,editor,{preserveLabelDraft:!!editor.sent?.delete});return;}
        editor.uncertain=false;editor.stale=true;this.error.textContent=t('journal.review_saved');
      }catch(error){if(this.editor===editor){this.error.textContent=error.message;if(error.status===401)this.callbacks.onUnauthorized(error);}}
      finally{if(this.editor===editor){editor.loading=false;this.controls();if(this.error.textContent)this.error.scrollIntoView({block:'nearest'});}}
      return;
    }
    if(this.dirty()&&!confirm(t('journal.reload_confirm')))return;
    await this.loadEditor(editor);
  }
  acceptSaved(reply,editor,{preserveLabelDraft=false}={}){
    const draft=preserveLabelDraft&&this.text.value.trim()!==editor.originalText?{value:this.text.value,version:editor.labelVersion,original:editor.originalText}:null;
    ++this.generation;this.key=null;
    if(reply.data.hasBody)this.bodyDates.add(editor.date);else this.bodyDates.delete(editor.date);
    if(reply.data.text||reply.data.hasBody)this.notes.set(editor.date,reply.data.text);else this.notes.delete(editor.date);
    this.paint();
    if(this.inline||draft){
      editor.originalText=reply.data.text;editor.originalBody=reply.data.body;editor.etag=reply.etag;editor.noteEtag=reply.data.noteEtag;editor.noteVersion=reply.data.version;editor.labelVersion=reply.data.labelVersion;editor.present=reply.data.version>0&&!reply.data.deleted;
      this.text.value=draft?draft.value:reply.data.text;
      if(draft&&draft.version!==reply.data.labelVersion){editor.originalText=draft.original;editor.labelVersion=draft.version;editor.stale=true;this.error.textContent=t('day_labels.stale');}
      if(this.markdown.value()!==reply.data.body){const formatted=!this.markdown.rich.hidden;this.markdown.setValue(reply.data.body);if(formatted)void this.markdown.showFormatted(false);}
      this.savedStatus.textContent=t(draft?'journal.unsaved':'journal.saved');this.controls();
    }
    else this.closeEditor();
    this.callbacks.onChanged();
  }
  async commit(){
    const editor=this.editor;if(!editor||editor.busy||editor.loading||editor.failedRead||editor.uncertain||editor.stale||!editor.etag||!this.writable||editor.session!==this.session)return;
    if(this.markdown.loading)return;
    const body=this.markdown.value();if(new TextEncoder().encode(body).length>200000){this.error.textContent=t('journal.too_large');this.error.scrollIntoView({block:'nearest'});return;}
    const text=this.text.value.trim();editor.sent={};
    if(text!==editor.originalText)Object.assign(editor.sent,{text,labelVersion:editor.labelVersion});
    if(body!==editor.originalBody)Object.assign(editor.sent,{body,noteVersion:editor.noteVersion});
    if(!Object.keys(editor.sent).length){this.savedStatus.textContent=t('journal.saved');if(!this.inline)this.closeEditor();return;}
    editor.busy=true;this.error.textContent='';this.savedStatus.textContent=t('journal.saving');this.controls();
    try{
      const reply=await editor.session.request('/day-entries/'+editor.date,{method:'POST',body:editor.sent});
      if(this.editor!==editor||editor.session!==this.session)return;
      this.acceptSaved(reply,editor);
    }catch(error){
      if(this.editor!==editor||editor.session!==this.session)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      editor.uncertain=error.uncertain;editor.stale=error.status===412;this.error.textContent=error.uncertain?t('day_notes.uncertain'):error.message;this.savedStatus.textContent=t('journal.unsaved');
    }finally{if(this.editor===editor){editor.busy=false;this.controls();if(this.error.textContent)this.error.scrollIntoView({block:'nearest'});}}
  }
  async deleteNote(){
    const editor=this.editor;if(!editor||editor.busy||editor.loading||editor.failedRead||editor.uncertain||editor.stale||!editor.present||!editor.etag||!this.writable||editor.session!==this.session)return;
    if(!confirm(t('journal.delete_confirm',{date:this.dateLabel(editor.date)})))return;
    editor.sent={delete:true};editor.busy=true;this.error.textContent='';this.savedStatus.textContent=t('journal.deleting');this.controls();
    try{
      await editor.session.request('/day-notes/'+editor.date+'/content',{method:'DELETE',etag:editor.noteEtag});
      if(this.editor!==editor||editor.session!==this.session)return;
      const reply=await editor.session.request('/day-entries/'+editor.date);
      if(this.editor!==editor||editor.session!==this.session)return;
      this.acceptSaved(reply,editor,{preserveLabelDraft:true});
    }catch(error){
      if(this.editor!==editor||editor.session!==this.session)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      editor.uncertain=error.uncertain;editor.stale=error.status===412;this.error.textContent=error.uncertain?t('day_notes.uncertain'):error.message;this.savedStatus.textContent=t('journal.unsaved');
    }finally{if(this.editor===editor){editor.busy=false;this.controls();if(this.error.textContent)this.error.scrollIntoView({block:'nearest'});}}
  }
}

import {t} from './i18n.js';
import {iconButton} from './icons.js';
import {MarkdownField} from './markdown-field.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(text,className)=>{const value=node('button',text,className);value.type='button';return value;};

/** Shared guarded Markdown dialog for week and calendar-event notes. */
export class JournalNoteEditor {
  constructor(root,callbacks,prefix,fieldPrefix=prefix+'-journal'){
    this.callbacks=callbacks;this.generation=0;
    this.dialog=node('dialog',undefined,'app-dialog journal-dialog shadow-lg');this.dialog.id=prefix+'-editor';
    this.title=node('h2');this.title.id=prefix+'-title';this.dialog.setAttribute('aria-labelledby',this.title.id);
    this.closeButton=iconButton(button('','btn btn-outline-secondary'),'close',t('ui.close'));this.closeButton.addEventListener('click',()=>this.close());
    const heading=node('div',undefined,'week-note-heading');heading.append(this.title,this.closeButton);
    this.error=node('p','', 'alert alert-danger');this.error.setAttribute('role','alert');this.error.hidden=true;
    this.status=node('p','', 'hint');this.status.setAttribute('role','status');
    const host=node('div');this.markdown=new MarkdownField(host,()=>this.changed(),()=>this.controls(),fieldPrefix);
    const actions=node('div',undefined,'dialog-actions');
    this.cancel=button(t('ui.cancel'),'btn btn-outline-secondary');this.cancel.addEventListener('click',()=>this.close());
    this.delete=button(t('journal.delete_note'),'btn btn-outline-secondary');this.delete.addEventListener('click',()=>void this.remove());
    this.save=button(t('ui.save'),'btn btn-primary');this.save.addEventListener('click',()=>void this.commit());
    actions.append(this.status,this.cancel,this.delete,this.save);
    this.dialog.append(heading,this.error,host,actions);root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{event.preventDefault();this.close();});
    this.dialog.addEventListener('close',()=>{if(!root.closest('[hidden]')){const origin=this.context?.originSelector?root.querySelector(this.context.originSelector):this.origin;if(origin?.isConnected)origin.focus();}});
    window.addEventListener('beforeunload',event=>{if(this.dirty()){event.preventDefault();event.returnValue='';}});
  }
  dirty(){return this.note&&this.markdown.value()!==this.note.body;}
  controls(){
    if(!this.markdown)return;
    const ready=!!this.note&&!this.busy,write=ready&&this.writable&&!this.stale;
    if(this.markdown.readOnly===write)this.markdown.setReadOnly(!write);
    this.save.disabled=!write||this.markdown.loading;
    this.delete.disabled=!write||!this.note?.version||this.note?.deleted;
    this.closeButton.disabled=!!this.busy;
    this.cancel.disabled=!!this.busy;
  }
  changed(){this.status.textContent='';this.controls();}
  async open(context,session,writable){
    if(!session||this.dialog.open)return;
    this.context=context;this.path=context.path;this.origin=document.activeElement;this.session=session;this.writable=writable;this.note=null;this.stale=false;this.busy=false;
    this.title.textContent=context.title;this.error.hidden=true;this.status.textContent=t('day_notes.loading');
    this.markdown.clear();this.controls();this.dialog.showModal();
    const generation=++this.generation;
    try{
      const reply=await session.request(this.path);
      if(generation!==this.generation||!this.dialog.open)return;
      this.note={...reply.data,etag:reply.etag};this.markdown.setValue(reply.data.body);this.status.textContent=writable?t('journal.saved'):t('journal.read_only');this.controls();
      void this.markdown.showFormatted(false);
    }catch(error){
      if(generation!==this.generation)return;
      this.error.textContent=error.message;this.error.hidden=false;this.status.textContent='';if(error.status===401)this.callbacks.onUnauthorized(error);
    }
  }
  close(force=false){
    if(!this.dialog.open)return;
    if(!force&&(this.busy||this.dirty()&&!confirm(t('journal.discard'))))return;
    ++this.generation;this.dialog.close();this.note=null;this.session=null;this.markdown.clear();
  }
  async commit(){
    if(!this.note||this.busy||this.stale||!this.writable||this.markdown.loading)return;
    const body=this.markdown.value();if(new TextEncoder().encode(body).length>200000){this.error.textContent=t('journal.too_large');this.error.hidden=false;return;}
    this.busy=true;this.status.textContent=t('journal.saving');this.controls();
    const generation=this.generation;
    try{
      const reply=await this.session.request(this.path,{method:'PUT',etag:this.note.etag,body:{...this.context.input,body}});
      if(generation!==this.generation)return;
      this.note={...reply.data,etag:reply.etag};this.error.hidden=true;this.status.textContent=t('journal.saved');this.callbacks.onNotesChanged?.();
    }catch(error){if(generation===this.generation){this.stale=true;this.error.textContent=error.uncertain?t('day_notes.uncertain'):error.message;this.error.hidden=false;this.status.textContent=t('journal.unsaved');if(error.status===401)this.callbacks.onUnauthorized(error);}}
    finally{if(generation===this.generation){this.busy=false;this.controls();}}
  }
  async remove(){
    if(!this.note?.version||this.note.deleted||this.busy||this.stale||!this.writable)return;
    if(!confirm(this.context.deleteConfirm))return;
    this.busy=true;this.controls();const generation=this.generation;
    try{
      await this.session.request(this.path,{method:'DELETE',etag:this.note.etag});
      if(generation!==this.generation)return;
      this.note={...this.note,body:'',deleted:true};this.markdown.setValue('');this.callbacks.onNotesChanged?.();
      this.busy=false;this.close();
    }catch(error){if(generation===this.generation){this.stale=true;this.error.textContent=error.uncertain?t('day_notes.uncertain'):error.message;this.error.hidden=false;if(error.status===401)this.callbacks.onUnauthorized(error);}}
    finally{if(generation===this.generation){this.busy=false;this.controls();}}
  }
}

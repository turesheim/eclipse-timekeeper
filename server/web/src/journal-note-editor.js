import {DocumentAutosave,bindDocumentAutosave} from './document-autosave.js';
import {noteRevisionResources} from './note-revisions.js';
import {vaultEditorResources} from './vault-editor-resources.js';
import {journalImageResources} from './image-preview.js';
import {t} from './i18n.js';
import {iconButton} from './icons.js';
import {MarkdownField} from './markdown-field.js';
import {guardDayNoteNavigation} from './journal-links.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(text,className)=>{const value=node('button',text,className);value.type='button';return value;};

/** Shared guarded Markdown dialog for week and calendar-event notes. */
export class JournalNoteEditor {
  constructor(root,callbacks,prefix,fieldPrefix=prefix+'-journal'){
    this.callbacks=callbacks;this.generation=0;this.positions=new Map();
    this.dialog=node('dialog',undefined,'app-dialog journal-dialog shadow-lg');this.dialog.id=prefix+'-editor';
    this.title=node('h2');this.title.id=prefix+'-title';this.dialog.setAttribute('aria-labelledby',this.title.id);
    this.closeButton=iconButton(button('','btn btn-outline-secondary'),'close',t('ui.close'));this.closeButton.addEventListener('click',()=>this.close());
    const heading=node('div',undefined,'week-note-heading');heading.append(this.title,this.closeButton);
    this.error=node('p','', 'alert alert-danger');this.error.setAttribute('role','alert');this.error.hidden=true;
    this.status=node('p','', 'hint');this.status.setAttribute('role','status');
    const host=node('div');this.markdown=new MarkdownField(host,()=>this.changed(),()=>this.controls(),fieldPrefix,(file,image)=>this.uploadAsset(file,image),{...noteRevisionResources(()=>this.session,()=>({sourceType:this.path?.startsWith('/week-notes/')?'WEEK_NOTE':'EVENT_NOTE',sourceId:this.note?.id}),{bodyOnly:true,latest:async()=>{const reply=await this.session.request(this.path);return {text:reply.data.body,etag:reply.etag};}}),...journalImageResources(()=>this.session),...vaultEditorResources(()=>this.session,()=>({sourceType:this.path?.startsWith('/week-notes/')?'WEEK_NOTE':'EVENT_NOTE',sourceId:this.note?.id,sourceKey:this.note?.week||this.note?.subscriptionId&&('event-'+this.note.subscriptionId+'-'+this.note.eventKey+'.md')}))});
    const actions=node('div',undefined,'dialog-actions');
    this.delete=iconButton(button('','btn btn-outline-secondary'),'trash',t('journal.delete_note'));this.delete.addEventListener('click',()=>void this.remove());
    const documentTools=node('div');documentTools.append(this.delete);this.markdown.addActionsGroup(documentTools);
    this.retrySave=button(t('journal.retry_save'),'btn btn-outline-secondary');this.retrySave.hidden=true;this.retrySave.addEventListener('click',()=>void this.retryCommit());
    this.reload=button(t('journal.reload'),'btn btn-outline-secondary');this.reload.hidden=true;this.reload.addEventListener('click',()=>void this.reloadSaved());
    actions.append(this.status,this.retrySave,this.reload);
    this.autosave=new DocumentAutosave({dirty:()=>!!this.dirty(),ready:()=>!!this.note&&this.writable&&!this.busy&&!this.stale&&!this.markdown.loading,save:options=>this.commit(options)});bindDocumentAutosave(this.dialog,this.autosave);
    this.dialog.append(heading,this.error,host,actions);root.append(this.dialog);this.dialog.addEventListener('keydown',event=>this.markdown.historyShortcut(event));
    this.dialog.addEventListener('cancel',event=>{event.preventDefault();this.close();});
    guardDayNoteNavigation(this.dialog,()=>!this.busy&&this.autosave.flush(),()=>this.close(true));
    this.dialog.addEventListener('close',()=>{if(!root.closest('[hidden]')){const origin=this.context?.originSelector?root.querySelector(this.context.originSelector):this.origin;if(origin?.isConnected)origin.focus();}});
    window.addEventListener('beforeunload',event=>{if(this.dirty()){event.preventDefault();event.returnValue='';}});
  }
  dirty(){return this.note&&!this.markdown.matches(this.note.body);}
  controls(){
    if(!this.markdown)return;
    const ready=!!this.note&&!this.busy,write=ready&&this.writable&&!this.stale;
    if(this.markdown.readOnly===write)this.markdown.setReadOnly(!write);
    if(this.delete){this.delete.disabled=!write||this.saving||!this.note?.version||this.note?.deleted;this.markdown.syncActions();}
    if(this.retrySave){this.retrySave.hidden=!this.saveFailed;this.retrySave.disabled=!!this.saving||!!this.busy||!this.writable||this.stale&&!this.uncertain;this.retrySave.textContent=t(this.uncertain?'journal.check_result':'journal.retry_save');}
    if(this.reload){this.reload.hidden=!this.stale;this.reload.disabled=!!this.saving||!!this.busy;}
    this.closeButton.disabled=!!this.busy;
    if(this.autosave&&this.dirty()&&write&&!this.saving&&!this.saveFailed&&!this.markdown.loading)this.autosave.changed();
    if(this.note)this.status.textContent=t(this.saving?'journal.saving':this.dirty()?'journal.unsaved':this.writable?'journal.saved':'journal.read_only');
  }
  changed(){this.controls();this.autosave?.changed();}
  async uploadAsset(file,image){
    const session=this.session,generation=this.generation;if(!session||!this.writable||file.size>5_000_000)throw new Error(t('markdown.upload_failed'));
    const extensions={'image/png':'png','image/jpeg':'jpg','image/gif':'gif','image/webp':'webp'},suffix=image?extensions[file.type]:(file.name.match(/\.([a-zA-Z0-9]{1,10})$/)?.[1]||'bin');if(!suffix)throw new Error(t('markdown.upload_failed'));
    const filename=crypto.randomUUID()+'.'+suffix.toLowerCase(),bytes=new Uint8Array(await file.arrayBuffer());let data='';for(let at=0;at<bytes.length;at+=8192)data+=String.fromCharCode(...bytes.subarray(at,at+8192));
    if(session!==this.session||generation!==this.generation)throw new Error(t('markdown.upload_failed'));
    await session.request('/journal-assets/'+filename,{method:'PUT',noneMatch:'*',body:{data:btoa(data)},emptyResponse:true,timeout:60000});
    if(session!==this.session||generation!==this.generation)throw new Error(t('markdown.upload_failed'));return 'assets/'+filename;
  }
  async open(context,session,writable){
    if(!session||this.dialog.open)return;
    if(this.positionSession&&this.positionSession!==session)this.positions.clear();this.positionSession=session;
    this.autosave.reset();this.saveFailed=false;this.uncertain=null;this.saving=false;this.context=context;this.path=context.path;this.origin=document.activeElement;this.session=session;this.writable=writable;this.note=null;this.stale=false;this.busy=false;
    this.title.textContent=context.title;this.error.hidden=true;this.status.textContent=t('day_notes.loading');
    this.markdown.clear();this.controls();this.dialog.showModal();
    const generation=++this.generation;
    try{
      const reply=await session.request(this.path);
      if(generation!==this.generation||!this.dialog.open)return;
      this.note={...reply.data,etag:reply.etag};this.markdown.setValue(reply.data.body);this.status.textContent=writable?t('journal.saved'):t('journal.read_only');this.controls();
      const position=this.positions.get(this.path);if(position)await this.markdown.restorePosition({...position,formatted:true});else void this.markdown.showFormatted(false);
    }catch(error){
      if(generation!==this.generation)return;
      this.error.textContent=error.message;this.error.hidden=false;this.status.textContent='';if(error.status===401)this.callbacks.onUnauthorized(error);
    }
  }
  async close(force=false){
    if(!this.dialog.open)return;
    if(!force&&(this.busy||!await this.autosave.flush()))return;
    this.autosave.reset();
    if(this.path&&this.note)this.positions.set(this.path,this.markdown.position());
    ++this.generation;this.dialog.close();this.note=null;this.session=null;this.markdown.clear();
  }
  reset(){this.close(true);this.positions.clear();this.positionSession=null;}
  async commit({keepalive=false}={}){
    if(!this.note||this.busy||this.saving||this.stale||!this.writable||this.markdown.loading)return false;
    if(!this.dirty())return true;
    const body=this.markdown.value();if(new TextEncoder().encode(body).length>200000){this.error.textContent=t('journal.too_large');this.error.hidden=false;return false;}
    this.saving=true;this.controls();const generation=this.generation,session=this.session,path=this.path;
    try{
      const reply=await session.request(path,{method:'PUT',etag:this.note.etag,body:{...this.context.input,body},keepalive:keepalive&&new TextEncoder().encode(body).length<60000});
      if(generation!==this.generation||session!==this.session)return false;
      this.note={...reply.data,etag:reply.etag};this.saveFailed=false;this.uncertain=null;this.error.hidden=true;this.callbacks.onNotesChanged?.();return true;
    }catch(error){if(generation===this.generation){this.stale=error.status===412||!!error.uncertain;this.saveFailed=true;if(error.uncertain)this.uncertain={body,path,etag:this.note.etag};this.error.textContent=error.uncertain?t('day_notes.uncertain'):error.message;this.error.hidden=false;if(error.status===401)this.callbacks.onUnauthorized(error);}return false;}
    finally{if(generation===this.generation){this.saving=false;this.controls();}}
  }
  async retryCommit(){
    if(!this.uncertain){await this.autosave.flush();return;}
    const sent=this.uncertain,generation=this.generation;this.saving=true;this.controls();
    try{
      const reply=await this.session.request(sent.path);if(generation!==this.generation)return;
      this.uncertain=null;if(reply.etag===sent.etag){this.stale=false;this.saveFailed=false;this.error.hidden=true;return;}if(reply.data.body!==sent.body){this.stale=true;this.error.textContent=t('journal.review_saved');return;}
      this.note={...reply.data,etag:reply.etag};this.stale=false;this.saveFailed=false;this.error.hidden=true;this.callbacks.onNotesChanged?.();
    }catch(error){if(generation===this.generation){this.error.textContent=error.message;this.error.hidden=false;if(error.status===401)this.callbacks.onUnauthorized(error);}}
    finally{if(generation===this.generation){this.saving=false;this.controls();if(!this.stale&&this.dirty())this.autosave.changed();}}
  }
  async reloadSaved(){
    if(this.busy||this.saving||this.dirty()&&!confirm(t('journal.reload_confirm')))return;
    const generation=this.generation;
    try{const reply=await this.session.request(this.path);if(generation!==this.generation)return;this.autosave.reset();this.note={...reply.data,etag:reply.etag};this.markdown.setValue(reply.data.body);this.stale=false;this.saveFailed=false;this.uncertain=null;this.error.hidden=true;this.controls();void this.markdown.showFormatted(false);}
    catch(error){if(generation===this.generation){this.error.textContent=error.message;this.error.hidden=false;if(error.status===401)this.callbacks.onUnauthorized(error);}}
  }
  async remove(){
    if(!this.note?.version||this.note.deleted||this.busy||this.stale||!this.writable)return;
    if(!await this.autosave.flush()||!confirm(this.context.deleteConfirm))return;
    this.busy=true;this.controls();const generation=this.generation;
    try{
      await this.session.request(this.path,{method:'DELETE',etag:this.note.etag});
      if(generation!==this.generation)return;
      this.note={...this.note,body:'',deleted:true};this.markdown.setValue('');this.callbacks.onNotesChanged?.();
      this.busy=false;this.close(true);
    }catch(error){if(generation===this.generation){this.stale=true;this.error.textContent=error.uncertain?t('day_notes.uncertain'):error.message;this.error.hidden=false;if(error.status===401)this.callbacks.onUnauthorized(error);}}
    finally{if(generation===this.generation){this.busy=false;this.controls();}}
  }
}

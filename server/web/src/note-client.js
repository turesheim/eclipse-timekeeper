import {icon} from './icons.js';
import {t,getLocale} from './i18n.js';

const fields=new Set();let directory={session:null,revision:null,clients:[]};
/** The complete owned list is independent of the workspace's focus filter. */
export function updateNoteClientDirectory(session,clients=[],revision=null){
  if(directory.session===session&&directory.revision===revision&&directory.clients===clients)return;
  directory={session,revision,clients};for(const field of fields)field.sync();
}
const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(text,run)=>{const value=node('button',text,'btn btn-outline-secondary');value.type='button';value.addEventListener('click',run);return value;};
const same=(a,b)=>!!a&&!!b&&a.session===b.session&&a.sourceType===b.sourceType&&a.sourceKey===b.sourceKey;

/** Client metadata never participates in the Markdown field's draft or saves. */
export class NoteClient {
  constructor(field,options){
    Object.assign(this,{field,options,generation:0});fields.add(this);
    this.label=node('span',undefined,'note-client-label');this.label.hidden=true;this.swatch=node('span',undefined,'note-client-swatch');this.swatch.setAttribute('aria-hidden','true');this.name=node('span');this.label.append(this.swatch,this.name);field.toolbar.append(this.label);
    this.action=button('',()=>void this.open());this.action.setAttribute('aria-label',t('notes.client'));this.action.append(icon('briefcase'));this.group=node('div');this.group.append(this.action);field.addActionsGroup(this.group);
    this.dialog=node('dialog',undefined,'app-dialog note-client-dialog');const id='note-client-'+crypto.randomUUID(),heading=node('h2',t('notes.client'));heading.id=id+'-heading';this.dialog.setAttribute('aria-labelledby',heading.id);
    const label=node('label',t('notes.client'),'form-label');this.select=node('select',undefined,'form-select');this.select.id=id;label.htmlFor=id;
    this.error=node('p','','alert alert-danger');this.error.setAttribute('role','alert');this.error.hidden=true;
    this.save=button(t('ui.save'),()=>void this.commit());this.save.className='btn btn-primary';this.cancel=button(t('ui.cancel'),()=>this.dialog.close());const actions=node('div',undefined,'dialog-actions');actions.append(this.cancel,this.save);this.dialog.append(heading,label,this.select,this.error,actions);field.root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{if(this.saving)event.preventDefault();});
    this.dialog.addEventListener('close',()=>{if(same(this.openContext,this.current())&&this.field.root.getClientRects().length)this.field.actionsTrigger.focus({preventScroll:true});if(this.refreshPending){this.refreshPending=false;this.revision=null;this.sync();}});
  }
  current(){const value=this.options.current?.();return value?.session===directory.session&&value?.sourceType&&value?.sourceKey?value:null;}
  path(context){return '/notes/client?'+new URLSearchParams({sourceType:context.sourceType,sourceKey:context.sourceKey});}
  active(context,generation){return generation===this.generation&&same(context,this.current());}
  visible(){return !!this.field.root.getClientRects().length;}
  sync(){
    const context=this.current();
    if(!same(context,this.context)){
      this.abort?.abort();++this.generation;this.context=context;this.data=this.etag=null;this.busy=false;this.saving=false;this.revision=null;this.refreshPending=false;this.dialog.close();
    }
    if(context&&this.visible()&&this.revision!==directory.revision){
      if(this.dialog.open||this.busy)this.refreshPending=true;else{this.revision=directory.revision;void this.load();}
    }
    this.paint();
  }
  paint(){
    const context=this.current(),client=context&&directory.clients.find(value=>value.id===this.data?.clientId),hidden=!client;
    if(this.label.hidden!==hidden)this.label.hidden=hidden;
    if(client){if(this.name.textContent!==client.name)this.name.textContent=client.name;if(this.label.title!==client.name)this.label.title=client.name;const color=/^#[a-f0-9]{6}$/i.test(client.color||'')?client.color:'';if(this.color!==color){this.color=color;this.swatch.style.backgroundColor=color||'var(--bs-secondary)';}}
    if(this.group.hidden!==!context)this.group.hidden=!context;
    const disabled=!context?.writable||this.busy;if(this.action.disabled!==disabled)this.action.disabled=disabled;
    this.select.disabled=this.busy||!context?.writable;this.save.disabled=this.busy||!context?.writable;this.cancel.disabled=!!this.saving;this.field.syncActions();
  }
  choices(){
    this.select.replaceChildren(new Option(t('notes.no_client'),''));
    for(const client of [...directory.clients].sort((a,b)=>a.name.localeCompare(b.name,getLocale(),{numeric:true,sensitivity:'base'})||a.id.localeCompare(b.id)))this.select.append(new Option(client.name,client.id));
    this.select.value=this.data?.clientId||'';
  }
  failure(error){this.error.textContent=error.message;this.error.hidden=false;this.options.error?.(error);}
  async load(){
    const context=this.context;if(!context||this.busy)return;const generation=this.generation;this.abort=new AbortController();this.busy=true;this.paint();
    try{const reply=await context.session.request(this.path(context),{signal:this.abort.signal});if(this.active(context,generation)){this.data=reply.data;this.etag=reply.etag;if(this.dialog.open)this.choices();}}
    catch(error){if(this.active(context,generation)&&error.name!=='AbortError')this.failure(error);}
    finally{if(this.active(context,generation)){this.busy=false;this.paint();if(this.refreshPending&&!this.dialog.open){this.refreshPending=false;this.revision=null;this.sync();}}}
  }
  async open(){
    this.sync();if(this.action.disabled)return;this.field.closeActions();this.openContext=this.context;this.error.hidden=true;this.choices();this.dialog.showModal();
    if(!this.etag)await this.load();if(this.dialog.open)this.select.focus();
  }
  async commit(){
    const context=this.current(),generation=this.generation;if(!same(context,this.openContext)||!context.writable||this.busy)return;
    if(!this.etag){await this.load();return;}const clientId=this.select.value||null;if(clientId&&!directory.clients.some(value=>value.id===clientId))return;
    if(clientId===this.data.clientId){this.dialog.close();return;}this.abort=new AbortController();this.saving=true;this.busy=true;this.error.hidden=true;this.paint();
    try{
      const reply=await context.session.request(this.path(context),{method:'PUT',body:{clientId},etag:this.etag,signal:this.abort.signal});
      if(this.active(context,generation)){this.data=reply.data;this.etag=reply.etag;this.dialog.close();document.dispatchEvent(new CustomEvent('timekeeper:notes-changed'));}
    }catch(error){
      if(this.active(context,generation)&&error.name!=='AbortError'){this.failure(error);this.saving=false;this.busy=false;await this.load();}
    }finally{if(this.active(context,generation)){this.saving=false;this.busy=false;this.paint();}}
  }
}

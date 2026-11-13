import {icon} from './icons.js';
import {t,getLocale} from './i18n.js';

const fields=new Set();let directory={session:null,revision:null,clients:[],projects:[]};
/** The complete owned list is independent of the workspace's focus filter. */
export function updateNoteClientDirectory(session,clients=[],revision=null,projects=[]){
  if(directory.session===session&&directory.revision===revision&&directory.clients===clients&&directory.projects===projects)return;
  directory={session,revision,clients,projects};for(const field of fields)field.sync();
}
const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(text,run)=>{const value=node('button',text,'btn btn-outline-secondary');value.type='button';value.addEventListener('click',run);return value;};
const same=(a,b)=>!!a&&!!b&&a.session===b.session&&a.sourceType===b.sourceType&&a.sourceKey===b.sourceKey;

/** Client metadata never participates in the Markdown field's draft or saves. */
export class NoteClient {
  constructor(field,options){
    Object.assign(this,{field,options,generation:0});fields.add(this);
    this.line=node('div',undefined,'note-client-line');this.line.hidden=true;this.label=node('span',undefined,'note-client-label');this.label.hidden=true;this.swatch=node('span',undefined,'note-client-swatch');this.swatch.setAttribute('aria-hidden','true');this.name=node('span');this.label.append(this.swatch,this.name);this.line.append(this.label);field.toolbar.before(this.line);
    this.action=button('',()=>void this.open());this.action.setAttribute('aria-label',t('notes.client'));this.action.append(icon('briefcase'));this.group=node('div');this.group.append(this.action);field.addActionsGroup(this.group);
    this.dialog=node('dialog',undefined,'app-dialog note-client-dialog');const id='note-client-'+crypto.randomUUID(),heading=node('h2',t('notes.client'));heading.id=id+'-heading';this.dialog.setAttribute('aria-labelledby',heading.id);
    const label=node('label',t('ui.client'),'form-label');this.select=node('select',undefined,'form-select');this.select.id=id;label.htmlFor=id;
    const projectLabel=node('label',t('ui.project'),'form-label mt-3');this.project=node('select',undefined,'form-select');this.project.id=id+'-project';projectLabel.htmlFor=this.project.id;
    this.select.addEventListener('change',()=>this.projectChoices(this.project.value));this.project.addEventListener('change',()=>{const selected=directory.projects.find(value=>value.id===this.project.value);if(selected){this.select.value=selected.clientId;this.projectChoices(selected.id);}});
    this.error=node('p','','alert alert-danger');this.error.setAttribute('role','alert');this.error.hidden=true;
    this.save=button(t('ui.save'),()=>void this.commit());this.save.className='btn btn-primary';this.cancel=button(t('ui.cancel'),()=>this.dialog.close());const actions=node('div',undefined,'dialog-actions');actions.append(this.cancel,this.save);this.dialog.append(heading,label,this.select,projectLabel,this.project,node('p',t('notes.association_tasks'),'hint'),this.error,actions);field.root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{if(this.saving)event.preventDefault();});
    this.dialog.addEventListener('close',()=>{if(same(this.openContext,this.current())&&this.field.root.getClientRects().length)(this.field.actionsMenu.contains(this.action)?this.field.actionsTrigger:this.action).focus({preventScroll:true});if(this.refreshPending){this.refreshPending=false;this.revision=null;this.sync();}});
  }
  current(){const value=this.options.current?.();return value?.session===directory.session&&value?.sourceType&&value?.sourceKey?value:null;}
  path(context){return '/notes/client?'+new URLSearchParams({sourceType:context.sourceType,sourceKey:context.sourceKey});}
  active(context,generation){return generation===this.generation&&same(context,this.current());}
  visible(){return !!this.field.root.getClientRects().length;}
  sync(){
    const context=this.current();
    if(!same(context,this.context)){
      this.abort?.abort();++this.generation;this.context=context;this.data=this.etag=this.openContext=null;this.busy=false;this.saving=false;this.revision=null;this.refreshPending=false;
      this.name.textContent='';this.label.title='';this.color=null;this.swatch.style.backgroundColor='';this.select.replaceChildren();this.project.replaceChildren();this.error.textContent='';this.error.hidden=true;this.dialog.close();
    }
    if(context&&this.visible()&&this.revision!==directory.revision){
      if(this.dialog.open||this.busy)this.refreshPending=true;else{this.revision=directory.revision;void this.load();}
    }
    this.paint();
  }
  paint(){
    const context=this.current(),client=context&&directory.clients.find(value=>value.id===this.data?.clientId),hidden=!client;
    if(this.label.hidden!==hidden)this.label.hidden=hidden;
    if(this.line.hidden!==hidden)this.line.hidden=hidden;
    if(client){const project=directory.projects.find(value=>value.id===this.data?.projectId),caption=[client.name,project?.name].filter(Boolean).join(' · ');if(this.name.textContent!==caption)this.name.textContent=caption;if(this.label.title!==caption)this.label.title=caption;const color=/^#[a-f0-9]{6}$/i.test(client.color||'')?client.color:'';if(this.color!==color){this.color=color;this.swatch.style.backgroundColor=color||'var(--bs-secondary)';}}
    if(this.group.hidden!==!context)this.group.hidden=!context;
    // A relocated opener keeps focus through background reads; dialog fields still wait.
    const disabled=!context?.writable||this.busy&&this.field.actionsMenu.contains(this.action);if(this.action.disabled!==disabled)this.action.disabled=disabled;
    this.select.disabled=this.project.disabled=this.busy||!context?.writable;this.save.disabled=this.busy||!context?.writable;this.cancel.disabled=!!this.saving;this.field.syncActions();
  }
  choices(){
    this.select.replaceChildren(new Option(t('notes.no_client'),''));
    for(const client of [...directory.clients].sort((a,b)=>a.name.localeCompare(b.name,getLocale(),{numeric:true,sensitivity:'base'})||a.id.localeCompare(b.id)))this.select.append(new Option(client.name,client.id));
    this.select.value=this.data?.clientId||'';
    this.projectChoices(this.data?.projectId||'');
  }
  projectChoices(selected){
    this.project.replaceChildren(new Option(t('notes.no_project'),''));
    for(const project of [...directory.projects].filter(value=>!this.select.value||value.clientId===this.select.value).sort((a,b)=>a.name.localeCompare(b.name,getLocale(),{numeric:true,sensitivity:'base'})||a.id.localeCompare(b.id)))this.project.append(new Option(project.name,project.id));
    this.project.value=[...this.project.options].some(option=>option.value===selected)?selected:'';
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
    if(!this.etag){await this.load();return;}const clientId=this.select.value||null,projectId=this.project.value||null;
    if(clientId===this.data.clientId&&projectId===(this.data.projectId||null)){this.dialog.close();return;}this.abort=new AbortController();this.saving=true;this.busy=true;this.error.hidden=true;this.paint();
    try{
      const reply=await context.session.request(this.path(context),{method:'PUT',body:{clientId,projectId},etag:this.etag,signal:this.abort.signal});
      if(this.active(context,generation)){this.data=reply.data;this.etag=reply.etag;this.dialog.close();document.dispatchEvent(new CustomEvent('timekeeper:notes-changed'));}
    }catch(error){
      if(this.active(context,generation)&&error.name!=='AbortError'){this.failure(error);this.saving=false;this.busy=false;await this.load();}
    }finally{if(this.active(context,generation)){this.saving=false;this.busy=false;this.paint();}}
  }
}

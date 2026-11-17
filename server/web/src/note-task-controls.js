import {t} from './i18n.js';
import {noteClientDirectory} from './note-client.js';
import {taskMomentFields} from './task-editor-fields.js';
import {splitMarkdown} from './markdown-document.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(label,run)=>{const value=node('button',label,'btn btn-outline-secondary');value.type='button';value.addEventListener('click',run);return value;};
const same=(a,b)=>!!a&&!!b&&a.session===b.session&&a.sourceType===b.sourceType&&a.sourceKey===b.sourceKey;
const normalize=value=>value.replace(/\r\n?/g,'\n');
const equivalent=(a,b)=>a===b||!!a&&!!b&&typeof a==='object'&&typeof b==='object'&&Object.keys(a).length===Object.keys(b).length&&Object.keys(a).every(key=>equivalent(a[key],b[key]));
let parser;

/** A contextual task editor. The checklist's Markdown is never rewritten here. */
export class NoteTaskControls {
  constructor(field){
    this.field=field;this.generation=0;
    this.action=button('+',()=>void this.open());this.action.className='btn btn-sm btn-outline-primary note-task-action';this.action.setAttribute('aria-label',t('notes.edit_task'));this.action.title=t('notes.edit_task');this.action.hidden=true;
    this.action.addEventListener('mousedown',event=>event.preventDefault());field.root.append(this.action);
    this.dialog=node('dialog',undefined,'app-dialog note-task-dialog');this.form=node('form');const heading=node('h2',t('notes.edit_task'));heading.id='note-task-'+crypto.randomUUID();this.dialog.setAttribute('aria-labelledby',heading.id);this.title=node('p','','note-task-title');this.inputs=node('div',undefined,'note-task-fields');
    this.error=node('p','','alert alert-danger');this.error.setAttribute('role','alert');this.error.hidden=true;
    this.cancel=button(t('ui.cancel'),()=>this.dialog.close());this.reload=button(t('notes.reload_task'),()=>{if(confirm(t('notes.reload_task_confirm')))void this.loadTask();});this.reload.hidden=true;
    this.check=button(t('notes.check_task_save'),()=>void this.checkSaved());this.check.hidden=true;this.save=node('button',t('ui.save'),'btn btn-primary');this.save.type='submit';const actions=node('div',undefined,'dialog-actions');actions.append(this.reload,this.check,this.cancel,this.save);this.form.append(heading,this.title,this.inputs,this.error,actions);this.dialog.append(this.form);field.root.append(this.dialog);
    this.form.addEventListener('submit',event=>{event.preventDefault();void this.commit();});
    this.dialog.addEventListener('cancel',event=>{event.stopPropagation();if(this.saving)event.preventDefault();});
    this.dialog.addEventListener('close',()=>{if(this.dialog.open)return;this.abort?.abort();++this.generation;this.busy=this.saving=false;this.inputs.replaceChildren();this.title.textContent='';this.error.textContent='';this.task=this.pending=null;if(same(this.openContext,this.current())&&field.root.getClientRects().length)field.focus();this.update();});
    for(const name of ['selectionchange','focusin','focusout','keyup','pointerup','scroll'])document.addEventListener(name,()=>this.update(),true);
    field.text.addEventListener('input',()=>this.update(100));window.addEventListener('resize',()=>this.update());
  }
  current(){return this.field.client?.current();}
  active(context,cycle){return this.dialog.open&&same(context,this.current())&&cycle===this.generation;}
  sync(){
    const current=this.current();if(!same(current,this.context)){this.context=current;this.sourceText=null;this.lines=[];this.selection=null;if(this.dialog.open){this.abort?.abort();this.dialog.close();}}
    if(this.dialog.open)this.paintDialog();this.update();
  }
  update(delay=0){if(document.activeElement===this.field.text&&this.sourceText!==this.field.text.value)delay=Math.max(delay,100);clearTimeout(this.timer);this.timer=setTimeout(()=>void this.paint(),delay);}
  async paint(){
    const f=this.field,current=this.current(),active=document.activeElement;
    if(this.dialog.open||!current?.writable||f.readOnly||f.loading||f.bannerBusy||!f.root.getClientRects().length){this.action.hidden=true;return;}
    if(active===this.action&&!this.action.hidden)return;
    let context,rect;
    if(f.editor&&f.rich.contains(active)){context=f.editor.taskContext();rect=context?.checkboxRect();}
    else if(active===f.text&&f.text.selectionStart===f.text.selectionEnd){
      const text=f.text.value,position=f.text.selectionStart,start=text.lastIndexOf('\n',position-1)+1,line=text.slice(start,text.indexOf('\n',position)<0?text.length:text.indexOf('\n',position));
      const marker=/^(\s*(?:>\s*)*(?:[-+*]|\d+[.)])\s+)\[[ xX]\]\s+\S/.exec(line);if(!marker){this.action.hidden=true;return;}
      if(this.sourceText!==text){
        this.action.hidden=true;
        // Keep the DOM-dependent bundle lazy for non-browser module consumers.
        try{parser??=import('./assets/milkdown/markdown-preview.js');const module=await parser;
          if(text!==f.text.value||position!==f.text.selectionStart||position!==f.text.selectionEnd||document.activeElement!==f.text||!same(current,this.current())||!this.current()?.writable||f.readOnly||f.loading||this.dialog.open)return;
          this.lines=module.noteTaskLines(text);this.sourceText=text;
        }catch{parser=null;return;}
      }
      const item=this.lines.find(item=>position>=item.start&&position<=item.end);if(item){context={offset:()=>item.offset};rect=f.caret.sourceRect(f.text,start+marker[1].length);}
    }
    if(!context||!rect){this.action.hidden=true;return;}const box=(f.editor?f.rich:f.text).getBoundingClientRect(),height=rect.height||rect.bottom-rect.top;
    if(rect.top<Math.max(0,box.top)||rect.top+height>Math.min(innerHeight,box.bottom)){this.action.hidden=true;return;}
    const size=24,left=rect.left-size-4,top=rect.top+(height-size)/2;if(left<0||left+size>innerWidth||top<0||top+size>innerHeight){this.action.hidden=true;return;}
    this.selection=context;this.action.style.left=left+'px';this.action.style.top=top+'px';this.action.hidden=false;
  }
  paintDialog(){
    const writable=this.current()?.writable&&!this.field.readOnly,disabled=this.busy||!writable;
    for(const input of this.inputs.querySelectorAll('input,textarea,select'))input.disabled=disabled;
    this.save.disabled=disabled||!this.task||this.stale||!!this.pending;this.cancel.disabled=!!this.saving;this.reload.hidden=!this.stale;this.reload.disabled=disabled;this.check.hidden=!this.pending;this.check.disabled=disabled;
    this.form.setAttribute('aria-busy',String(!!this.busy));
  }
  failure(error){this.error.textContent=error.message;this.error.hidden=false;}
  async open(){
    const context=this.current(),selection=this.selection,f=this.field;if(!context?.writable||f.readOnly||!selection||this.dialog.open)return;
    let offset,selected;const movedAtClick=f.bodyBomMoved;try{offset=selection.offset();selected=splitMarkdown(normalize(f.value()));}catch{return;}
    this.openContext=context;const cycle=++this.generation;this.abort=new AbortController();this.busy=true;this.task=null;this.stale=false;this.pending=null;this.inputs.replaceChildren();this.error.hidden=true;this.title.textContent=t('markdown.loading');this.dialog.showModal();this.paintDialog();this.action.hidden=true;
    try{
      let reply;for(let attempt=0;;attempt++)try{
      const source=await f.resources.readTaskSource({signal:this.abort.signal});if(!this.active(context,cycle))return;
      const saved=splitMarkdown(normalize(source.text)),moved=!selected.frontmatter&&selected.body.startsWith('\uFEFF')&&saved.frontmatter.startsWith('\uFEFF')&&saved.body===selected.body.slice(1);
      if(saved.body!==selected.body&&!moved||source.entry.sourceType!==context.sourceType||source.entry.sourceKey!==context.sourceKey)throw new Error(t('notes.stale'));
      const bodyOffset=offset-(movedAtClick||moved?1:0);reply=await context.session.request('/notes/tasks/resolve',{method:'POST',etag:source.etag,body:{sourceType:context.sourceType,sourceKey:context.sourceKey,offset:saved.frontmatter.length+bodyOffset},signal:this.abort.signal});break;
      }catch(error){if(attempt===2||error.status!==412||!this.active(context,cycle))throw error;}
      const profile=await context.session.request('/me',{signal:this.abort.signal});if(!this.active(context,cycle))return;this.zone=profile.data.zoneId;this.accept(reply);
    }catch(error){if(this.active(context,cycle)&&error.name!=='AbortError')this.failure(error);}
    finally{if(this.active(context,cycle)){this.busy=false;this.paintDialog();this.inputs.querySelector('select,input,textarea')?.focus();}}
  }
  accept(reply){
    this.task=reply.data;this.etag=reply.etag;this.stale=false;this.pending=null;this.inputs.replaceChildren();this.error.hidden=true;this.title.textContent=this.task.summary;
    const field=(label,name,value,{choices,type='text',multiline=false,max=65536}={})=>{
      const wrap=node('label',undefined,'form-label'),caption=node('span',label),input=node(choices?'select':multiline?'textarea':'input',undefined,choices?'form-select':'form-control');caption.id='task-field-'+crypto.randomUUID();input.setAttribute('aria-labelledby',caption.id);input.name=name;if(!choices&&!multiline)input.type=type;if(choices)for(const [key,text]of choices)input.append(new Option(text,key));input.value=value;input.maxLength=max;if(multiline)input.rows=3;wrap.append(caption,input);this.inputs.append(wrap);return input;
    };
    const directory=noteClientDirectory(this.openContext.session)||{clients:[],projects:[]},value=this.task;
    this.client=field(t('ui.client'),'clientId',value.clientId||directory.projects.find(item=>item.id===value.projectId)?.clientId||'',{choices:[['',t('ui.unassigned')],...directory.clients.map(item=>[item.id,item.name])]});
    this.project=field(t('ui.project'),'projectId','',{choices:[]});
    const projects=selected=>{this.project.replaceChildren(new Option(t('ui.no_project'),''));for(const item of directory.projects.filter(item=>!this.client.value||item.clientId===this.client.value))this.project.append(new Option(item.name,item.id));this.project.value=selected;if(this.project.selectedIndex<0)this.project.value='';};projects(value.projectId||'');
    this.client.addEventListener('change',()=>projects(this.project.value));this.project.addEventListener('change',()=>{const selected=directory.projects.find(item=>item.id===this.project.value);if(selected)this.client.value=selected.clientId;});
    this.scheduled=field(t('tasks.scheduled_day'),'scheduledDate',value.scheduledDate||'',{type:'date'});this.due=taskMomentFields(field,'due',value.due,this.zone);
    this.priority=field(t('tasks.priority'),'priority',value.priority||'UNSPECIFIED',{choices:['UNSPECIFIED','LOW','NORMAL','HIGH'].map(key=>[key,t('tasks.priority_'+key.toLowerCase())])});
    this.notes=field(t('tasks.notes'),'bodyContent',value.body?.content||'',{multiline:true});
  }
  values(){const value=this.task,projectId=this.project.value||null,clientId=projectId?null:this.client.value||null,changed=projectId!==(value.projectId||null)||!projectId&&clientId!==(value.clientId||null);return {summary:value.summary,url:value.url,clientId,projectId,parentTaskId:changed?null:value.parentTaskId,scheduledDate:this.scheduled.value||null,due:this.due(),priority:this.priority.value,body:{format:value.body?.format||'TEXT',content:this.notes.value}};}
  async commit(){
    const context=this.current(),cycle=this.generation;if(!same(context,this.openContext)||!context.writable||this.field.readOnly||this.busy||this.stale||this.pending||!this.task||!this.form.reportValidity())return;
    const body=this.values();this.busy=this.saving=true;this.error.hidden=true;this.paintDialog();
    try{await context.session.request('/tasks/'+this.task.id,{method:'PUT',etag:this.etag,body,signal:this.abort.signal});if(this.active(context,cycle))this.saved();}
    catch(error){if(this.active(context,cycle)&&error.name!=='AbortError'){if(error.status===412)this.stale=true;else if(!error.status||error.status>=500)this.pending=body;this.failure(error);}}
    finally{if(this.active(context,cycle)){this.busy=this.saving=false;this.paintDialog();}}
  }
  saved(){this.saving=false;this.dialog.close();document.dispatchEvent(new CustomEvent('timekeeper:notes-changed'));}
  async loadTask(){
    const context=this.current(),cycle=this.generation;if(!same(context,this.openContext)||this.busy||!this.task)return;this.busy=true;this.paintDialog();
    try{const reply=await context.session.request('/tasks/'+this.task.id,{signal:this.abort.signal});if(this.active(context,cycle))this.accept(reply);}
    catch(error){if(this.active(context,cycle)&&error.name!=='AbortError')this.failure(error);}
    finally{if(this.active(context,cycle)){this.busy=false;this.paintDialog();}}
  }
  async checkSaved(){
    const context=this.current(),cycle=this.generation;if(!same(context,this.openContext)||this.busy||!this.pending)return;this.busy=true;this.paintDialog();
    try{const reply=await context.session.request('/tasks/'+this.task.id,{signal:this.abort.signal});if(!this.active(context,cycle))return;
      if(Object.entries(this.pending).every(([key,value])=>equivalent(value,reply.data[key]??null))){this.saved();return;}
      this.pending=null;this.stale=reply.etag!==this.etag;this.failure(new Error(t(this.stale?'notes.task_stale':'notes.task_not_saved')));
    }catch(error){if(this.active(context,cycle)&&error.name!=='AbortError')this.failure(error);}
    finally{if(this.active(context,cycle)){this.busy=false;this.paintDialog();}}
  }
}

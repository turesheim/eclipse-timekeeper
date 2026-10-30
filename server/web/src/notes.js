import {t} from './i18n.js';
import {MarkdownField} from './markdown-field.js';
import {icon,iconButton} from './icons.js';
import {relativeNotePath,rasterType} from './notes-links.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const action=(label,run,name)=>{const value=node('button',name?'':label,'btn btn-outline-secondary');value.type='button';if(name)iconButton(value,name,label);value.addEventListener('click',run);return value;};
const textDecoder=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true});

/** In-memory drafts only. A session generation fences every asynchronous response. */
export class NotesView {
  constructor(root,callbacks){
    Object.assign(this,{root,callbacks,generation:0,entries:new Map(),children:new Map(),expanded:new Set(),writable:false});
    const heading=node('div',undefined,'notes-heading');this.title=node('h2',t('notes.title'));this.title.id='notes-title';
    this.toggle=action(t('notes.navigation'),()=>{this.root.classList.toggle('notes-drawer-open');this.toggle.setAttribute('aria-expanded',String(this.root.classList.contains('notes-drawer-open')));},'folder');this.toggle.classList.add('notes-drawer-toggle');this.toggle.setAttribute('aria-controls','notes-navigation');this.toggle.setAttribute('aria-expanded','false');this.settingsButton=action(t('notes.settings'),()=>void this.openSettings(),'gear');const headingActions=node('div',undefined,'notes-file-tools');headingActions.append(this.settingsButton,this.toggle);heading.append(this.title,headingActions);
    this.error=node('p','','alert alert-danger');this.error.hidden=true;this.error.setAttribute('role','alert');
    this.layout=node('div',undefined,'notes-layout');this.navigation=node('aside',undefined,'notes-navigation card');this.navigation.id='notes-navigation';this.navigation.setAttribute('aria-label',t('notes.navigation'));
    const tools=node('div',undefined,'notes-navigation-tools');
    this.newNote=action(t('notes.new_note'),()=>this.pathDialog('note'),'file-earmark-plus');this.newFolder=action(t('notes.new_folder'),()=>this.pathDialog('folder'),'folder-plus');this.refresh=action(t('notes.refresh'),()=>void this.reload(),'refresh');
    this.trash=action(t('notes.trash'),()=>{this.showTrash=!this.showTrash;this.trash.setAttribute('aria-pressed',String(this.showTrash));void this.reload();},'delete');this.trash.setAttribute('aria-pressed','false');tools.append(this.newNote,this.newFolder,this.refresh,this.trash);
    const searchLabel=node('label',t('notes.search'),'form-label');this.search=node('input',undefined,'form-control');this.search.type='search';this.search.id='notes-search';searchLabel.htmlFor=this.search.id;
    this.search.addEventListener('input',()=>{clearTimeout(this.searchTimer);this.searchTimer=setTimeout(()=>void this.reload(),250);});
    this.tree=node('div',undefined,'notes-tree');this.tree.setAttribute('role','tree');this.tree.setAttribute('aria-label',t('notes.navigation'));this.tree.addEventListener('keydown',event=>this.treeKey(event));
    this.navigation.append(tools,searchLabel,this.search,this.tree);
    this.resize=node('div',undefined,'notes-resize');this.resize.tabIndex=0;this.resize.setAttribute('role','separator');this.resize.setAttribute('aria-orientation','vertical');this.resize.setAttribute('aria-label',t('notes.resize'));this.resize.setAttribute('aria-valuemin','180');this.resize.setAttribute('aria-valuemax','480');this.resize.setAttribute('aria-valuenow','260');
    const width=value=>{this.navWidth=Math.max(180,Math.min(480,value));this.root.style.setProperty('--notes-nav-width',this.navWidth+'px');this.resize.setAttribute('aria-valuenow',String(Math.round(this.navWidth)));};
    this.resize.addEventListener('pointerdown',event=>{event.preventDefault();this.resize.setPointerCapture(event.pointerId);});
    this.resize.addEventListener('pointermove',event=>{if(this.resize.hasPointerCapture(event.pointerId))width(event.clientX-this.layout.getBoundingClientRect().left);});
    this.resize.addEventListener('pointerup',event=>this.resize.releasePointerCapture(event.pointerId));
    this.resize.addEventListener('keydown',event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();width(event.key==='Home'?180:event.key==='End'?480:(this.navWidth||260)+(event.key==='ArrowLeft'?-20:20));}});
    this.editor=node('section',undefined,'notes-editor card');this.editor.setAttribute('aria-labelledby','notes-document-title');this.documentTitle=node('h3',t('notes.select'));this.documentTitle.id='notes-document-title';
    const fileTools=node('div',undefined,'notes-file-tools');this.rename=action(t('notes.move'),()=>this.pathDialog('move'),'edit');this.remove=action(t('notes.delete'),()=>void this.delete(),'delete');this.recover=action(t('notes.restore'),()=>void this.restore(),'arrow-counterclockwise');this.reloadNote=action(t('notes.reload'),()=>{if(this.canLeave())void this.select(this.note?.id,true);},'refresh');this.download=action(t('notes.download_attachment'),()=>void this.downloadAttachment(),'download');fileTools.append(this.rename,this.remove,this.recover,this.reloadNote,this.download);
    const resources={allowsAsset:reference=>!!this.linkPath(reference),allowsLink:reference=>!!this.linkPath(reference),image:(reference,alt)=>this.image(reference,alt),openLink:reference=>void this.openLink(reference)};
    const host=node('div',undefined,'notes-markdown');this.markdown=new MarkdownField(host,()=>this.controls(),()=>{},'notes',(file,image)=>this.upload(file,image),resources);
    this.empty=node('p',t('notes.select'),'hint');this.status=node('p','','hint');this.status.setAttribute('role','status');
    this.save=action(t('ui.save'),()=>void this.commit());this.save.className='btn btn-primary';const footer=node('div',undefined,'notes-footer');footer.append(this.status,this.save);
    this.editor.append(this.documentTitle,fileTools,this.empty,host,footer);this.layout.append(this.navigation,this.resize,this.editor);root.append(heading,this.error,this.layout);
    this.dialog=node('dialog',undefined,'app-dialog');this.dialogTitle=node('h2');this.dialogTitle.id='notes-path-title';this.dialog.setAttribute('aria-labelledby',this.dialogTitle.id);
    this.form=node('form');const pathLabel=node('label',t('notes.path'),'form-label');this.pathInput=node('input',undefined,'form-control');this.pathInput.id='notes-path';this.pathInput.required=true;this.pathInput.maxLength=1024;pathLabel.htmlFor=this.pathInput.id;
    this.dialogError=node('p','','alert alert-danger');this.dialogError.hidden=true;this.dialogError.setAttribute('role','alert');
    const buttons=node('div',undefined,'dialog-actions');this.cancel=action(t('ui.cancel'),()=>{if(!this.busy)this.dialog.close();});this.apply=node('button',t('ui.save'),'btn btn-primary');this.apply.type='submit';buttons.append(this.cancel,this.apply);this.form.append(pathLabel,this.pathInput,this.dialogError,buttons);this.dialog.append(this.dialogTitle,this.form);root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{if(this.busy)event.preventDefault();});this.form.addEventListener('submit',event=>{event.preventDefault();void this.applyPath();});
    this.settingsDialog=node('dialog',undefined,'app-dialog');this.settingsDialog.setAttribute('aria-labelledby','notes-settings-title');const settingsTitle=node('h2',t('notes.settings'));settingsTitle.id='notes-settings-title';this.settingsForm=node('form');this.settingsFields={};
    this.settingsForm.append(node('p',t('notes.settings_help'),'hint'));
    for(const name of ['journalFolder','eventsFolder','assetsFolder']){const label=node('label',t('notes.'+name),'form-label'),input=node('input',undefined,'form-control');input.id='notes-setting-'+name;input.required=true;input.maxLength=1024;label.htmlFor=input.id;this.settingsFields[name]=input;this.settingsForm.append(label,input);}
    const configLabel=node('label',undefined,'form-check-label');this.configCheckbox=node('input',undefined,'form-check-input');this.configCheckbox.type='checkbox';configLabel.append(this.configCheckbox,document.createTextNode(' '+t('notes.config_opt_in')));const configField=node('div',undefined,'form-check');configField.append(configLabel);this.settingsForm.append(configField,node('p',t('notes.config_help'),'hint'));
    this.settingsError=node('p','','alert alert-danger');this.settingsError.setAttribute('role','alert');this.settingsError.hidden=true;this.settingsForm.append(this.settingsError);
    const settingsActions=node('div',undefined,'dialog-actions');this.settingsCancel=action(t('ui.cancel'),()=>{if(!this.busy)this.settingsDialog.close();});this.settingsApply=node('button',t('ui.save'),'btn btn-primary');this.settingsApply.type='submit';settingsActions.append(this.settingsCancel,this.settingsApply);this.settingsForm.append(settingsActions);this.settingsDialog.append(settingsTitle,this.settingsForm);root.append(this.settingsDialog);
    this.settingsDialog.addEventListener('cancel',event=>{if(this.busy)event.preventDefault();});this.settingsForm.addEventListener('submit',event=>{event.preventDefault();void this.saveSettings();});
    window.addEventListener('beforeunload',event=>{if(this.dirty()){event.preventDefault();event.returnValue='';}});
    this.observer=new ResizeObserver(()=>this.fit());this.observer.observe(document.documentElement);window.addEventListener('resize',()=>this.fit());this.controls();
  }
  dirty(){return !!this.note&&!this.binary&&this.markdown.value()!==this.original;}
  canLeave(){return !this.busy&&(!this.dirty()||confirm(t('notes.discard')));}
  update(snapshot,connected,session,writable){
    if(this.session&&this.session!==session)this.reset();this.session=session;this.writable=connected&&writable;this.controls();
    if(this.visible&&!this.busy&&!this.loading)void this.reload();
  }
  show(){this.visible=true;this.fit();if(this.session)void this.reload();}
  hide(){this.visible=false;}
  reset(){++this.generation;++this.loadGeneration;this.session=null;this.info=null;this.note=null;this.original='';this.binary=false;this.stale=false;this.busy=false;this.loading=false;this.entries.clear();this.children.clear();this.expanded.clear();this.tree.replaceChildren();this.markdown.clear();this.dialog.close();this.settingsDialog.close();this.error.hidden=true;this.search.value='';this.status.textContent='';this.documentTitle.textContent=t('notes.select');this.controls();}
  fit(){if(!this.visible)return;const bar=document.getElementById('workspace-navigation');const bottom=bar?.getBoundingClientRect().top||innerHeight;this.layout.style.height=Math.max(280,bottom-this.layout.getBoundingClientRect().top-8)+'px';}
  controls(){
    const write=!!this.session&&this.writable&&!this.busy&&!this.stale;this.markdown.setReadOnly(!write||!this.note||this.binary||this.note.deleted);
    this.newNote.disabled=this.newFolder.disabled=!write||!!this.showTrash;this.save.disabled=!write||!this.dirty()||this.markdown.loading;this.save.hidden=!this.note||this.binary||this.note.deleted;
    this.rename.disabled=this.remove.disabled=!write||!this.note||this.note.deleted;this.recover.hidden=!this.note?.deleted;this.recover.disabled=!write;
    this.reloadNote.disabled=!this.note||this.busy;this.apply.disabled=this.cancel.disabled=!!this.busy;
    this.download.hidden=!this.note||this.note.kind!=='BINARY'&&this.note.kind!=='ASSET'||this.note.deleted;this.download.disabled=!!this.busy;
    this.settingsButton.disabled=!this.session||this.busy;this.settingsApply.disabled=!this.writable||this.busy;this.settingsCancel.disabled=!!this.busy;
    for(const input of [...Object.values(this.settingsFields),this.configCheckbox])input.disabled=!this.writable||this.busy;
    this.empty.hidden=!!this.note&&!this.binary&&!this.note.deleted;this.markdown.root.hidden=!this.note||this.binary||this.note.deleted;
    if(this.note&&!this.busy)this.status.textContent=this.stale?t('notes.stale'):this.dirty()?t('journal.unsaved'):t('journal.saved');
  }
  fail(error){this.error.hidden=false;this.error.textContent=error.uncertain?t('notes.uncertain'):error.message;if(error.status===401)this.callbacks.onUnauthorized?.(error);}
  async list(parameters,session){
    const rows=[];let offset=0,snapshot;
    do{const query=new URLSearchParams({...parameters,offset,limit:this.info.limits.pageSize});const reply=await session.request('/notes/entries?'+query,{etag:snapshot});snapshot=reply.data.snapshot;rows.push(...reply.data.items);offset=reply.data.nextOffset;}while(offset!==undefined);
    return rows;
  }
  async reload(){
    if(!this.session)return;const session=this.session,current=(this.loadGeneration||0)+1;this.loadGeneration=current;this.loading=true;
    try{
      if(!this.info){const reply=await session.request('/notes');if(session!==this.session||current!==this.loadGeneration)return;this.info=reply.data;}
      const search=this.search.value.trim();const children=new Map(),entries=new Map();
      if(search||this.showTrash){const rows=await this.list({query:search,...(this.showTrash?{trash:true}:{})},session);children.set('search',rows);for(const row of rows)entries.set(row.id,row);}
      else for(const parent of [this.info.rootId,...this.expanded]){const rows=await this.list({parentId:parent},session);children.set(parent,rows);for(const row of rows)entries.set(row.id,row);}
      if(session!==this.session||current!==this.loadGeneration)return;this.children=children;this.entries=entries;this.paint();this.error.hidden=true;
      if(this.note){const reply=await session.request('/notes/entries/'+this.note.id);if(session!==this.session||current!==this.loadGeneration)return;if(reply.etag!==this.note.etag)this.stale=true;else{this.note={...reply.data,etag:reply.etag};this.documentTitle.textContent=this.note.path;}}
    }catch(error){if(session===this.session&&current===this.loadGeneration)this.fail(error);}
    finally{if(session===this.session&&current===this.loadGeneration){this.loading=false;this.controls();}}
  }
  paint(){
    const focused=this.tree.querySelector(':focus')?.dataset.id;this.tree.replaceChildren();
    const append=(parent,host)=>{for(const entry of this.children.get(parent)||[]){
      const folder=entry.kind==='FOLDER',row=node('div',undefined,'notes-tree-row');row.setAttribute('role','treeitem');row.setAttribute('aria-selected',String(this.note?.id===entry.id));row.dataset.id=entry.id;row.tabIndex=-1;
      const pick=action(entry.path,()=>{if(folder&&!this.showTrash){this.folder=entry;this.expanded.has(entry.id)?this.expanded.delete(entry.id):this.expanded.add(entry.id);void this.reload();}void this.select(entry.id);});pick.className='notes-tree-pick';pick.tabIndex=-1;pick.title=entry.path;pick.textContent='';pick.setAttribute('aria-label',entry.path);pick.append(icon(folder?'folder':'file-earmark-text'),node('span',parent==='search'?entry.path:entry.path.split('/').at(-1)));row.append(pick);
      row.addEventListener('focus',()=>{for(const item of this.tree.querySelectorAll('[role=treeitem]'))item.tabIndex=item===row?0:-1;});
      if(folder&&!this.showTrash){row.setAttribute('aria-expanded',String(this.expanded.has(entry.id)));if(this.expanded.has(entry.id)){const group=node('div');group.setAttribute('role','group');append(entry.id,group);row.append(group);}}host.append(row);
    }};append(this.children.has('search')?'search':this.info.rootId,this.tree);
    if(!this.tree.children.length)this.tree.append(node('p',t('notes.empty'),'hint'));
    const target=[...this.tree.querySelectorAll('[role=treeitem]')].find(row=>row.dataset.id===focused)||this.tree.querySelector('[role=treeitem]');if(target){target.tabIndex=0;if(focused)target.focus();}
  }
  treeKey(event){
    const row=event.target.closest('[role=treeitem]');if(!row)return;const rows=[...this.tree.querySelectorAll('[role=treeitem]')],index=rows.indexOf(row),entry=this.entries.get(row.dataset.id);
    if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();rows[event.key==='Home'?0:event.key==='End'?rows.length-1:Math.max(0,Math.min(rows.length-1,index+(event.key==='ArrowDown'?1:-1)))].focus();}
    if(event.key==='ArrowRight'&&entry.kind==='FOLDER'&&!this.expanded.has(entry.id)){event.preventDefault();this.expanded.add(entry.id);void this.reload();}
    if(event.key==='ArrowLeft'){event.preventDefault();if(this.expanded.delete(entry.id))void this.reload();else row.parentElement.closest('[role=treeitem]')?.focus();}
    if(['Enter',' '].includes(event.key)){event.preventDefault();row.querySelector('button').click();}
  }
  async select(id,force=false){
    if(!id||this.busy||!force&&this.note?.id===id||!force&&!this.canLeave())return;
    const session=this.session,current=++this.generation;this.note=null;this.original='';this.binary=false;this.stale=false;this.markdown.clear();this.controls();
    try{
      const reply=await session.request('/notes/entries/'+id);if(session!==this.session||current!==this.generation)return;
      const metadata={...reply.data,etag:reply.etag};let text='';
      if(metadata.kind!=='FOLDER'&&!metadata.deleted){const content=await session.request('/notes/entries/'+id+'/content',{binary:'application/octet-stream'});if(content.etag!==metadata.etag)throw new Error(t('notes.stale'));if(session!==this.session||current!==this.generation)return;
        if(['MARKDOWN','DAY','WEEK','EVENT'].includes(metadata.kind))text=textDecoder.decode(await content.data.arrayBuffer());else this.binary=true;}
      if(session!==this.session||current!==this.generation)return;this.note=metadata;this.original=text;this.documentTitle.textContent=metadata.path;this.markdown.setValue(text);this.error.hidden=true;
      this.empty.textContent=metadata.deleted?t('notes.deleted'):this.binary?t('notes.binary'):t('notes.select');this.binary||=metadata.kind==='FOLDER';this.root.classList.remove('notes-drawer-open');this.toggle.setAttribute('aria-expanded','false');this.controls();this.paint();
    }catch(error){if(session===this.session&&current===this.generation)this.fail(error);}
  }
  async commit(){
    if(this.save.disabled)return;const session=this.session,current=this.generation,body=this.markdown.value();this.busy=true;this.controls();this.status.textContent=t('journal.saving');
    try{const reply=await session.request('/notes/entries/'+this.note.id+'/content',{method:'PUT',etag:this.note.etag,rawBody:new TextEncoder().encode(body)});if(session!==this.session||current!==this.generation)return;
      this.note={...reply.data,etag:reply.etag};this.original=body;this.error.hidden=true;this.callbacks.onChanged?.();
    }catch(error){if(session===this.session&&current===this.generation){this.stale=true;this.fail(error);}}
    finally{if(session===this.session&&current===this.generation){this.busy=false;this.controls();}}
  }
  pathDialog(mode){
    if(!this.writable||this.busy||!this.canLeave())return;this.pathMode=mode;this.dialogTitle.textContent=t(mode==='move'?'notes.move':mode==='folder'?'notes.new_folder':'notes.new_note');this.dialogError.hidden=true;
    this.pathInput.value=mode==='move'?this.note.path:(this.folder?.path?this.folder.path+'/':'')+(mode==='note'?'Untitled.md':'');this.dialog.showModal();this.pathInput.focus();this.pathInput.select();
  }
  async applyPath(){
    if(this.busy)return;const session=this.session,current=this.generation,mode=this.pathMode,path=this.pathInput.value;this.busy=true;this.controls();
    try{let reply;if(mode==='move')reply=await session.request('/notes/entries/'+this.note.id+'/path',{method:'PUT',etag:this.note.etag,body:{path}});
      else if(mode==='folder')reply=await session.request('/notes/folders',{method:'POST',body:{path}});
      else reply=await session.request('/notes/files?'+new URLSearchParams({path}),{method:'PUT',noneMatch:'*',rawBody:new Uint8Array()});
      if(session!==this.session||current!==this.generation)return;this.dialog.close();this.busy=false;this.info=null;await this.reload();await this.select(reply.data.id,true);this.callbacks.onChanged?.();
    }catch(error){if(session===this.session&&current===this.generation){this.dialogError.textContent=error.message;this.dialogError.hidden=false;}}
    finally{if(session===this.session&&current===this.generation){this.busy=false;this.controls();}}
  }
  async delete(){
    if(this.remove.disabled||!this.canLeave()||!confirm(t('notes.delete_confirm',{path:this.note.path})))return;
    const session=this.session,current=this.generation;this.busy=true;this.controls();
    try{await session.request('/notes/entries/'+this.note.id,{method:'DELETE',etag:this.note.etag});if(session!==this.session||current!==this.generation)return;this.note=null;this.markdown.clear();this.original='';this.busy=false;await this.reload();this.callbacks.onChanged?.();}
    catch(error){if(session===this.session&&current===this.generation){this.stale=true;this.fail(error);}}
    finally{if(session===this.session&&current===this.generation){this.busy=false;this.controls();}}
  }
  async restore(){
    if(this.recover.disabled)return;const session=this.session,current=this.generation;this.busy=true;this.controls();
    try{const reply=await session.request('/notes/entries/'+this.note.id+'/restore',{method:'POST',etag:this.note.etag});if(session!==this.session||current!==this.generation)return;this.busy=false;this.showTrash=false;this.trash.setAttribute('aria-pressed','false');await this.reload();await this.select(reply.data.id,true);this.callbacks.onChanged?.();}
    catch(error){if(session===this.session&&current===this.generation){this.stale=true;this.fail(error);}}
    finally{if(session===this.session&&current===this.generation){this.busy=false;this.controls();}}
  }
  async upload(file,image){
    if(!this.note||!this.writable||this.stale||this.busy)throw new Error();
    const session=this.session,current=this.generation,parent=this.note.path.split('/').slice(0,-1).join('/'),name=file.name,path=(parent?parent+'/':'')+name;
    if(['DAY','WEEK','EVENT'].includes(this.note.kind)){
      if(file.size>5_000_000||image&&!['image/png','image/jpeg','image/gif','image/webp'].includes(file.type))throw new Error();
      const suffix=(name.match(/\.([a-zA-Z0-9]{1,10})$/)?.[1]||'bin').toLowerCase(),filename=crypto.randomUUID()+'.'+suffix,bytes=new Uint8Array(await file.arrayBuffer());let binary='';
      for(let offset=0;offset<bytes.length;offset+=8192)binary+=String.fromCharCode(...bytes.subarray(offset,offset+8192));
      if(session!==this.session||current!==this.generation)throw new Error();
      await session.request('/journal-assets/'+filename,{method:'PUT',noneMatch:'*',body:{data:btoa(binary)},emptyResponse:true,timeout:60000});
      if(session!==this.session||current!==this.generation)throw new Error();this.callbacks.onChanged?.();return 'assets/'+filename;
    }
    const reply=await session.request('/notes/files?'+new URLSearchParams({path}),{method:'PUT',noneMatch:'*',rawBody:file});
    if(session!==this.session||current!==this.generation)throw new Error();this.callbacks.onChanged?.();return encodeURIComponent(name);
  }
  linkPath(reference){return this.note?relativeNotePath(this.note.path,reference,{kind:this.note.kind,assetsFolder:this.info?.settings.assetsFolder}):null;}
  async resolveLink(reference,session=this.session){const path=this.linkPath(reference);if(!path)throw new Error(t('notes.invalid_link'));return (await session.request('/notes/resolve?'+new URLSearchParams({path}))).data;}
  image(reference,alt){
    const dom=node('img');dom.alt=alt;dom.loading='lazy';let disposed=false,url;const session=this.session,current=this.generation;
    void (async()=>{try{const entry=await this.resolveLink(reference,session);const reply=await session.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});
      const type=rasterType(new Uint8Array(await reply.data.slice(0,12).arrayBuffer()));if(!type||disposed||session!==this.session||current!==this.generation)return;
      url=URL.createObjectURL(reply.data.slice(0,reply.data.size,type));dom.src=url;
    }catch{if(!disposed)dom.alt=alt||t('notes.unavailable_image');}})();
    return {dom,destroy(){disposed=true;if(url)URL.revokeObjectURL(url);dom.removeAttribute('src');}};
  }
  async openLink(reference){const session=this.session,current=this.generation;try{const entry=await this.resolveLink(reference,session);if(session===this.session&&current===this.generation)await this.select(entry.id);}catch(error){if(session===this.session&&current===this.generation)this.fail(error);}}
  async downloadAttachment(){
    if(!this.note||this.download.disabled)return;const session=this.session,current=this.generation,note=this.note;
    try{const reply=await session.request('/notes/entries/'+note.id+'/content',{binary:'application/octet-stream'});if(session!==this.session||current!==this.generation)return;
      const url=URL.createObjectURL(reply.data),link=node('a');link.href=url;link.download=note.path.split('/').at(-1);link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    }catch(error){if(session===this.session&&current===this.generation)this.fail(error);}
  }
  async openSettings(){
    if(!this.session||this.busy)return;const session=this.session,current=this.generation;this.settingsError.hidden=true;
    try{const reply=await session.request('/notes/settings');if(session!==this.session||current!==this.generation)return;this.settingsEtag=reply.etag;this.originalSettings=reply.data;for(const [name,input]of Object.entries(this.settingsFields))input.value=reply.data[name];this.configCheckbox.checked=reply.data.syncObsidianConfig;this.controls();this.settingsDialog.showModal();}
    catch(error){if(session===this.session&&current===this.generation)this.fail(error);}
  }
  async saveSettings(){
    if(!this.writable||this.busy)return;const session=this.session,current=this.generation;this.busy=true;this.controls();
    const body=Object.fromEntries(Object.entries(this.settingsFields).filter(([name,input])=>input.value!==this.originalSettings[name]).map(([name,input])=>[name,input.value]));if(this.configCheckbox.checked!==this.originalSettings.syncObsidianConfig)body.syncObsidianConfig=this.configCheckbox.checked;
    try{await session.request('/notes/settings',{method:'PUT',etag:this.settingsEtag,body});if(session!==this.session||current!==this.generation)return;this.info=null;this.folder=null;this.settingsDialog.close();this.busy=false;await this.reload();this.callbacks.onChanged?.();}
    catch(error){if(session===this.session&&current===this.generation){this.settingsError.hidden=false;this.settingsError.textContent=error.uncertain?t('notes.uncertain'):error.message;if(error.status===401)this.callbacks.onUnauthorized?.(error);}}
    finally{if(session===this.session&&current===this.generation){this.busy=false;this.controls();}}
  }
}

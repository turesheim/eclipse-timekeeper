import {DocumentAutosave,bindDocumentAutosave} from './document-autosave.js';
import {noteRevisionResources} from './note-revisions.js';
import {t,getLocale} from './i18n.js';
import {noteSortOrders,sortedNotes} from './notes-sort.js';
import {MarkdownField} from './markdown-field.js';
import {icon,iconButton} from './icons.js';
import {relativeNotePath,rasterType,notesEntryVisible} from './notes-links.js';
import {vaultEditorResources} from './vault-editor-resources.js';
import {imagePreview} from './image-preview.js';
import {noteTitle} from './note-title.js';
import {dateTimeFormat} from './formatters.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const action=(label,run,name)=>{const value=node('button',name?'':label,'btn btn-outline-secondary');value.type='button';if(name)iconButton(value,name,label);value.addEventListener('click',run);return value;};
const textDecoder=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true});

/** In-memory drafts only. A session generation fences every asynchronous response. */
export class NotesView {
  constructor(root,callbacks){
    Object.assign(this,{root,callbacks,generation:0,positions:new Map(),entries:new Map(),children:new Map(),expanded:new Set(),writable:false,sortOrder:'name-asc',expandAllRequested:false});
    this.narrow=matchMedia('(max-width:760px)');
    const heading=node('div',undefined,'notes-heading'),navigationHeading=node('div',undefined,'notes-navigation-heading');this.title=node('h2',t('notes.title'));this.title.id='notes-title';
    this.toggle=action(t('notes.navigation'),()=>{
      if(this.narrow.matches)this.root.classList.toggle('notes-drawer-open');else this.navCollapsed=!this.navCollapsed;
      this.navigationState();
    },'layout-sidebar');this.toggle.classList.add('notes-drawer-toggle');this.toggle.setAttribute('aria-controls','notes-navigation');
    this.documentTitle=node('h2',t('notes.select'),'notes-document-title');this.documentTitle.id='notes-document-title';
    navigationHeading.append(this.toggle,this.title);heading.append(navigationHeading,this.documentTitle);
    this.error=node('p','','alert alert-danger');this.error.hidden=true;this.error.setAttribute('role','alert');
    this.layout=node('div',undefined,'notes-layout');this.navigation=node('aside',undefined,'notes-navigation card');this.navigation.id='notes-navigation';this.navigation.setAttribute('aria-label',t('notes.navigation'));
    const tools=node('div',undefined,'btn-toolbar notes-navigation-tools');tools.setAttribute('role','toolbar');tools.setAttribute('aria-label',t('notes.navigation_actions'));
    this.newNote=action(t('notes.new_note'),()=>this.pathDialog('note'),'file-earmark-plus');this.newFolder=action(t('notes.new_folder'),()=>this.pathDialog('folder'),'folder-plus');this.refresh=action(t('notes.refresh'),()=>void this.reload(),'refresh');
    this.trash=action(t('notes.trash'),()=>{this.showTrash=!this.showTrash;this.trash.setAttribute('aria-pressed',String(this.showTrash));this.trash.classList.toggle('active',this.showTrash);void this.reload();},'trash');this.trash.setAttribute('aria-pressed','false');
    this.sortButton=action(t('notes.sort'),event=>this.showSortMenu(event.detail===0),'sort-alpha-down');
    this.sortButton.setAttribute('aria-haspopup','menu');this.sortButton.setAttribute('aria-expanded','false');this.sortButton.setAttribute('aria-controls','notes-sort-menu');
    this.sortMenu=node('div',undefined,'dropdown-menu notes-sort-menu shadow');this.sortMenu.id='notes-sort-menu';this.sortMenu.popover='auto';this.sortMenu.setAttribute('role','menu');this.sortMenu.setAttribute('aria-label',t('notes.sort'));
    for(const order of noteSortOrders){
      const choice=action(t(order.label),()=>{this.sortOrder=order.value;this.closeSortMenu();this.sortState();this.paint();});choice.className='dropdown-item';choice.dataset.sort=order.value;choice.tabIndex=-1;choice.setAttribute('role','menuitemradio');this.sortMenu.append(choice);
    }
    this.sortMenu.addEventListener('beforetoggle',event=>this.sortButton.setAttribute('aria-expanded',String(event.newState==='open')));
    this.sortButton.addEventListener('keydown',event=>{if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();if(this.sortMenu.matches(':popover-open'))(event.key==='ArrowUp'?this.sortMenu.lastElementChild:this.sortMenu.querySelector('[aria-checked=true]')).focus();else this.showSortMenu(true,event.key==='ArrowUp');}});
    this.sortMenu.addEventListener('keydown',event=>{
      const choices=[...this.sortMenu.children],index=choices.indexOf(document.activeElement);
      if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();choices[event.key==='Home'?0:event.key==='End'?choices.length-1:(index+(event.key==='ArrowDown'?1:-1)+choices.length)%choices.length].focus();}
      else if(event.key==='Escape'||event.key==='Tab'){if(event.key==='Escape'){event.preventDefault();event.stopPropagation();}this.closeSortMenu();this.sortButton.focus();}
    });
    this.collapseAll=action(t('notes.collapse_all'),()=>{this.expandAllRequested=false;this.expanded.clear();this.paint();void this.reload();},'arrows-collapse');
    this.expandAll=action(t('notes.expand_all'),()=>{this.expandAllRequested=true;void this.reload();},'arrows-expand');
    for(const [label,buttons]of [['notes.create_actions',[this.newNote,this.newFolder]],['notes.organize_actions',[this.sortButton,this.collapseAll,this.expandAll]],['notes.browse_actions',[this.refresh,this.trash]]]){
      const group=node('div',undefined,'btn-group');group.setAttribute('role','group');group.setAttribute('aria-label',t(label));group.append(...buttons);tools.append(group);
    }
    tools.addEventListener('keydown',event=>{
      if(event.ctrlKey||event.altKey||event.metaKey||!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
      const buttons=[...tools.querySelectorAll('button:not(:disabled)')],index=buttons.indexOf(event.target.closest('button'));if(index<0)return;
      event.preventDefault();buttons[event.key==='Home'?0:event.key==='End'?buttons.length-1:(index+(event.key==='ArrowRight'?1:-1)+buttons.length)%buttons.length].focus();
    });
    this.sortState();
    window.addEventListener('resize',()=>this.positionSortMenu());document.addEventListener('scroll',()=>this.positionSortMenu(),true);
    const searchLabel=this.searchLabel=node('label',t('notes.search'),'form-label');this.search=node('input',undefined,'form-control');this.search.type='search';this.search.id='notes-search';searchLabel.htmlFor=this.search.id;
    this.searchMode=node('select',undefined,'form-select');this.searchMode.id='notes-search-mode';this.searchMode.setAttribute('aria-label',t('notes.search_mode'));
    for(const [value,key]of [['filename','notes.search_names'],['content','notes.search_content']]){const option=node('option',t(key));option.value=value;this.searchMode.append(option);}
    this.searchMode.addEventListener('change',()=>{this.searchLabel.textContent=t(this.searchMode.value==='content'?'notes.search_content':'notes.search');this.invalidateSearch();void this.reload();});
    this.search.addEventListener('input',()=>{this.invalidateSearch();this.controls();this.searchTimer=setTimeout(()=>void this.reload(),250);});
    this.searchMessage=node('p','','hint');this.searchMessage.setAttribute('role','status');this.searchMessage.hidden=true;
    this.searchRetry=action(t('notes.search_retry'),()=>void this.reload());this.searchRetry.hidden=true;
    this.searchRebuild=action(t('notes.search_rebuild'),()=>void this.rebuildSearch());this.searchRebuild.hidden=true;
    this.searchMore=action(t('notes.search_more'),()=>void this.moreSearch());this.searchMore.hidden=true;
    this.searchExcerpts=new Map();
    this.tree=node('div',undefined,'notes-tree');this.tree.setAttribute('role','tree');this.tree.setAttribute('aria-label',t('notes.navigation'));this.tree.addEventListener('keydown',event=>this.treeKey(event));
    this.navigation.append(tools,this.searchMode,searchLabel,this.search,this.searchMessage,this.searchRetry,this.searchRebuild,this.tree,this.searchMore,this.sortMenu);
    this.resize=node('div',undefined,'notes-resize');this.resize.tabIndex=0;this.resize.setAttribute('role','separator');this.resize.setAttribute('aria-orientation','vertical');this.resize.setAttribute('aria-label',t('notes.resize'));this.resize.setAttribute('aria-valuemin','180');this.resize.setAttribute('aria-valuemax','480');this.resize.setAttribute('aria-valuenow','260');
    const width=value=>{this.navWidth=Math.max(180,Math.min(480,value));this.root.style.setProperty('--notes-nav-width',this.navWidth+'px');this.resize.setAttribute('aria-valuenow',String(Math.round(this.navWidth)));};
    this.resize.addEventListener('pointerdown',event=>{event.preventDefault();this.resize.setPointerCapture(event.pointerId);});
    this.resize.addEventListener('pointermove',event=>{if(this.resize.hasPointerCapture(event.pointerId))width(event.clientX-this.layout.getBoundingClientRect().left);});
    this.resize.addEventListener('pointerup',event=>this.resize.releasePointerCapture(event.pointerId));
    this.resize.addEventListener('keydown',event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();width(event.key==='Home'?180:event.key==='End'?480:(this.navWidth||260)+(event.key==='ArrowLeft'?-20:20));}});
    this.editor=node('section',undefined,'notes-editor card');this.editor.setAttribute('aria-labelledby','notes-document-title');
    const fileTools=this.fileTools=node('div',undefined,'notes-file-tools');this.rename=action(t('notes.move'),()=>this.pathDialog('move'),'edit');this.remove=action(t('notes.delete'),()=>void this.delete(),'delete');this.recover=action(t('notes.restore'),()=>void this.restore(),'arrow-counterclockwise');this.reloadNote=action(t('notes.reload'),()=>void this.reloadDocument(),'refresh');this.download=action(t('notes.download_attachment'),()=>void this.downloadAttachment(),'download');this.documentTools=node('div',undefined,'btn-group markdown-document-tools');this.documentTools.setAttribute('role','group');this.documentTools.setAttribute('aria-label',t('notes.document_actions'));this.documentTools.append(this.remove,this.rename,this.reloadNote,this.recover,this.download);
    const resources={bookmark:{current:()=>this.note&&!this.note.deleted&&['MARKDOWN','DAY','WEEK','EVENT'].includes(this.note.kind)?{session:this.session,sourceType:this.note.sourceType,sourceKey:this.note.sourceKey,writable:this.writable&&!this.busy}:null,error:error=>this.fail(error),changed:(value,etag)=>void this.bookmarkChanged(value,etag)},...noteRevisionResources(()=>this.session,()=>({sourceType:this.note?.sourceType,sourceId:this.note?.sourceId}),{latest:async()=>{const session=this.session,id=this.note?.id;const reply=await session.request('/notes/entries/'+id+'/content',{binary:'application/octet-stream'});return {text:await reply.data.text(),etag:reply.etag};}}),...vaultEditorResources(()=>this.session,()=>this.note?.path||'',async(entry,heading)=>{await this.select(entry.id);if(heading)await this.markdown.showFormatted(false);this.markdown.scrollToHeading?.(heading);}),canonicalAsset:async reference=>(await this.resolveLink(reference)).path,allowsAsset:reference=>!!this.linkPath(reference),allowsLink:reference=>!!this.linkPath(reference),image:(reference,alt)=>this.image(reference,alt),openLink:reference=>void this.openLink(reference)};
    const host=node('div',undefined,'notes-markdown');this.markdown=new MarkdownField(host,()=>{this.controls();this.autosave?.changed();},()=>{if(this.dirty()&&!this.saveFailed&&!this.stale)this.autosave?.changed();},'notes',(file,image)=>this.upload(file,image),resources);
    this.markdown.addActionsGroup(this.documentTools);this.editor.append(this.markdown.actionsMenu);this.editor.addEventListener('keydown',event=>this.markdown.historyShortcut(event));
    this.preview=node('div',undefined,'notes-image-preview');this.preview.hidden=true;
    this.empty=node('p',t('notes.select'),'hint');this.status=node('p','','hint');this.status.setAttribute('role','status');
    this.timestamps=node('p',undefined,'hint notes-timestamps');this.timestamps.hidden=true;this.savedDates={};
    for(const [field,key]of [['createdAt','notes.created'],['modifiedAt','notes.modified']]){const label=node('span'),time=node('time');label.append(document.createTextNode(t(key)+' '),time);this.savedDates[field]=time;this.timestamps.append(label);}
    const footer=node('div',undefined,'notes-footer');footer.append(this.status,this.timestamps);this.retrySave=action(t('journal.retry_save'),()=>void this.retryCommit());this.retrySave.hidden=true;footer.append(this.retrySave);
    this.editor.append(fileTools,this.empty,host,this.preview,footer);this.layout.append(this.navigation,this.resize,this.editor);root.append(heading,this.error,this.layout);
    this.dialog=node('dialog',undefined,'app-dialog');this.dialogTitle=node('h2');this.dialogTitle.id='notes-path-title';this.dialog.setAttribute('aria-labelledby',this.dialogTitle.id);
    this.form=node('form');const pathLabel=node('label',t('notes.path'),'form-label');this.pathInput=node('input',undefined,'form-control');this.pathInput.id='notes-path';this.pathInput.required=true;this.pathInput.maxLength=1024;pathLabel.htmlFor=this.pathInput.id;
    this.dialogError=node('p','','alert alert-danger');this.dialogError.hidden=true;this.dialogError.setAttribute('role','alert');
    const buttons=node('div',undefined,'dialog-actions');this.cancel=action(t('ui.cancel'),()=>{if(!this.busy)this.dialog.close();});this.apply=node('button',t('ui.save'),'btn btn-primary');this.apply.type='submit';buttons.append(this.cancel,this.apply);this.form.append(pathLabel,this.pathInput,this.dialogError,buttons);this.dialog.append(this.dialogTitle,this.form);root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{if(this.busy)event.preventDefault();});this.form.addEventListener('submit',event=>{event.preventDefault();void this.applyPath();});
    this.autosave=new DocumentAutosave({dirty:()=>this.dirty(),ready:()=>!!this.session&&this.writable&&!this.busy&&!this.stale&&!this.markdown.loading,save:options=>this.commit(options)});bindDocumentAutosave(this.editor,this.autosave);
    window.addEventListener('beforeunload',event=>{if(this.dirty()){event.preventDefault();event.returnValue='';}});
    document.addEventListener('timekeeper:notes-changed',()=>{if(this.treePointerPending||this.dragged)this.reloadAfterDrag=true;else{this.info=null;this.folder=null;}this.markdown.bookmark?.refresh();if(this.session)this.callbacks.onChanged?.();});
    this.observer=new ResizeObserver(()=>this.fit());for(const target of [document.documentElement,heading,this.error])this.observer.observe(target);window.addEventListener('resize',()=>this.fit());
    window.visualViewport?.addEventListener('resize',()=>this.fit());
    this.moveStatus=node('p','','visually-hidden');this.moveStatus.setAttribute('role','status');root.append(this.moveStatus);
    this.navigation.addEventListener('pointerdown',event=>{if(event.button===0&&event.target.closest?.('.notes-tree-pick'))this.treePointerPending=true;});
    for(const type of ['pointerup','pointercancel'])window.addEventListener(type,()=>{if(this.treePointerPending&&!this.dragged)this.clearDrag();});
    this.navigation.addEventListener('dragstart',event=>{
      const pick=event.target.closest?.('.notes-tree-pick'),entry=pick&&this.entries.get(pick.parentElement.dataset.id);
      if(!entry||!pick.draggable||!this.writable||this.stale||this.busy||this.loading&&!this.treePointerPending){event.preventDefault();return;}
      this.dragged={id:entry.id,session:this.session,generation:this.generation};event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('application/x-timekeeper-note',entry.id);
    });
    this.navigation.addEventListener('dragover',event=>{
      const target=this.dropTarget(event);this.clearDropTarget();if(!target)return;
      event.preventDefault();event.dataTransfer.dropEffect='move';target.element.classList.add('notes-drop-target');
    });
    this.navigation.addEventListener('drop',event=>{
      const target=this.dropTarget(event),source=this.dragged;this.clearDrag();if(!target)return;
      event.preventDefault();void this.moveDocument(source,target.folder);
    });
    this.navigation.addEventListener('dragleave',event=>{if(!this.navigation.contains(event.relatedTarget))this.clearDropTarget();});
    this.navigation.addEventListener('dragend',()=>this.clearDrag());
    // CSS can hide a focused control before matchMedia's change event, leaving
    // activeElement at body. Remember actual focus so the toggle can receive it.
    document.addEventListener('focusin',event=>{this.navigationFocused=this.navigation.contains(event.target)||event.target===this.resize;});
    this.narrow.addEventListener('change',()=>{this.root.classList.remove('notes-drawer-open');this.navigationState();});this.navigationState();this.controls();
  }
  dirty(){return !!this.note&&!this.binary&&!this.markdown.matches(this.original);}
  async canLeave(){return !this.busy&&await this.autosave.flush();}
  async reloadDocument(){if(this.saving)await this.autosave.flush();if(!this.note||this.busy||this.dirty()&&!confirm(t('journal.reload_confirm')))return;await this.select(this.note.id,true);}
  async retryCommit(){if(this.uncertain){await this.checkCommitted();return;}this.stale=false;this.error.hidden=true;await this.autosave.flush();}
  update(snapshot,connected,session,writable){
    if(this.session&&this.session!==session)this.reset();this.session=session;this.zone=snapshot?.profile?.data?.zoneId||'UTC';this.writable=connected&&writable;this.controls();
    if(this.visible&&!this.busy&&!this.loading)void this.reload();
  }
  show(){this.visible=true;window.scrollTo(0,0);this.fit();if(this.session)void this.reload();}
  hide(){this.visible=false;this.clearDrag();}
  clearDropTarget(){this.navigation.classList.remove('notes-drop-target');for(const node of this.navigation.querySelectorAll('.notes-drop-target'))node.classList.remove('notes-drop-target');}
  clearDrag(){
    const refresh=this.reloadAfterDrag,session=this.session;this.dragged=null;this.treePointerPending=false;this.reloadAfterDrag=false;this.clearDropTarget();
    // Let the associated click/drop finish before replacing any tree nodes.
    if(refresh)setTimeout(()=>{if(this.visible&&this.session===session&&!this.busy)void this.reload();},0);
  }
  dropTarget(event){
    const drag=this.dragged;if(!drag||drag.session!==this.session||drag.generation!==this.generation||!this.writable||this.stale||this.busy||this.showTrash||!this.visible||!event.dataTransfer?.types.includes('application/x-timekeeper-note'))return null;
    const source=this.entries.get(drag.id),row=event.target.closest?.('[role=treeitem]');
    const folder=row?this.entries.get(row.dataset.id):{id:this.info.rootId,path:'',kind:'FOLDER'};
    if(!source||source.sourceType!=='VAULT_CONTENT'||!folder||folder.kind!=='FOLDER'||source.parentId===folder.id)return null;
    return {folder,element:row?.querySelector('.notes-tree-pick')||this.navigation};
  }
  async moveDocument(drag,folder){
    if(!await this.canLeave()||drag.session!==this.session||drag.generation!==this.generation||!this.writable||this.stale)return;
    const entry=this.note?.id===drag.id?this.note:this.entries.get(drag.id);if(!entry||entry.deleted||entry.sourceType!=='VAULT_CONTENT')return;
    const path=(folder.path?folder.path+'/':'')+entry.path.split('/').at(-1);this.moveFailed=false;this.busy=true;this.controls();
    try{
      const reply=await drag.session.request('/notes/entries/'+entry.id+'/path',{method:'PUT',etag:entry.entryEtag,body:{path}});
      if(drag.session!==this.session||drag.generation!==this.generation)return;
      this.markdown.resources?.invalidate?.();
      if(this.note?.id===entry.id){this.note={...reply.data};this.documentTitle.textContent=noteTitle(this.note,this.note.path);}
      this.expanded.add(folder.id);this.busy=false;await this.reload();
      if(drag.session!==this.session||drag.generation!==this.generation)return;
      this.moveStatus.textContent=t('notes.moved',{path:reply.data.path});this.callbacks.onChanged?.();
    }catch(error){if(drag.session===this.session&&drag.generation===this.generation){this.moveFailed=true;this.fail(error);}}
    finally{if(drag.session===this.session&&drag.generation===this.generation){this.busy=false;this.controls();}}
  }
  clearPreview(){this.selectedImage?.destroy();this.selectedImage=null;this.preview.replaceChildren();this.preview.hidden=true;}
  reset(){this.invalidateSearch();this.searchMode.value='filename';this.searchLabel.textContent=t('notes.search');this.moveFailed=false;this.autosave.reset();this.saving=false;this.uncertain=null;this.saveFailed=false;this.clearPreview();this.clearDrag();this.moveStatus.textContent='';++this.generation;++this.loadGeneration;this.session=null;this.info=null;this.note=null;this.original='';this.binary=false;this.stale=false;this.busy=false;this.loading=false;this.expandAllRequested=false;this.showTrash=false;this.trash.setAttribute('aria-pressed','false');this.trash.classList.remove('active');this.sortOrder='name-asc';this.closeSortMenu(false);this.sortState();this.navCollapsed=false;this.root.classList.remove('notes-drawer-open');this.navigationState();this.positions.clear();this.entries.clear();this.children.clear();this.expanded.clear();this.tree.replaceChildren();this.markdown.clear();this.dialog.close();this.error.hidden=true;this.search.value='';this.status.textContent='';this.documentTitle.textContent=t('notes.select');this.controls();}
  navigationState(){
    const focused=this.navigation.contains(document.activeElement)||document.activeElement===this.resize||this.navigationFocused&&document.activeElement===document.body;
    this.root.classList.toggle('notes-navigation-collapsed',!!this.navCollapsed);
    const visible=this.narrow.matches?this.root.classList.contains('notes-drawer-open'):!this.navCollapsed;
    this.toggle.setAttribute('aria-expanded',String(visible));
    if(!visible){this.closeSortMenu(false);if(this.visible&&focused)this.toggle.focus();}
    this.fit();
  }
  fit(){if(!this.visible||!this.root.offsetParent)return;const viewport=window.visualViewport,bottom=viewport?viewport.offsetTop+viewport.height:innerHeight;document.documentElement.style.setProperty('--notes-viewport-height',bottom+'px');}
  canDrag(entry){return !!(entry&&this.writable&&!this.stale&&!this.busy&&(!this.loading||this.treePointerPending||this.dragged)&&!this.showTrash&&!entry.deleted&&entry.sourceType==='VAULT_CONTENT');}
  controls(){
    this.tree.setAttribute('aria-busy',String(!!this.loading));
    for(const pick of this.tree.querySelectorAll('.notes-tree-pick'))pick.draggable=this.canDrag(this.entries.get(pick.parentElement.dataset.id));
    const write=!!this.session&&this.writable&&!this.busy&&!this.stale;this.markdown.setReadOnly(!write||!this.note||this.binary||this.note.deleted);
    this.newNote.disabled=this.newFolder.disabled=!write||!!this.showTrash;this.retrySave.hidden=!this.saveFailed||!this.note||this.note.deleted;this.retrySave.textContent=t(this.uncertain?'journal.check_result':'journal.retry_save');this.retrySave.disabled=!!this.busy||!!this.saving||!this.writable||this.stale&&!this.uncertain;
    this.rename.disabled=this.remove.disabled=!write||this.saving||!this.note||this.note.deleted;this.recover.hidden=!this.note?.deleted;this.recover.disabled=!write;
    this.reloadNote.disabled=!this.note||this.busy||this.saving;this.apply.disabled=this.cancel.disabled=!!this.busy;
    this.searchLabel.textContent=t(this.searchMode.value==='content'&&!this.showTrash?'notes.search_content':'notes.search');this.searchMode.disabled=!this.session||this.busy||this.showTrash;this.searchMore.disabled=this.searchRetry.disabled=this.searchRebuild.disabled=!!this.loading||this.busy;this.sortButton.disabled=!this.session||this.busy||this.contentSearch();
    this.collapseAll.disabled=!this.session||!this.info||this.busy||this.showTrash||!!this.search.value.trim();
    this.expandAll.disabled=this.collapseAll.disabled||this.expandAllRequested;
    this.download.hidden=!this.note||this.note.kind!=='BINARY'&&this.note.kind!=='ASSET'||this.note.deleted;this.download.disabled=!!this.busy;
    this.empty.hidden=!!this.selectedImage||!!this.note&&!this.binary&&!this.note.deleted;this.preview.hidden=!this.selectedImage;this.markdown.root.hidden=!this.note||this.binary||this.note.deleted;
    const host=this.markdown.root.hidden?this.fileTools:this.markdown.toolbar;if(this.markdown.actionsTrigger.parentElement!==host)host.append(this.markdown.actionsTrigger);
    this.markdown.resourceTools.hidden=this.markdown.root.hidden;this.documentTools.hidden=!this.note;this.fileTools.hidden=!this.note||host!==this.fileTools;this.markdown.syncActions();
    if(!this.note)this.status.textContent='';else if(!this.busy)this.status.textContent=this.saving?t('journal.saving'):this.stale?t('notes.stale'):this.dirty()?t('journal.unsaved'):t('journal.saved');
    this.timestamps.hidden=!this.note;
    for(const [field,time]of Object.entries(this.savedDates)){
      if(field==='createdAt'){time.parentElement.firstChild.textContent=t(this.note?.createdAtSource==='ORIGINAL'?'notes.created':this.note?.createdAtSource==='ADDED'?'notes.added':'notes.created_or_added')+' ';time.parentElement.title=t('notes.creation_date_help');}
      const value=this.note?.[field],date=new Date(value||NaN),valid=Number.isFinite(date.getTime());time.parentElement.hidden=!valid;
      time.dateTime=valid?value:'';time.textContent=valid?dateTimeFormat(getLocale(),{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',timeZoneName:'short',timeZone:this.zone||'UTC'}).format(date):'';
    }
  }
  async bookmarkChanged(value,etag){
    const session=this.session,note=this.note;if(!note)return;
    note.bookmarked=value.bookmarked;note.bookmarkEtag=etag;
    const entry=this.entries.get(note.id);if(entry)entry.bookmarked=value.bookmarked;this.paint();
    try{
      const reply=await session.request('/notes/entries/'+note.id);
      if(session!==this.session||note!==this.note)return;
      if(reply.data.etag===note.etag)note.entryEtag=reply.data.entryEtag;
    }catch(error){if(session===this.session&&note===this.note)this.fail(error);}
    if(session===this.session)document.dispatchEvent(new CustomEvent('timekeeper:notes-changed'));
  }
  fail(error){this.error.hidden=false;this.error.textContent=error.uncertain?t('notes.uncertain'):error.message;if(error.status===401)this.callbacks.onUnauthorized?.(error);}
  sortState(){
    const order=noteSortOrders.find(value=>value.value===this.sortOrder);this.sortButton.replaceChildren(icon(order.icon));this.sortButton.title=t('notes.sort')+' · '+t(order.label);this.sortButton.setAttribute('aria-description',t(order.label));
    for(const choice of this.sortMenu.children)choice.setAttribute('aria-checked',String(choice.dataset.sort===this.sortOrder));
  }
  closeSortMenu(focus=true){if(this.sortMenu.matches(':popover-open')){this.sortMenu.hidePopover();if(focus)this.sortButton.focus();}}
  showSortMenu(keyboard=false,last=false){
    if(this.sortButton.disabled)return;if(this.sortMenu.matches(':popover-open')){this.closeSortMenu();return;}
    this.sortMenu.showPopover();this.positionSortMenu();if(keyboard)(last?this.sortMenu.lastElementChild:this.sortMenu.querySelector('[aria-checked=true]')).focus();
  }
  positionSortMenu(){
    if(!this.sortMenu.matches(':popover-open'))return;const anchor=this.sortButton.getBoundingClientRect(),menu=this.sortMenu.getBoundingClientRect();
    this.sortMenu.style.left=Math.max(8,Math.min(anchor.left,innerWidth-menu.width-8))+'px';this.sortMenu.style.top=Math.max(8,Math.min(anchor.bottom+4,innerHeight-menu.height-8))+'px';
  }
  invalidateSearch(){
    clearTimeout(this.searchTimer);this.loadGeneration=(this.loadGeneration||0)+1;this.searchExcerpts?.clear();this.contentResults=false;this.searchCursor=null;
    for(const item of [this.searchMessage,this.searchRetry,this.searchRebuild,this.searchMore])if(item)item.hidden=true;
    if(this.searchMode?.value==='content')this.tree?.replaceChildren();
  }
  contentSearch(){return this.searchMode.value==='content'&&!this.showTrash&&!!this.search.value.trim();}
  async findTag(tag){
    if(!this.session)return;this.searchMode.value='content';this.search.value='tag:'+tag;
    this.showTrash=false;this.trash.setAttribute('aria-pressed','false');this.trash.classList.remove('active');
    this.navCollapsed=false;this.root.classList.add('notes-drawer-open');this.navigationState();
    this.invalidateSearch();this.search.focus({preventScroll:true});await this.reload();
  }
  async searchPage(session,current,cursor){
    const query=new URLSearchParams({q:this.search.value.trim(),limit:50});if(cursor)query.set('cursor',cursor);
    const reply=await session.request('/notes/search?'+query);if(session!==this.session||current!==this.loadGeneration)return null;
    this.searchCursor=reply.data.nextCursor||null;this.searchMore.hidden=!this.searchCursor;this.searchMessage.hidden=false;this.searchRetry.hidden=this.searchRebuild.hidden=true;
    const rows=reply.data.items.map(hit=>{this.searchExcerpts.set(hit.entry.id,hit.excerpt);return hit.entry;});
    this.searchMessage.textContent=t(rows.length||cursor?'notes.search_results':'notes.search_none');return rows;
  }
  searchFailed(error){
    this.children=new Map([['search',[]]]);this.entries=new Map();this.contentResults=true;this.searchExcerpts.clear();this.searchCursor=null;this.searchMore.hidden=true;this.tree.replaceChildren();
    this.searchMessage.textContent=t(error.status===412?'notes.search_changed':error.status===422?'notes.search_invalid':'notes.search_unavailable');this.searchMessage.hidden=false;this.searchRetry.hidden=false;this.searchRebuild.hidden=!this.writable||error.status!==503;
  }
  async moreSearch(){
    if(!this.session||!this.searchCursor||this.loading)return;const session=this.session,current=++this.loadGeneration,cursor=this.searchCursor,focusMore=document.activeElement===this.searchMore,previousCount=this.children.get('search')?.length||0;this.loading=true;this.controls();let focusRetry=false;
    try{const rows=await this.searchPage(session,current,cursor);if(!rows)return;for(const row of rows)this.entries.set(row.id,row);this.children.get('search').push(...rows);const focusStillPaging=focusMore&&(document.activeElement===this.searchMore||document.activeElement===document.body);this.paint();if(focusStillPaging)this.tree.querySelectorAll('[role=treeitem]')[previousCount]?.focus();}
    catch(error){if(session===this.session&&current===this.loadGeneration){focusRetry=focusMore&&(document.activeElement===this.searchMore||document.activeElement===document.body);this.searchFailed(error);}}
    finally{if(session===this.session&&current===this.loadGeneration){this.loading=false;this.controls();if(focusRetry)this.searchRetry.focus();}}
  }
  async rebuildSearch(){
    if(!this.session||!this.writable||this.loading)return;const session=this.session,current=++this.loadGeneration;this.loading=true;this.controls();
    try{await session.request('/notes/search/rebuild',{method:'POST'});if(session===this.session&&current===this.loadGeneration){this.searchMessage.textContent=t('notes.search_preparing');this.searchRebuild.hidden=true;}}
    catch(error){if(session===this.session&&current===this.loadGeneration)this.searchFailed(error);}
    finally{if(session===this.session&&current===this.loadGeneration){this.loading=false;this.controls();}}
  }
  async list(parameters,session,snapshot,current){
    const rows=[];let offset=0;
    do{if(session!==this.session||current!==this.loadGeneration)return null;const query=new URLSearchParams({...parameters,offset,limit:this.info.limits.pageSize});const reply=await session.request('/notes/entries?'+query,{etag:snapshot});if(session!==this.session||current!==this.loadGeneration)return null;snapshot=reply.data.snapshot;rows.push(...reply.data.items);offset=reply.data.nextOffset;}while(offset!==undefined);
    return rows;
  }
  async reload(retry=0){
    if(this.treePointerPending||this.dragged){this.reloadAfterDrag=true;return;}
    if(!this.session)return;const session=this.session,current=(this.loadGeneration||0)+1,expandAll=this.expandAllRequested,expanded=new Set(this.expanded);this.loadGeneration=current;this.loading=true;this.controls();
    try{
      const reply=await session.request('/notes');if(session!==this.session||current!==this.loadGeneration)return;this.info=reply.data;if(this.folder&&!notesEntryVisible(this.folder,this.info.settings))this.folder=null;const snapshot=reply.data.snapshot;
      const search=this.search.value.trim();const children=new Map(),entries=new Map();
      this.searchExcerpts.clear();this.searchCursor=null;this.contentResults=this.contentSearch();for(const item of [this.searchMessage,this.searchRetry,this.searchRebuild,this.searchMore])item.hidden=true;
      if(this.contentResults){this.searchMessage.textContent=t('notes.search_preparing');this.searchMessage.hidden=false;const rows=await this.searchPage(session,current,null);if(!rows)return;children.set('search',rows);for(const row of rows)entries.set(row.id,row);}
      else if(search||this.showTrash){const listed=await this.list({query:search,...(this.showTrash?{trash:true}:{})},session,snapshot,current);if(!listed)return;const rows=listed.filter(entry=>notesEntryVisible(entry,this.info.settings));children.set('search',rows);for(const row of rows)entries.set(row.id,row);}
      else{const pending=[this.info.rootId];while(pending.length){const parent=pending.shift(),listed=await this.list({parentId:parent},session,snapshot,current);if(!listed)return;const rows=listed.filter(entry=>notesEntryVisible(entry,this.info.settings));children.set(parent,rows);for(const row of rows){entries.set(row.id,row);if(row.kind==='FOLDER'&&(expandAll||expanded.has(row.id))){expanded.add(row.id);pending.push(row.id);}}}}
      if(session!==this.session||current!==this.loadGeneration)return;this.expanded=expanded;this.children=children;this.entries=entries;this.paint();if(!this.stale&&!this.moveFailed)this.error.hidden=true;
      if(this.note&&!this.saving){const inspected=this.note;const reply=await session.request('/notes/entries/'+inspected.id);if(session!==this.session||current!==this.loadGeneration)return;if(this.note!==inspected||this.saving)return;if(reply.data.etag!==this.note.etag){this.stale=true;this.error.textContent=t('notes.stale');this.error.hidden=false;}else{this.note={...reply.data};this.documentTitle.textContent=noteTitle(this.note,this.note.path);}this.markdown.bookmark?.refresh();}
    }catch(error){if(session===this.session&&current===this.loadGeneration){if(this.contentSearch()){this.searchFailed(error);return;}if(error.status===412&&retry===0){await this.reload(1);return;}this.fail(error);}}
    finally{if(session===this.session&&current===this.loadGeneration){this.loading=false;this.expandAllRequested=false;this.controls();}}
  }
  paint(){
    if(!this.info)return;
    if(this.treePointerPending||this.dragged){this.reloadAfterDrag=true;return;}
    const focused=this.tree.querySelector(':focus')?.dataset.id;this.tree.replaceChildren();
    const append=(parent,host)=>{for(const entry of this.contentResults?(this.children.get(parent)||[]):sortedNotes(this.children.get(parent)||[],this.sortOrder,getLocale())){
      const folder=entry.kind==='FOLDER',row=node('div',undefined,'notes-tree-row');row.setAttribute('role','treeitem');row.setAttribute('aria-selected',String(this.note?.id===entry.id));row.dataset.id=entry.id;row.tabIndex=-1;
      const title=noteTitle(entry,parent==='search'?entry.path:entry.path.split('/').at(-1));
      const pick=action(entry.path,()=>{if(folder&&!this.showTrash){this.expandAllRequested=false;this.folder=entry;this.expanded.has(entry.id)?this.expanded.delete(entry.id):this.expanded.add(entry.id);void this.reload();}void this.select(entry.id);});pick.className='notes-tree-pick';pick.tabIndex=-1;pick.title=entry.kind==='EVENT'?title:entry.path;pick.textContent='';pick.setAttribute('aria-label',entry.title?title+' · '+entry.path:entry.path);pick.append(icon(folder?'folder':entry.bookmarked?'bookmark':'file-earmark-text'),node('span',title));row.append(pick);
      const excerpt=this.searchExcerpts.get(entry.id);
      if(this.contentResults&&excerpt){
        pick.append(node('span',entry.path,'notes-search-path'));
        const text=node('span',undefined,'notes-search-excerpt');pick.removeAttribute('aria-label');let offset=0;
        for(const range of excerpt.highlights){text.append(document.createTextNode(excerpt.text.slice(offset,range.start)),node('mark',excerpt.text.slice(range.start,range.end)));offset=range.end;}
        text.append(document.createTextNode(excerpt.text.slice(offset)));pick.classList.add('notes-search-pick');pick.append(text);
      }
      pick.draggable=this.canDrag(entry);
      row.addEventListener('focus',()=>{for(const item of this.tree.querySelectorAll('[role=treeitem]'))item.tabIndex=item===row?0:-1;});
      if(folder&&!this.showTrash){row.setAttribute('aria-expanded',String(this.expanded.has(entry.id)));if(this.expanded.has(entry.id)){const group=node('div');group.setAttribute('role','group');append(entry.id,group);row.append(group);}}host.append(row);
    }};append(this.children.has('search')?'search':this.info.rootId,this.tree);
    if(!this.tree.children.length)this.tree.append(node('p',t('notes.empty'),'hint'));
    const target=[...this.tree.querySelectorAll('[role=treeitem]')].find(row=>row.dataset.id===focused)||this.tree.querySelector('[role=treeitem]');if(target){target.tabIndex=0;if(focused)target.focus();}
  }
  treeKey(event){
    const row=event.target.closest('[role=treeitem]');if(!row)return;const rows=[...this.tree.querySelectorAll('[role=treeitem]')],index=rows.indexOf(row),entry=this.entries.get(row.dataset.id);
    if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();rows[event.key==='Home'?0:event.key==='End'?rows.length-1:Math.max(0,Math.min(rows.length-1,index+(event.key==='ArrowDown'?1:-1)))].focus();}
    if(event.key==='ArrowRight'&&entry.kind==='FOLDER'&&!this.expanded.has(entry.id)){event.preventDefault();this.expandAllRequested=false;this.expanded.add(entry.id);void this.reload();}
    if(event.key==='ArrowLeft'){event.preventDefault();this.expandAllRequested=false;if(this.expanded.delete(entry.id))void this.reload();else row.parentElement.closest('[role=treeitem]')?.focus();}
    if(['Enter',' '].includes(event.key)){event.preventDefault();row.querySelector('button').click();}
  }
  async select(id,force=false){
    if(!id||this.busy||!force&&this.note?.id===id||!force&&!await this.canLeave())return;
    if(this.note&&!this.binary)this.positions.set(this.note.id,this.markdown.position());
    this.moveFailed=false;this.autosave.reset();this.saveFailed=false;this.uncertain=null;const session=this.session,current=++this.generation;this.clearPreview();this.note=null;this.original='';this.binary=false;this.stale=false;this.markdown.clear();this.controls();
    try{
      const reply=await session.request('/notes/entries/'+id);if(session!==this.session||current!==this.generation)return;
      const metadata={...reply.data};let text='',imageBlob=null;
      if(metadata.kind!=='FOLDER'&&!metadata.deleted){const content=await session.request('/notes/entries/'+id+'/content',{binary:'application/octet-stream'});if(content.etag!==metadata.etag)throw new Error(t('notes.stale'));if(session!==this.session||current!==this.generation)return;
        if(['MARKDOWN','DAY','WEEK','EVENT'].includes(metadata.kind))text=textDecoder.decode(await content.data.arrayBuffer());else{this.binary=true;if(rasterType(new Uint8Array(await content.data.slice(0,12).arrayBuffer())))imageBlob=content.data;}}
      if(session!==this.session||current!==this.generation)return;this.note=metadata;this.original=text;this.documentTitle.textContent=noteTitle(metadata,metadata.path);this.markdown.setValue(text);this.error.hidden=true;
      if(imageBlob){this.selectedImage=imagePreview({alt:metadata.path.split('/').at(-1),load:async()=>imageBlob,active:()=>session===this.session&&current===this.generation});this.preview.append(this.selectedImage.dom);}
      this.empty.textContent=metadata.deleted?t('notes.deleted'):this.binary?t('notes.binary'):t('notes.select');this.binary||=metadata.kind==='FOLDER';this.root.classList.remove('notes-drawer-open');this.navigationState();this.controls();this.paint();if(this.positions.has(id))await this.markdown.restorePosition(this.positions.get(id));else if(!this.binary&&!metadata.deleted)await this.markdown.showFormatted(false);
    }catch(error){if(session===this.session&&current===this.generation)this.fail(error);}
  }
  async commit({keepalive=false}={}){
    if(!this.session||this.busy||this.saving||this.stale||!this.writable||this.markdown.loading)return false;
    if(!this.dirty())return true;
    const session=this.session,current=this.generation,id=this.note.id,body=this.markdown.value();this.saving=true;this.controls();
    try{
      const reply=await session.request('/notes/entries/'+id+'/content',{method:'PUT',etag:this.note.etag,rawBody:new TextEncoder().encode(body),keepalive:keepalive&&new TextEncoder().encode(body).length<60000});if(session!==this.session||current!==this.generation)return false;
      this.note={...reply.data,etag:reply.etag};this.documentTitle.textContent=noteTitle(this.note,this.note.path);this.original=body;this.saveFailed=false;this.error.hidden=true;this.callbacks.onChanged?.();return true;
    }catch(error){if(session===this.session&&current===this.generation){this.stale=error.status===412||!!error.uncertain;this.saveFailed=true;if(error.uncertain)this.uncertain={id,body,etag:this.note.etag};this.fail(error);}return false;}
    finally{if(session===this.session&&current===this.generation){this.saving=false;this.controls();}}
  }
  async checkCommitted(){
    const sent=this.uncertain,session=this.session,current=this.generation;if(!sent||this.saving)return false;
    this.saving=true;this.controls();
    try{
      const reply=await session.request('/notes/entries/'+sent.id+'/content',{binary:'application/octet-stream'});const text=textDecoder.decode(await reply.data.arrayBuffer());if(session!==this.session||current!==this.generation)return false;
      this.uncertain=null;if(reply.etag===sent.etag){this.stale=false;this.saveFailed=false;this.error.hidden=true;return true;}if(text!==sent.body){this.stale=true;this.error.textContent=t('notes.stale');return false;}
      const metadata=await session.request('/notes/entries/'+sent.id);if(session!==this.session||current!==this.generation)return false;
      if(metadata.data.etag!==reply.etag){this.stale=true;this.error.textContent=t('notes.stale');return false;}
      this.note=metadata.data;this.original=sent.body;this.stale=false;this.saveFailed=false;this.error.hidden=true;this.callbacks.onChanged?.();return true;
    }catch(error){if(session===this.session&&current===this.generation)this.fail(error);return false;}
    finally{if(session===this.session&&current===this.generation){this.saving=false;this.controls();if(!this.stale&&this.dirty())this.autosave.changed();}}
  }
  async pathDialog(mode){
    if(!this.writable||this.busy||!await this.canLeave())return;this.pathMode=mode;this.dialogTitle.textContent=t(mode==='move'?'notes.move':mode==='folder'?'notes.new_folder':'notes.new_note');this.dialogError.hidden=true;
    this.pathInput.value=mode==='move'?this.note.path:(this.folder?.path?this.folder.path+'/':'')+(mode==='note'?'Untitled.md':'');this.dialog.showModal();this.pathInput.focus();this.pathInput.select();
  }
  async applyPath(){
    if(this.busy)return;const session=this.session,current=this.generation,mode=this.pathMode,path=this.pathInput.value;this.busy=true;this.controls();
    try{let reply;if(mode==='move')reply=await session.request('/notes/entries/'+this.note.id+'/path',{method:'PUT',etag:this.note.entryEtag,body:{path}});
      else if(mode==='folder')reply=await session.request('/notes/folders',{method:'POST',body:{path}});
      else reply=await session.request('/notes/files?'+new URLSearchParams({path}),{method:'PUT',noneMatch:'*',rawBody:new Uint8Array()});
      if(session!==this.session||current!==this.generation)return;this.dialog.close();this.busy=false;this.info=null;await this.reload();await this.select(reply.data.id,true);this.callbacks.onChanged?.();
    }catch(error){if(session===this.session&&current===this.generation){this.dialogError.textContent=error.message;this.dialogError.hidden=false;}}
    finally{if(session===this.session&&current===this.generation){this.busy=false;this.controls();}}
  }
  async delete(){
    if(this.remove.disabled||!await this.canLeave()||!confirm(t('notes.delete_confirm',{path:this.note.path})))return;
    const session=this.session,current=this.generation;this.busy=true;this.controls();
    try{await session.request('/notes/entries/'+this.note.id,{method:'DELETE',etag:this.note.entryEtag});if(session!==this.session||current!==this.generation)return;this.clearPreview();this.note=null;this.markdown.clear();this.original='';this.busy=false;await this.reload();this.callbacks.onChanged?.();}
    catch(error){if(session===this.session&&current===this.generation){this.stale=true;this.fail(error);}}
    finally{if(session===this.session&&current===this.generation){this.busy=false;this.controls();}}
  }
  async restore(){
    if(this.recover.disabled)return;const session=this.session,current=this.generation;this.busy=true;this.controls();
    try{const reply=await session.request('/notes/entries/'+this.note.id+'/restore',{method:'POST',etag:this.note.entryEtag});if(session!==this.session||current!==this.generation)return;this.busy=false;this.showTrash=false;this.trash.setAttribute('aria-pressed','false');this.trash.classList.remove('active');await this.reload();await this.select(reply.data.id,true);this.callbacks.onChanged?.();}
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
  async resolveLink(reference,session=this.session){
    const path=this.linkPath(reference),relative=this.note?relativeNotePath(this.note.path,reference,{legacyAssets:false}):null;if(!path||!relative)throw new Error(t('notes.invalid_link'));
    const find=async path=>{try{return (await session.request('/notes/resolve?'+new URLSearchParams({path}))).data;}catch(error){if(error.status===404)return null;throw error;}};
    if(path===relative){
      const ordinary=await find(relative);
      if(ordinary)return ordinary;
      // Keep old root-assets references renderable after nesting the default
      // folder, without rewriting authored Markdown or shadowing a real path.
      const folder=this.info?.settings.assetsFolder;
      const relocated=relative.startsWith('assets/')&&folder&&folder!=='assets'?await find(folder+'/'+relative.slice(7)):null;
      if(!relocated)throw new Error(t('notes.invalid_link'));return relocated;
    }
    const [ordinary,candidate]=await Promise.all([find(relative),find(path)]),legacy=candidate?.kind==='ASSET'?candidate:null;
    if(ordinary&&legacy&&ordinary.id!==legacy.id)throw new Error(t('notes.ambiguous_link'));
    if(!ordinary&&!legacy)throw new Error(t('notes.invalid_link'));return ordinary||legacy;
  }
  image(reference,alt){
    const session=this.session,current=this.generation;
    return imagePreview({alt,active:()=>session===this.session&&current===this.generation,load:async()=>{
      const entry=await this.resolveLink(reference,session);
      return (await session.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'})).data;
    },onError:(error,dom)=>{dom.alt=(alt?alt+' · ':'')+t('notes.unavailable_image');dom.title=error.message;if(error.status===401)this.callbacks.onUnauthorized?.(error);}});
  }
  async openLink(reference){const session=this.session,current=this.generation;try{const entry=await this.resolveLink(reference,session);if(session===this.session&&current===this.generation){await this.select(entry.id);let heading=reference.split('#').slice(1).join('#');try{heading=decodeURIComponent(heading);}catch{}if(heading&&this.note?.id===entry.id){await this.markdown.showFormatted(false);this.markdown.scrollToHeading(heading);}}}catch(error){if(session===this.session&&current===this.generation)this.fail(error);}}
  async downloadAttachment(){
    if(!this.note||this.download.disabled)return;const session=this.session,current=this.generation,note=this.note;
    try{const reply=await session.request('/notes/entries/'+note.id+'/content',{binary:'application/octet-stream'});if(session!==this.session||current!==this.generation)return;
      const url=URL.createObjectURL(reply.data),link=node('a');link.href=url;link.download=note.path.split('/').at(-1);link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    }catch(error){if(session===this.session&&current===this.generation)this.fail(error);}
  }
}

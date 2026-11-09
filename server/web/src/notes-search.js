import {t} from './i18n.js';
import {icon,iconButton} from './icons.js';
import {noteTitle} from './note-title.js';
import {notesEntryVisible} from './notes-links.js';
import {vaultNoteNavigation} from './vault-editor-resources.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(label,operation)=>{const value=node('button',label,'btn btn-outline-secondary');value.type='button';value.addEventListener('click',operation);return value;};

/** Pure filename searches retain the catalog's literal substring and folder support. */
export function filenameSearch(query){
  const match=/^file:(?:"((?:\\["\\]|[^"\\])*)"|([^\s"]+))$/i.exec(query.trim());
  return match?(match[1]??match[2]).replace(/\\(["\\])/g,'$1'):null;
}

/** Modeless results remain available behind the shared guarded note popup. */
export class NotesSearch {
  constructor(form,callbacks){
    this.callbacks=callbacks;this.form=form;this.titleInput=form.querySelector('input');this.generation=0;this.items=[];
    const root=this.root=node('aside',undefined,'notes-search-panel');root.id='notes-search-panel';root.hidden=true;root.setAttribute('aria-labelledby','notes-search-heading');
    const heading=node('div',undefined,'notes-search-heading'),title=node('h2',t('notes.search_title'));title.id='notes-search-heading';
    this.close=button('',()=>this.hide());iconButton(this.close,'close',t('ui.close'));heading.append(title,this.close);
    const refine=node('form'),label=node('label',t('notes.search_title'),'form-label');this.input=node('input',undefined,'form-control');this.input.id='notes-search-query';this.input.type='search';this.input.maxLength=512;label.htmlFor=this.input.id;
    const help=node('p',t('notes.search_help'),'hint');help.id='notes-search-help';this.input.setAttribute('aria-describedby',help.id);refine.append(label,this.input,help);
    this.message=node('p','','hint');this.message.setAttribute('role','status');this.results=node('ul',undefined,'notes-search-results');this.results.id='notes-search-results';
    this.more=button(t('notes.search_more'),()=>void this.search(true));this.retry=button(t('notes.search_retry'),()=>void this.search());this.rebuild=button(t('notes.search_rebuild'),()=>void this.rebuildIndex());
    const actions=node('div',undefined,'notes-search-actions');actions.append(this.more,this.retry,this.rebuild);root.append(heading,refine,this.message,this.results,actions);document.body.append(root);
    form.addEventListener('submit',event=>{event.preventDefault();this.show(this.titleInput.value);});
    refine.addEventListener('submit',event=>{event.preventDefault();clearTimeout(this.timer);void this.search();});
    this.input.addEventListener('input',()=>{this.titleInput.value=this.input.value;clearTimeout(this.timer);++this.generation;this.busy=false;this.items=[];this.cursor=null;this.failed=null;this.paint();this.timer=setTimeout(()=>void this.search(),250);});
    root.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();this.hide();}else if(event.key==='ArrowDown'&&event.target===this.input){event.preventDefault();this.results.querySelector('button')?.focus();}});
    this.reset();
  }
  update(session,writable){if(session!==this.session)this.reset();this.session=session;this.writable=writable;this.form.hidden=!session;this.controls();}
  reset(){clearTimeout(this.timer);this.hide(false);++this.generation;this.session=null;this.items=[];this.cursor=null;this.catalog=null;this.offset=undefined;this.busy=false;this.failed=null;this.input.value=this.titleInput.value='';this.form.hidden=true;this.paint();}
  show(query){
    if(!this.session)return;this.origin=document.activeElement;this.input.value=this.titleInput.value=query;this.root.hidden=false;this.input.focus({preventScroll:true});void this.search();
  }
  hide(restore=true){const open=!this.root.hidden;this.root.hidden=true;if(open&&restore)(this.origin?.isConnected&&this.origin.getClientRects().length?this.origin:this.titleInput.getClientRects().length?this.titleInput:this.form.querySelector('button')).focus({preventScroll:true});}
  controls(){this.results.setAttribute('aria-busy',String(!!this.busy));this.more.hidden=!this.cursor&&this.offset===undefined;this.retry.hidden=!this.failed;this.rebuild.hidden=!this.failed||this.failed.status!==503||!this.writable;for(const action of [this.more,this.retry,this.rebuild])action.disabled=this.busy;}
  async search(more=false){
    clearTimeout(this.timer);if(!this.session||more&&this.busy)return;
    const session=this.session,generation=++this.generation,query=this.input.value.trim(),focusedMore=more&&document.activeElement===this.more,previous=this.items.length;
    if(!more){this.items=[];this.cursor=null;this.offset=undefined;this.catalog=null;this.paint();}
    this.busy=true;this.failed=null;this.message.textContent=query?t('notes.search_preparing'):'';this.controls();
    const current=()=>session===this.session&&generation===this.generation;let retryFocus=false;
    try{
      if(!query)return;
      const filename=filenameSearch(query);let incoming;
      if(filename!==null){
        if(!this.catalog){const reply=await session.request('/notes');if(!current())return;this.catalog=reply.data;}
        const params=new URLSearchParams({query:filename,limit:this.catalog.limits.pageSize,offset:more?this.offset:0}),reply=await session.request('/notes/entries?'+params,{etag:this.catalog.snapshot});if(!current())return;
        this.offset=reply.data.nextOffset;incoming=reply.data.items.filter(entry=>notesEntryVisible(entry,this.catalog.settings)).map(entry=>({entry}));
      }else{
        const params=new URLSearchParams({q:query,limit:50});if(more)params.set('cursor',this.cursor);
        const reply=await session.request('/notes/search?'+params);if(!current())return;this.cursor=reply.data.nextCursor;incoming=reply.data.items;
      }
      this.items=more?[...this.items,...incoming]:incoming;this.message.textContent=t(this.items.length||this.cursor||this.offset!==undefined?'notes.search_results':'notes.search_none');this.paint();
      if(focusedMore&&(document.activeElement===this.more||document.activeElement===document.body))this.results.querySelectorAll('button')[previous]?.focus();
    }catch(error){if(!current())return;if(error.status===401){this.callbacks.onUnauthorized(error);return;}this.items=[];this.cursor=null;this.offset=undefined;this.failed=error;this.message.textContent=t(error.status===412?'notes.search_changed':error.status===422?'notes.search_invalid':'notes.search_unavailable');this.paint();retryFocus=focusedMore&&(document.activeElement===this.more||document.activeElement===document.body);}
    finally{if(current()){this.busy=false;this.controls();if(retryFocus)this.retry.focus();}}
  }
  async rebuildIndex(){
    if(!this.session||!this.writable||this.busy)return;const session=this.session,generation=++this.generation;this.busy=true;this.controls();
    try{await session.request('/notes/search/rebuild',{method:'POST'});if(session===this.session&&generation===this.generation){this.message.textContent=t('notes.search_preparing');this.failed=null;}}
    catch(error){if(session===this.session&&generation===this.generation){if(error.status===401){this.callbacks.onUnauthorized(error);return;}this.failed=error;this.message.textContent=t('notes.search_unavailable');}}
    finally{if(session===this.session&&generation===this.generation){this.busy=false;this.controls();}}
  }
  paint(){
    const active=this.results.contains(document.activeElement)?document.activeElement.closest('[data-search-note]')?.dataset.searchNote:null;this.results.replaceChildren();
    const session=this.session,generation=this.generation;
    for(const {entry,excerpt}of this.items){
      const item=node('li'),pick=button('',()=>pick.dispatchEvent(new CustomEvent(vaultNoteNavigation,{bubbles:true,cancelable:true,detail:{entry,popup:['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind),searchFolder:entry.kind==='FOLDER',originSelector:`#notes-search-results [data-search-note="${entry.id}"]`,originElement:pick,guards:[()=>session===this.session&&generation===this.generation],leave:[]}})));
      pick.dataset.searchNote=entry.id;pick.className='btn btn-outline-secondary notes-search-result';const title=node('span',undefined,'notes-search-result-title');title.append(icon(entry.kind==='FOLDER'?'folder':entry.kind==='EVENT'?'calendar-event':['DAY','WEEK'].includes(entry.kind)?'journal-text':'file-earmark-text'),node('span',noteTitle(entry,entry.path)));pick.append(title,node('small',entry.path,'notes-search-path'));
      if(excerpt){const text=node('span',undefined,'notes-search-excerpt');let offset=0;for(const range of excerpt.highlights){text.append(document.createTextNode(excerpt.text.slice(offset,range.start)),node('mark',excerpt.text.slice(range.start,range.end)));offset=range.end;}text.append(document.createTextNode(excerpt.text.slice(offset)));pick.append(text);}
      item.append(pick);this.results.append(item);
    }
    if(active)this.results.querySelector(`[data-search-note="${CSS.escape(active)}"]`)?.focus({preventScroll:true});this.controls();
  }
}

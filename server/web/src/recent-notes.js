import {t,getLocale} from './i18n.js';
import {dateInZone,instantText} from './model.js';
import {vaultNoteNavigation} from './vault-editor-resources.js';
import {noteTitle} from './note-title.js';
import {icon} from './icons.js';
import {noteIcon} from './note-presentation.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};

/** Metadata only; opening a document uses the shared unsaved-draft navigation guard. */
export class RecentNotes {
  constructor(root,callbacks,{section=root}={}){
    this.root=root;this.section=section;this.callbacks=callbacks;this.generation=0;this.visible=false;this.items=[];
    document.addEventListener('timekeeper:notes-changed',()=>void this.refresh(true));
  }
  reset(){this.hide();this.scope=null;this.items=[];this.page=null;this.extent=0;this.error=null;this.list=this.more=this.rowsKey=null;this.root.replaceChildren();this.root.removeAttribute('aria-busy');}
  hide(){this.visible=false;this.key=null;this.busy=false;this.loadingMore=false;++this.generation;clearInterval(this.timer);this.timer=null;}
  show(host,id){
    if(host&&this.section.parentElement!==host)host.append(this.section);
    if(id){
      this.root.id=id;const heading=this.section.querySelector('h3');
      if(heading){heading.id=id+'-title';this.section.setAttribute('aria-labelledby',heading.id);}
    }
    this.visible=true;if(!this.timer)this.timer=setInterval(()=>void this.refresh(),30000);void this.refresh();
  }
  update(scope,zone,revision){
    if(scope!==this.scope){const visible=this.visible;this.reset();this.visible=visible;this.scope=scope;}
    this.zone=zone;this.revision=revision;if(this.visible)this.show();
  }
  async refresh(force=false){
    if(!this.visible||!this.scope||!this.zone)return;
    const key=[dateInZone(Date.now(),this.zone),this.zone,this.revision].join('|');
    if(!force&&this.key===key)return;this.key=key;
    await this.load();
  }
  async load(more=false){
    if(!this.visible||!this.scope||more&&(this.loadingMore||this.page?.nextOffset===undefined))return;
    const generation=++this.generation,scope=this.scope,previous=this.page;
    const active=()=>this.visible&&scope===this.scope&&generation===this.generation;
    this.extent=more?this.page.nextOffset+(this.page.items.length||20):Math.max(this.extent||0,this.items.length);
    this.busy=true;this.loadingMore=more;this.error=null;this.paint();
    try{
      const query=new URLSearchParams(),options={};
      if(more){query.set('offset',this.page.nextOffset);options.etag=this.page.snapshot;}
      let reply=await scope.request('/notes/recent?'+query,options);if(!active())return;
      let items=reply.data.items;
      if(!more){
        const window=page=>JSON.stringify([page.days,page.zoneId,page.fromDate,page.toDate]);
        if(previous&&window(previous)!==window(reply.data))this.extent=items.length;
        // Metadata capture must not collapse a list the owner has expanded.
        // Rebuild its loaded extent atomically from a single fresh snapshot.
        while(items.length<this.extent&&reply.data.nextOffset!==undefined){
          reply=await scope.request('/notes/recent?'+new URLSearchParams({offset:reply.data.nextOffset}),{etag:reply.data.snapshot});if(!active())return;
          items=[...items,...reply.data.items];
        }
      }
      this.items=more?[...this.items,...items]:items;this.page=reply.data;this.extent=Math.max(this.extent,this.items.length);
    }catch(error){
      if(!this.visible||scope!==this.scope||generation!==this.generation)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      if(more&&error.status===412){this.busy=false;await this.refresh(true);return;}
      this.error=error.message;this.key=null;
    }finally{
      if(this.visible&&scope===this.scope&&generation===this.generation){this.busy=false;this.loadingMore=false;this.paint();}
    }
  }
  paint(){
    const active=this.root.contains(document.activeElement)?document.activeElement.dataset.recentNote:null;
    this.root.setAttribute('aria-busy',String(this.busy));
    if(this.list?.parentElement!==this.root){
      this.list=node('ul',undefined,'today-list today-recent-list');this.more=node('button',undefined,'btn btn-outline-secondary mt-2');this.more.type='button';
      this.more.addEventListener('click',()=>this.error?void this.refresh(true):void this.load(true));this.rowsKey=null;this.root.replaceChildren(this.list,this.more);
    }
    const key=JSON.stringify([this.items,this.zone,getLocale()]);
    if(key!==this.rowsKey){
      this.rowsKey=key;const rows=[],scope=this.scope;
      for(const entry of this.items){
        const item=node('li'),action=node('button',undefined,'btn btn-outline-secondary today-recent-note');action.type='button';action.dataset.recentNote=entry.id;
        const key=entry.kind==='EVENT'?'notes.type_event':['DAY','WEEK'].includes(entry.kind)?'notes.type_journal':'notes.type_ordinary';
        const title=node('span',undefined,'today-recent-note-title');
        title.append(icon(noteIcon(entry)),node('span',t(key)+': ','visually-hidden'),node('span',noteTitle(entry),'today-recent-note-label'));action.append(title);
        action.title=t(key)+'\n'+(entry.kind==='EVENT'?noteTitle(entry):entry.path)+'\n'+instantText(entry.modifiedAt,this.zone);
        action.addEventListener('click',()=>{
          const generation=action.recentGeneration,originSelector=(this.root.id?'#'+CSS.escape(this.root.id)+' ':'')+`[data-recent-note="${entry.id}"]`;
          action.dispatchEvent(new CustomEvent(vaultNoteNavigation,{bubbles:true,cancelable:true,detail:{entry,popup:true,originSelector,originElement:action,guards:[()=>this.visible&&scope===this.scope&&generation===this.generation],leave:[]}}));
        });item.append(action);rows.push(item);
      }
      this.list.replaceChildren(...rows);
    }
    this.list.hidden=!this.items.length;
    for(const action of this.list.querySelectorAll('[data-recent-note]'))action.recentGeneration=this.generation;
    this.more.hidden=!this.error&&this.page?.nextOffset===undefined;this.more.textContent=t(this.error?'ui.retry':'today.more_notes');this.more.disabled=this.busy&&this.loadingMore;
    for(const status of this.root.querySelectorAll(':scope > p'))status.remove();
    if(!this.items.length&&!this.busy&&!this.error)this.root.append(node('p',t('today.no_recent_notes'),'hint'));
    if(this.busy)this.root.append(node('p',t('calendar.loading'),'hint'));
    if(this.error){const error=node('p',this.error,'alert alert-warning');error.setAttribute('role','status');this.root.append(error);}
    if(active)[...this.root.querySelectorAll('[data-recent-note]')].find(value=>value.dataset.recentNote===active)?.focus({preventScroll:true});
  }
}

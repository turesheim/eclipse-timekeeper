import {t} from './i18n.js';
import {dateInZone,instantText} from './model.js';
import {vaultNoteNavigation} from './vault-editor-resources.js';
import {noteTitle} from './note-title.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};

/** Metadata only; opening a document uses the shared unsaved-draft navigation guard. */
export class RecentNotes {
  constructor(root,callbacks){
    this.root=root;this.callbacks=callbacks;this.generation=0;this.visible=false;this.items=[];
    document.addEventListener('timekeeper:notes-changed',()=>void this.refresh(true));
  }
  reset(){this.hide();this.scope=null;this.items=[];this.page=null;this.error=null;this.root.replaceChildren();this.root.removeAttribute('aria-busy');}
  hide(){this.visible=false;this.key=null;this.busy=false;++this.generation;}
  show(){this.visible=true;void this.refresh();}
  update(scope,zone,revision){
    if(scope!==this.scope){const visible=this.visible;this.reset();this.visible=visible;this.scope=scope;}
    this.zone=zone;this.revision=revision;void this.refresh();
  }
  async refresh(force=false){
    if(!this.visible||!this.scope||!this.zone)return;
    const key=[dateInZone(Date.now(),this.zone),this.zone,this.revision].join('|');
    if(!force&&this.key===key)return;this.key=key;
    await this.load();
  }
  async load(more=false){
    if(!this.visible||!this.scope||more&&(this.busy||this.page?.nextOffset===undefined))return;
    const generation=++this.generation,scope=this.scope;
    this.busy=true;this.error=null;this.paint();
    try{
      const query=new URLSearchParams(),options={};
      if(more){query.set('offset',this.page.nextOffset);options.etag=this.page.snapshot;}
      const reply=await scope.request('/notes/recent?'+query,options);
      if(!this.visible||scope!==this.scope||generation!==this.generation)return;
      this.items=more?[...this.items,...reply.data.items]:reply.data.items;this.page=reply.data;
    }catch(error){
      if(!this.visible||scope!==this.scope||generation!==this.generation)return;
      if(error.status===401){this.callbacks.onUnauthorized(error);return;}
      if(more&&error.status===412){this.busy=false;await this.refresh(true);return;}
      this.error=error.message;this.key=null;
    }finally{
      if(this.visible&&scope===this.scope&&generation===this.generation){this.busy=false;this.paint();}
    }
  }
  paint(){
    const active=this.root.contains(document.activeElement)?document.activeElement.dataset.recentNote:null;
    this.root.replaceChildren();this.root.setAttribute('aria-busy',String(this.busy));
    if(this.items.length){
      const list=node('ul',undefined,'today-list today-recent-list'),scope=this.scope,generation=this.generation;
      for(const entry of this.items){
        const item=node('li'),action=node('button',undefined,'btn btn-outline-secondary today-recent-note');action.type='button';action.dataset.recentNote=entry.id;
        action.append(node('strong',noteTitle(entry)),node('span',entry.path,'hint'));
        action.title=entry.path+'\n'+instantText(entry.modifiedAt,this.zone);
        action.addEventListener('click',()=>action.dispatchEvent(new CustomEvent(vaultNoteNavigation,{bubbles:true,cancelable:true,detail:{entry,guards:[()=>this.visible&&scope===this.scope&&generation===this.generation],leave:[]}})));
        item.append(action);list.append(item);
      }
      this.root.append(list);
    }else if(!this.busy&&!this.error)this.root.append(node('p',t('today.no_recent_notes'),'hint'));
    if(this.busy)this.root.append(node('p',t('calendar.loading'),'hint'));
    if(this.error){const error=node('p',this.error,'alert alert-warning');error.setAttribute('role','status');this.root.append(error);}
    if(this.error||this.page?.nextOffset!==undefined){
      const more=node('button',t(this.error?'ui.retry':'today.more_notes'),'btn btn-outline-secondary mt-2');more.type='button';more.disabled=this.busy;
      more.addEventListener('click',()=>this.error?void this.refresh(true):void this.load(true));this.root.append(more);
    }
    if(active)[...this.root.querySelectorAll('[data-recent-note]')].find(value=>value.dataset.recentNote===active)?.focus({preventScroll:true});
  }
}

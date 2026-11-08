import {icon} from './icons.js';
import {t} from './i18n.js';

/** Page metadata has its own request lifetime and never touches the body draft. */
export class NoteBookmark {
  constructor(root,options){
    Object.assign(this,{root,options,generation:0});
    this.button=document.createElement('button');this.button.type='button';this.button.className='btn btn-outline-secondary icon-button note-bookmark';
    this.button.addEventListener('click',()=>void this.toggle());root.classList.add('has-note-bookmark');root.append(this.button);this.paint();
  }
  current(){return this.options.current?.();}
  same(a,b){return !!a&&!!b&&a.session===b.session&&a.sourceType===b.sourceType&&a.sourceKey===b.sourceKey;}
  active(context,generation){return generation===this.generation&&this.same(context,this.current());}
  sync(){
    const context=this.current();
    if(!this.same(context,this.context)){
      this.abort?.abort();++this.generation;this.context=context;this.data=null;this.etag=null;this.busy=false;this.failed=false;
      if(context?.session)void this.load();
    }
    this.paint();
  }
  path(context){return '/notes/bookmark?'+new URLSearchParams({sourceType:context.sourceType,sourceKey:context.sourceKey});}
  paint(){
    const context=this.current(),marked=!!this.data?.bookmarked,label=t(this.failed?'ui.retry':marked?'notes.bookmark_remove':'notes.bookmark_add');
    this.button.hidden=!context||!context.writable&&!marked;
    this.button.disabled=!context?.writable||this.busy||!this.etag&&!this.failed;
    this.button.classList.toggle('is-bookmarked',marked);this.button.setAttribute('aria-pressed',String(marked));this.button.setAttribute('aria-label',label);this.button.title=label;
    this.button.replaceChildren(icon(marked?'bookmark':'bookmark-plus'));
    if(marked)this.button.append(icon('bookmark-x'));
  }
  async load(){
    const context=this.context;if(!context?.session)return;const generation=this.generation;
    this.abort?.abort();this.abort=new AbortController();this.busy=true;this.paint();
    try{const reply=await context.session.request(this.path(context),{signal:this.abort.signal});if(this.active(context,generation)){this.data=reply.data;this.etag=reply.etag;this.failed=false;}}
    catch(error){if(this.active(context,generation)&&error.name!=='AbortError'){this.failed=true;this.options.error?.(error);}}
    finally{if(this.active(context,generation)){this.busy=false;this.paint();}}
  }
  refresh(){this.sync();if(this.context&&!this.busy)void this.load();}
  async toggle(){
    this.sync();if(this.button.disabled)return;if(this.failed){await this.load();return;}const context=this.context,generation=this.generation;
    this.busy=true;this.paint();this.abort=new AbortController();
    try{
      const reply=await context.session.request(this.path(context),{method:'PUT',body:{bookmarked:!this.data.bookmarked},etag:this.etag,signal:this.abort.signal});
      if(!this.active(context,generation))return;this.data=reply.data;this.etag=reply.etag;this.options.changed?.(reply.data,reply.etag);
    }catch(error){
      if(this.active(context,generation)&&error.name!=='AbortError'){
        this.options.error?.(error);
        // A rejected or uncertain write is resolved by reading the current
        // metadata. A second click is always a new, explicit decision.
        await this.load();
      }
    }finally{if(this.active(context,generation)){this.busy=false;this.paint();}}
  }
}

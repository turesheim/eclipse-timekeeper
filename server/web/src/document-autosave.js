/** Serialize writes and drain newer edits before allowing document navigation. */
export class DocumentAutosave {
  constructor({dirty,ready,save},delay=1000,interval=5000){Object.assign(this,{dirty,ready,save,delay,interval,generation:0});}
  cancelTimers(){clearTimeout(this.timer);clearTimeout(this.deadline);this.timer=this.deadline=null;}
  changed(){
    clearTimeout(this.timer);
    if(!this.dirty()){this.cancelTimers();return;}
    this.deadline??=setTimeout(()=>void this.flush(),this.interval);
    this.timer=setTimeout(()=>void this.flush(),this.delay);
  }
  flush(options={}){
    this.cancelTimers();if(this.pending)return this.pending;
    const generation=this.generation;
    return this.track(Promise.resolve().then(()=>this.drain(generation,options)));
  }
  async drain(generation,options={}){
    while(generation===this.generation&&this.dirty()){
      if(!this.ready()||!await this.save(options))return false;
    }
    return generation===this.generation;
  }
  /** Queue metadata between prose writes; navigation also waits for newer edits. */
  exclusive(operation){
    this.cancelTimers();const generation=this.generation,previous=this.pending;
    return this.track(Promise.resolve(previous).then(async success=>{
      if(success===false||!await this.drain(generation)||!this.ready())return false;
      if(await operation()===false||generation!==this.generation)return false;
      return this.drain(generation);
    }));
  }
  track(work){
    const pending=work.finally(()=>{if(this.pending===pending){this.pending=null;if(!this.dirty())this.cancelTimers();}});
    this.pending=pending;return pending;
  }
  reset(){this.cancelTimers();++this.generation;this.pending=null;}
}
export function bindDocumentAutosave(root,autosave,controls=[]){
  const roots=[root,...controls.filter(Boolean)];
  for(const target of roots){
    target.addEventListener('keydown',event=>{if(!event.isComposing&&(event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='s'){event.preventDefault();void autosave.flush();}});
    target.addEventListener('focusout',event=>{if(event.relatedTarget&&!roots.some(value=>value.contains(event.relatedTarget)))void autosave.flush();});
  }
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')void autosave.flush();});
  window.addEventListener('beforeunload',()=>void autosave.flush({keepalive:true}));
}

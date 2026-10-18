import {iconButton} from './icons.js';

/** One visible, session-local Undo offer. No work snapshots or credentials enter browser storage. */
export class UndoNotice {
  constructor(root,callbacks) {
    this.root=root;this.callbacks=callbacks;this.message=root.querySelector('[role=status]');this.button=root.querySelector('[data-undo]');
    this.dismiss=root.querySelector('[data-dismiss-undo]');iconButton(this.dismiss,'close','Dismiss undo');
    this.button.addEventListener('pointerdown',event=>event.preventDefault());
    this.button.addEventListener('click',()=>void this.apply());this.dismiss.addEventListener('click',()=>this.clear());
    this.timer=setInterval(()=>this.paint(),500);
  }
  offer(info,api,label='Work session updated') {
    if(!info)return;
    this.entry={...info,api,label,key:crypto.randomUUID(),until:performance.now()+info.seconds*1000};this.paint();
  }
  clear() {this.entry=null;this.root.hidden=true;this.message.textContent='';}
  paint() {
    const entry=this.entry;if(!entry)return;
    if(performance.now()>=entry.until&&!entry.busy&&!entry.uncertain){this.clear();return;}
    this.root.hidden=false;
    this.message.textContent=entry.message || entry.label;
    this.button.textContent=entry.uncertain ? 'Retry undo':'Undo';
    this.button.hidden=!!entry.done;this.button.disabled=!!entry.busy||!this.callbacks.available(entry.api);
    this.dismiss.disabled=!!entry.busy;
  }
  async apply() {
    const entry=this.entry;if(!entry||entry.busy||entry.done||!this.callbacks.available(entry.api))return;
    entry.busy=true;entry.message='Undoing work-session change…';this.paint();
    try {
      await entry.api.request('/undo/'+entry.id,{method:'POST',body:{},key:entry.key});
      if(this.entry!==entry)return;
      entry.done=true;entry.uncertain=false;entry.message='Work-session change undone.';
      entry.until=performance.now()+5000;await this.callbacks.changed(entry.api);
    } catch(error) {
      if(this.entry!==entry)return;
      if(error.status===401){this.callbacks.unauthorized(error);return;}
      entry.uncertain=error.uncertain;
      entry.message=error.uncertain ? 'Undo could not be confirmed. Retry safely to check the same request.' : error.status===404 ? 'Undo is no longer available.' : error.message;
      entry.done=!error.uncertain;entry.until=performance.now()+10000;
    } finally {if(this.entry===entry){entry.busy=false;this.paint();}}
  }
}

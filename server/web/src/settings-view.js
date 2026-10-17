import {button} from './account-ui.js';

/** Shared account/admin write lifecycle: stale views never regain authority. */
export function settingsView({root, session, account, valid, feedback, changed, signOut, refreshProfile, navigate, reload, forbidden}) {
  return {
    root, session, account, valid, canWrite:!!account.permissions?.includes('write'),
    dirty:new Set(), cleanup:new Set(), busy:false, pending:0, uncertain:false,
    clean(form) {if(valid()) this.dirty.delete(form);},
    changed(value) {if(valid()) {this.account={...this.account,...value};changed?.(value);}},
    signOut:message=>{if(valid()) signOut(message);},
    refreshProfile:async profile=>{if(valid()) await refreshProfile?.(profile);},
    navigate:next=>{if(valid()) navigate(next);},
    async run(work, {submit, feedback:target=feedback, write=false, canSubmit=()=>true}={}) {
      if(!valid() || this.busy || (write && (!this.canWrite || this.uncertain || !canSubmit()))) return;
      target.replaceChildren();if(submit) submit.disabled=true;
      if(write) this.busy=true;++this.pending;this.root.setAttribute('aria-busy','true');
      try {await work();}
      catch(error) {
        if(!valid()) return;
        if(error.status===401) {signOut(error.message);return;}
        if(error.status===403 && forbidden) {forbidden();return;}
        target.textContent=write && error.uncertain ? 'The outcome is unknown. Review current records before making another change.' : error.code?.startsWith('IMPORT_') ? error.message : [412,409].includes(error.status) ? 'These records changed or conflict with this change. Your draft is kept. Review current values before saving again.' : error.message;
        if((write && error.uncertain) || [412,409].includes(error.status)) {
          if(error.uncertain) this.uncertain=true;
          target.append(' ',button('Reload current values',()=>{if(valid()) reload();}));
        }
      } finally {
        if(valid()) {if(write) this.busy=false;--this.pending;this.root.setAttribute('aria-busy',String(this.pending>0));if(submit) submit.disabled=!this.canWrite || this.uncertain || !canSubmit();}
      }
    }
  };
}

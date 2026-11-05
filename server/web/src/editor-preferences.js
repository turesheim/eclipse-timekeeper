const cursors=new Set(['default','line','underscore','solid']);
export const cursorPreferenceChanged='timekeeper:editor-cursor-change';
let session,generation=0,version=-1,unauthorized;
const changed=()=>window.dispatchEvent(new Event(cursorPreferenceChanged));

/** Account preferences stay on the server; replies from an old session are ignored. */
export const editorPreferences={
  connect(scope,onUnauthorized){if(scope===session)return;this.reset();session=scope;unauthorized=onUnauthorized;void this.refresh();},
  reset(){session=null;unauthorized=null;version=-1;++generation;delete document.documentElement.dataset.editorCursor;changed();},
  async refresh(){
    if(!session)return;const scope=session,request=++generation;
    try{
      const reply=await scope.request('/notes/settings');
      if(scope!==session||request!==generation||reply.data.ownerId!==scope.owner||reply.data.version<version)return;
      version=reply.data.version;document.documentElement.dataset.editorCursor=cursors.has(reply.data.editorCursor)?reply.data.editorCursor:'default';changed();
    }catch(error){if(scope===session&&request===generation&&error.status===401)unauthorized?.(error);}
  }
};
if(typeof document!=='undefined')document.addEventListener('timekeeper:notes-changed',()=>void editorPreferences.refresh());

import {instantText} from './model.js';
import {node,button,field,passwordField,section,form,table} from './account-ui.js';
import {PasskeyCeremony,passkeysSupported,canceledPasskey} from './webauthn.js';

export async function renderPasskeys(view,zone) {
  let data;
  try {data=(await view.session.request('/auth/passkeys')).data;}
  catch(error) {
    if(!view.valid()) return;
    if(error.code !== 'PASSKEY_ACCOUNT_SIGN_IN_REQUIRED') throw error;
    const card=section(view,'Passkeys');card.append(node('p',error.message,'alert alert-info'));
    const signIn=button('Sign in with your account',()=>void view.run(async()=>{
      await view.session.request('/auth/logout',{method:'POST'});if(view.valid()) view.signOut('Sign in with your account password or a passkey to manage passkeys.');
    },{submit:signIn}));card.append(signIn);return;
  }
  if(!view.valid()) return;
  const card=section(view,'Passkeys'),feedback=node('p','','form-feedback'),rows=node('div'),actions=node('div');feedback.setAttribute('role','status');
  card.append(node('p','Use your browser or password manager to sign in with a fingerprint, device PIN or security key. Your password remains available.'));
  if(!data.enabled) card.append(node('p','Passkeys require a configured HTTPS hostname and enabled server support. You can continue using your username and password.','alert alert-info'));
  else if(!passkeysSupported()) card.append(node('p','This browser cannot add or use passkeys. You can still rename or remove one using your password.','alert alert-info'));
  const request=async(path,body)=>(await view.session.request('/auth/passkeys/'+path,{method:'POST',body})).data;
  const ceremony=new PasskeyCeremony(request);let operation=null,actionForm=null,readGeneration=0;
  const dispose=()=>{if(operation) operation.canceled=true;ceremony.cancel();for(const manager of managers) manager.clear();};view.cleanup.add(dispose);
  const hasKey=()=>data.items.some(key=>key.usableHere);
  const managers=[];
  async function refresh() {
    const current=++readGeneration,reply=await view.session.request('/auth/passkeys');
    if(!view.valid() || current!==readGeneration) return;
    data=reply.data;paint();for(const manager of managers) manager.update();
  }
  const reload=button('Refresh passkey list',()=>void view.run(refresh,{submit:reload,feedback}));card.append(reload,feedback,rows);
  function proofFields(target) {
    const method=field(target,'Confirm with','password',{choices:[['password','Current password'],['passkey','Existing passkey']]}),password=passwordField(target,'Current password',true);
    const update=()=>{
      method.querySelector('option[value="passkey"]').disabled=!hasKey() || !passkeysSupported();
      if(method.value==='passkey' && (!hasKey() || !passkeysSupported())) method.value='password';
      const usePassword=method.value==='password';password.disabled=!usePassword;password.required=usePassword;password.closest('.account-field').hidden=!usePassword;
    };
    method.addEventListener('change',()=>{password.value='';update();});update();
    const result={target,update,clear:()=>{password.value='';},async value(action,stage) {
      if(method.value==='password') {const value=password.value;password.value='';return {currentPassword:value};}
      const proof=await ceremony.run('proof/options','proof/finish',action,{valid:()=>view.valid() && !operation?.canceled,stage});
      return {proof:proof.proof};
    }};managers.push(result);return result;
  }
  async function change(cancel,feedback,work) {
    ++readGeneration;const current={canceled:false};operation=current;
    const stage=value=>{
      if(!view.valid() || operation!==current) return;
      cancel.hidden=false;cancel.disabled=value==='finish';
      feedback.textContent=value==='prompt' ? 'Complete the request in your browser or password manager.' : value==='finish' ? 'Saving…' : 'Preparing passkey request…';
    };
    cancel.onclick=()=>{if(cancel.disabled || operation!==current) return;current.canceled=true;ceremony.cancel();feedback.textContent='Passkey request canceled.';};
    try {await work(stage,current);}
    catch(error) {
      if(!view.valid()) return;
      if(canceledPasskey(error) || current.canceled) {feedback.textContent='Passkey request canceled. Your other passkeys are unchanged.';return;}
      throw error;
    } finally {if(operation===current) operation=null;if(view.valid()) cancel.hidden=true;}
  }
  function cancelControl(target) {const value=button('Cancel passkey request',()=>{});value.hidden=true;target.append(value);return value;}
  const add=form(view,'Add passkey','Add passkey',async()=>{
    await change(addCancel,add.feedback,async(stage,current)=>{
      const name=addName.value.trim(),proof=await addProof.value({action:'ADD'},stage);
      if(!view.valid() || current.canceled) return;
      await ceremony.run('register/options','register/finish',{name,...proof},{create:true,valid:()=>view.valid() && !current.canceled,stage});
      if(!view.valid() || current.canceled) return;
      addName.value='';view.clean(add.value);add.feedback.textContent='Passkey added.';await refresh();
    });
  },{canSubmit:()=>data.enabled && passkeysSupported()});
  const addName=field(add.value,'Passkey name','',{maxLength:200});const addProof=proofFields(add.value),addCancel=cancelControl(add.value);add.finish();
  view.root.append(actions);
  function discardAction() {
    for(let index=managers.length-1;index>=0;index--) if(managers[index].target===actionForm) {managers[index].clear();managers.splice(index,1);}
    if(actionForm) view.clean(actionForm);actionForm=null;actions.replaceChildren();
  }
  function closeAction(ask=true) {
    if(view.busy) return false;
    if(actionForm && ask && view.dirty.has(actionForm) && !window.confirm('Discard unsaved passkey changes?')) return false;
    discardAction();return true;
  }
  function edit(key,remove=false) {
    if(view.busy || !closeAction()) return;
    const title=remove ? `Remove “${key.name}”?` : `Rename “${key.name}”`;
    const editor=form(view,title,remove ? 'Remove passkey':'Save name',async()=>{
      if(remove) await change(cancel,editor.feedback,async(stage,current)=>{
        const proof=await evidence.value({action:'REMOVE',id:key.id,etag:key.etag},stage);
        if(!view.valid() || current.canceled) return;
        stage('finish');
        const reply=await view.session.request('/auth/passkeys/'+key.id,{method:'DELETE',etag:key.etag,body:proof});
        if(!view.valid()) return;
        view.clean(editor.value);
        if(reply.data.signedOut) {view.signOut('Passkey removed. Its sessions were signed out. Sign in with your password or another passkey.');return;}
        feedback.textContent='Passkey removed. Delete it from your password manager separately if needed.';discardAction();await refresh();
      });
      else {
        ++readGeneration;await view.session.request('/auth/passkeys/'+key.id,{method:'PUT',etag:key.etag,body:{name:name.value.trim()}});
        if(!view.valid()) return;view.clean(editor.value);feedback.textContent='Passkey renamed.';discardAction();await refresh();
      }
    },{canSubmit:()=>!remove || data.enabled});
    let name,evidence,cancel;
    if(remove) {
      editor.value.append(node('p','Removing this passkey signs out sessions created with it. It does not remove the credential from your password manager.','alert alert-warning'));
      evidence=proofFields(editor.value);cancel=cancelControl(editor.value);
    } else name=field(editor.value,'Passkey name',key.name,{maxLength:200});
    editor.finish();editor.value.append(button('Cancel',()=>closeAction()));
    actionForm=editor.value;actions.append(editor.value.closest('section'));(name || editor.value.querySelector('input,select'))?.focus();
  }
  function paint() {
    rows.replaceChildren();
    if(!data.items.length) {rows.append(node('p','No passkeys added.'));return;}
    rows.append(table(['Name','Added','Last used','Availability','Actions'],data.items.map(key=>{
      const controls=node('div',undefined,'d-flex flex-wrap gap-2');
      const rename=button('Rename',()=>edit(key)),remove=button('Remove',()=>edit(key,true),'btn btn-outline-danger');
      rename.setAttribute('aria-label',`Rename ${key.name}`);remove.setAttribute('aria-label',`Remove ${key.name}`);
      rename.disabled=!view.canWrite;remove.disabled=!view.canWrite || !data.enabled;controls.append(rename,remove);
      return [key.name,instantText(key.createdAt,zone),key.lastUsedAt ? instantText(key.lastUsedAt,zone):'Never used',!data.enabled ? 'Passkeys disabled':key.usableHere ? 'Ready to use':`Registered for ${key.rpId}; add a replacement here`,controls];
    })));
  }
  paint();
}

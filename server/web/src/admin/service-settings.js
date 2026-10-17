import {node,form,field,passwordField,section,table,button} from '/web/account-ui.js';

/** Passwords stay in this view only and are never part of the redacted configuration. */
export async function renderServiceSettings(view) {
  let loaded=await view.session.request('/admin/settings');if(!view.valid()) return;
  const status=section(view,'Service status'),pending=node('p'),values=node('div'),details=node('details');details.append(node('summary','Saved and effective settings'),values);status.append(pending,details);
  function describe(value) {
    return [value.publicOrigin || 'Not configured',value.passkeysEnabled ? 'Enabled':'Disabled',value.passkeyRpId,
      value.emailRecoveryEnabled ? 'Enabled':'Disabled',value.smtpHost,String(value.smtpPort),value.smtpFrom,value.smtpUsername || 'None',
      value.smtpTls ? 'TLS on connect':value.smtpStartTls==='REQUIRED' ? 'STARTTLS required':value.smtpStartTls==='OPTIONAL' ? 'STARTTLS when offered':'Plain connection'];
  }
  function paint() {
    const data=loaded.data,saved=describe(data.saved),effective=describe(data.effective);
    pending.textContent=data.restartRequired ? 'Changes are saved. Restart Timekeeper on every server instance to apply them. Browser sessions will be signed out.' : 'Saved settings are active.';
    pending.className=data.restartRequired ? 'alert alert-info':'hint';pending.setAttribute('role','status');
    const labels=['Public HTTPS address','Passkey sign-in','Passkey RP ID','Email recovery','SMTP host','SMTP port','Sender address','SMTP username','SMTP encryption'];
    effective[1]=data.passkeysEnabled ? 'Enabled':data.effective.passkeysEnabled ? 'Unavailable for this address':'Disabled';
    values.replaceChildren(table(['Setting','Saved','Effective'],[...labels.map((label,index)=>[label,saved[index],effective[index]]),['SMTP password',data.smtpPasswordConfigured ? 'Configured':'Not configured',data.effectiveSmtpPasswordConfigured ? 'Configured':'Not configured']]));
    values.append(node('p',`Database: ${data.databaseType==='postgresql' ? 'PostgreSQL':'H2'} · Schema ${data.schemaVersion}`,'hint'));
    if(!data.canEdit) status.append(node('p','Sign in with your account password or a passkey to change service settings. An API-token session cannot authorize these changes.','alert alert-info'));
  }
  paint();
  if(!loaded.data.canEdit) {
    status.append(button('Sign in with an account',()=>void view.run(async()=>{
      await view.session.request('/auth/logout',{method:'POST'});if(view.valid()) location.assign('/web/#administration');
    })));return;
  }
  const editor=form(view,'General settings','Save settings',async({value,feedback})=>{
    if(clearPassword.checked && smtpPassword.value) throw new Error('Choose either a replacement SMTP password or clear the saved password.');
    const publicOrigin=origin.value.trim() || null,rpId=rp.value.trim() || 'AUTO';
    if(publicOrigin && rpId!=='AUTO') {
      let host;try {host=new URL(publicOrigin).hostname;}catch {throw new Error('Enter a valid public HTTPS address.');}
      if(host!==rpId) throw new Error('The passkey RP ID must match the public hostname. Use AUTO to follow that hostname.');
    }
    const body={publicOrigin,passkeysEnabled:passkeys.checked,passkeyRpId:rpId,emailRecoveryEnabled:email.checked,
      smtpHost:host.value.trim(),smtpPort:Number(port.value),smtpFrom:from.value.trim(),smtpUsername:username.value,smtpTls:encryption.value==='TLS',
      smtpStartTls:encryption.value==='TLS' || encryption.value==='DISABLED' ? 'DISABLED':encryption.value,
      smtpPasswordAction:clearPassword.checked ? 'CLEAR':smtpPassword.value ? 'REPLACE':'PRESERVE',currentPassword:proof.value,
      ...(smtpPassword.value ? {smtpPassword:smtpPassword.value}: {})};
    const reply=await view.session.request('/admin/settings',{method:'PUT',etag:loaded.etag,body});if(!view.valid()) return;
    loaded=reply;proof.value='';smtpPassword.value='';clearPassword.checked=false;view.clean(value);paint();
    secret.textContent=loaded.data.smtpPasswordConfigured ? 'An SMTP password is saved. Leave the password field blank to keep it.':'No SMTP password is saved.';
    feedback.textContent=loaded.data.restartRequired ? 'Settings saved. Restart Timekeeper to apply the changes.':'Settings are already active.';
  });
  const value=loaded.data.saved;
  const origin=field(editor.value,'Public HTTPS address',value.publicOrigin,{type:'url',required:false,maxLength:2048});origin.placeholder='https://timekeeper.example.org';
  const passkeys=field(editor.value,'Enable passkey sign-in',value.passkeysEnabled,{type:'checkbox'});
  const rp=field(editor.value,'Passkey RP ID',value.passkeyRpId,{required:false,maxLength:255});rp.placeholder='AUTO';
  const email=field(editor.value,'Enable recovery email',value.emailRecoveryEnabled,{type:'checkbox'});
  const host=field(editor.value,'SMTP host',value.smtpHost,{maxLength:255});
  const port=field(editor.value,'SMTP port',value.smtpPort,{type:'number'});port.min='1';port.max='65535';port.step='1';
  const from=field(editor.value,'Sender email address',value.smtpFrom,{type:'email',maxLength:320});
  const username=field(editor.value,'SMTP username',value.smtpUsername,{required:false,maxLength:255,autocomplete:'section-smtp username'});
  const encryption=field(editor.value,'SMTP encryption',value.smtpTls ? 'TLS':value.smtpStartTls,{choices:[['REQUIRED','STARTTLS required'],['TLS','TLS on connect'],['OPTIONAL','STARTTLS when offered'],['DISABLED','Plain connection']]});
  const secret=node('p',loaded.data.smtpPasswordConfigured ? 'An SMTP password is saved. Leave the password field blank to keep it.':'No SMTP password is saved.','hint');editor.value.append(secret);
  const smtpPassword=field(editor.value,'New SMTP password','',{type:'password',required:false,autocomplete:'section-smtp new-password',maxLength:1024});
  const clearPassword=field(editor.value,'Clear saved SMTP password',false,{type:'checkbox'});
  const proof=passwordField(editor.value,'Current administrator password',true);proof.autocomplete='section-administrator current-password';
  editor.value.append(node('p','Changes take effect after a coordinated restart. DNS, certificates and Tailscale forwarding are managed outside Timekeeper.','hint'));
  view.cleanup.add(()=>{smtpPassword.value='';proof.value='';});editor.finish();
}

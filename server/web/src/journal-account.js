import {t} from './i18n.js';
import {instantText} from './model.js';
import {node,button,field,passwordField,section,form,table} from './account-ui.js';

/** Private account setup. Only public connection profiles and explicit original copies are downloaded. */
export async function renderJournal(view,zone){
  let data=(await view.session.request('/journal')).data;if(!view.valid())return;
  view.root.classList.add('journal-account');view.cleanup.add(()=>view.root.classList.remove('journal-account'));
  let generation=0,actionGeneration=0,actionForm=null,settingsTag=data.settings.etag;
  const urls=new Set(),secrets=new Set(),feedback=node('p','','form-feedback');feedback.setAttribute('role','status');
  const title=section(view,t('journal.sync.title')),health=node('div'),devices=node('div'),items=node('div'),actionHome=node('div');
  title.append(node('p',t('journal.sync.direction')),node('p',t('journal.sync.authority'),'hint'));
  const refreshButton=button(t('journal.sync.refresh'),()=>void view.run(refresh,{submit:refreshButton,feedback}));
  const rescan=button(t('journal.sync.rescan'),()=>void view.run(async()=>{await view.session.request('/journal/rescan',{method:'POST'});await refresh();},{submit:rescan,feedback,write:true,canSubmit:()=>data.deployment.ready&&data.settings.enabled}));
  title.append(refreshButton,rescan,feedback,health);
  const setupCard=section(view,t('journal.sync.setup')),setupStatus=node('div'),connection=node('div'),setup=node('details');setup.append(node('summary',t('journal.sync.setup_steps')));setupCard.append(setupStatus,connection,setup);
  setup.append(node('p',t('journal.sync.key_local')));
  command(setup,'ssh-keygen -t ed25519 -f ~/.ssh/timekeeper_journal -C timekeeper-journal');
  setup.append(node('p',t('journal.sync.key_permissions')));command(setup,'chmod 600 ~/.ssh/timekeeper_journal\nssh-add ~/.ssh/timekeeper_journal\ncat ~/.ssh/timekeeper_journal.pub');
  setup.append(node('p',t('journal.sync.naming')),node('p',t('journal.sync.tools')),node('p',t('journal.sync.install')));
  const runner=node('a',t('journal.sync.runner'),'btn btn-outline-secondary');runner.href='/web/journal-client.py';runner.download='timekeeper-journal.py';
  const clientCommands=node('div'),guide=node('a',t('journal.sync.guide'));guide.href='https://github.com/turesheim/eclipse-timekeeper/blob/main/docs/operations/JOURNAL-SYNC.md';guide.target='_blank';guide.rel='noopener noreferrer';
  setup.append(runner,node('p',t('journal.sync.configure')),node('p',t('journal.sync.pin')),clientCommands,node('p',t('journal.sync.schedule')),guide,node('p',t('journal.sync.storage'),'hint'));
  let enabled;
  const settings=form(view,t('journal.sync.settings'),t('journal.sync.save_settings'),async()=>{
    const currentPassword=takePassword(settingsPassword);
    const reply=await view.session.request('/journal/settings',{method:'PUT',etag:settingsTag,body:{enabled:enabled.checked,currentPassword}});
    if(!view.valid())return;view.clean(settings.value);data=reply.data;settingsTag=data.settings.etag;settings.feedback.textContent=t('journal.sync.settings_saved');paint();
  },{canSubmit:()=>data.canManage&&(!enabled?.checked||data.deployment.ready)});
  enabled=field(settings.value,t('journal.sync.enabled'),data.settings.enabled,{type:'checkbox'});enabled.disabled=!data.canManage;
  const settingsPassword=secret(settings.value);settings.finish();
  const enrollment=form(view,t('journal.sync.enroll'),t('journal.sync.enroll_submit'),async()=>{
    if(!/^ssh-ed25519 [A-Za-z0-9+/]+={0,2}(?:[ \t]+[^\r\n]*)?$/.test(publicKey.value.trim()))throw new Error(t('journal.sync.public_only'));
    const body={label:label.value,publicKey:publicKey.value.trim(),currentPassword:takePassword(enrollPassword)};if(rotation.value)body.rotateDeviceId=rotation.value;
    await view.session.request('/journal/devices',{method:'POST',key:crypto.randomUUID(),body});if(!view.valid())return;
    view.clean(enrollment.value);label.value='';publicKey.value='';rotation.value='';enrollment.feedback.textContent=t('journal.sync.enrolled');await refresh();
  },{canSubmit:()=>data.canManage&&data.deployment.ready});
  const label=field(enrollment.value,t('journal.sync.device_label'),'',{maxLength:80}),rotation=field(enrollment.value,t('journal.sync.rotation'),'',{required:false,choices:[['',t('journal.sync.new_device')]]});
  const publicLabel=node('label',t('journal.sync.public_key'),'form-label'),publicKey=node('textarea');publicKey.className='form-control';publicKey.id='journal-public-key';publicKey.maxLength=4096;publicKey.required=true;publicKey.rows=3;publicKey.spellcheck=false;publicLabel.htmlFor=publicKey.id;
  publicKey.addEventListener('input',()=>{if(/PRIVATE KEY/.test(publicKey.value)){publicKey.value='';enrollment.feedback.textContent=t('journal.sync.public_only');}});
  enrollment.value.append(publicLabel,publicKey,node('p',t('journal.sync.rotation_help'),'hint'));const enrollPassword=secret(enrollment.value);enrollment.finish();
  const deviceCard=section(view,t('journal.sync.devices'));deviceCard.append(devices);
  const itemCard=section(view,t('journal.sync.imports'));itemCard.append(node('p',t('journal.sync.conflicts_help')),node('p',t('journal.sync.empty_missing'),'hint'),items);
  view.root.append(actionHome);
  view.cleanup.add(()=>{++generation;++actionGeneration;for(const input of secrets)input.value='';for(const url of urls)URL.revokeObjectURL(url);urls.clear();actionHome.replaceChildren();data=null;});
  function secret(target){const input=passwordField(target,t('ui.current_password'),true);input.maxLength=1024;input.disabled=!data.canManage;secrets.add(input);return input;}
  function takePassword(input){const value=input.value;input.value='';return value;}
  function command(target,text,copyKey='journal.sync.copy'){const pre=node('pre'),code=node('code',text);pre.className='journal-sync-command';pre.append(code);const copy=button(t(copyKey),()=>void view.run(async()=>{await navigator.clipboard.writeText(text);feedback.textContent=t('journal.sync.copied');},{submit:copy,feedback}));target.append(pre,copy);}
  function facts(entries){const list=node('dl',undefined,'journal-sync-facts');for(const [label,value] of entries){const detail=node('dd');detail.append(value instanceof Node?value:document.createTextNode(value));list.append(node('dt',label),detail);}return list;}
  function download(blob,filename){if(!view.valid())return;const url=URL.createObjectURL(blob);urls.add(url);const anchor=node('a');anchor.href=url;anchor.download=filename;anchor.click();}
  async function refresh(){const current=++generation,reply=await view.session.request('/journal');if(!view.valid()||current!==generation)return;data=reply.data;if(!view.dirty.has(settings.value)){enabled.checked=data.settings.enabled;settingsTag=data.settings.etag;}paint();}
  function closeAction(){if(view.busy)return false;if(actionForm&&view.dirty.has(actionForm)&&!window.confirm(t('ui.discard_unsaved_account_changes')))return false;++actionGeneration;if(actionForm){view.clean(actionForm);for(const input of actionForm.querySelectorAll('input[type="password"]'))input.value='';}actionForm=null;actionHome.replaceChildren();return true;}
  function paint(){
    if(!view.valid()||!data)return;
    rescan.disabled=!view.canWrite||!data.deployment.ready||!data.settings.enabled;
    settings.submit.disabled=!view.canWrite||!data.canManage||(enabled.checked&&!data.deployment.ready);enrollment.submit.disabled=!view.canWrite||!data.canManage||!data.deployment.ready;
    enabled.disabled=!data.canManage;settingsPassword.disabled=!data.canManage;enrollPassword.disabled=!data.canManage;
    setupStatus.replaceChildren(node('p',data.deployment.ready?t('journal.sync.ready'):errorText(data.deployment.code),data.deployment.ready?'hint':'alert alert-info'));
    if(!data.canManage)setupStatus.append(node('p',t('journal.sync.account_sign_in'),'alert alert-info'));
    connection.replaceChildren();if(data.deployment.ready){const c=data.deployment;connection.append(facts([[t('journal.sync.host'),c.hostname+':'+c.port],[t('journal.sync.user'),c.username],[t('journal.sync.remote'),c.remotePath],[t('journal.sync.fingerprint'),node('code',c.hostKeyFingerprint)]]),node('p',t('journal.sync.known_hosts')));command(connection,c.knownHostsEntry,'journal.sync.copy_host_key');}
    clientCommands.replaceChildren();if(data.deployment.ready){const prefix='python3 timekeeper-journal.py ';command(clientCommands,prefix+'configure --profile profile.json\n'+prefix+'trust --profile profile.json --fingerprint '+"'"+data.deployment.hostKeyFingerprint+"'"+'\n'+prefix+'verify --profile profile.json\n'+prefix+'sync --profile profile.json\n'+prefix+'schedule --profile profile.json');}
    const h=data.health;health.replaceChildren(facts([[t('journal.sync.last_verified'),h.lastVerifiedAt?instantText(h.lastVerifiedAt,zone):t('journal.sync.never')],[t('journal.sync.last_transfer'),h.lastTransferAt?instantText(h.lastTransferAt,zone):t('journal.sync.never')],[t('journal.sync.last_import'),h.lastImportAt?instantText(h.lastImportAt,zone):t('journal.sync.never')],[t('journal.sync.pending'),String(h.pending)],[t('journal.sync.conflicts'),String(h.conflicts)],[t('journal.sync.errors'),String(h.errors)],[t('journal.sync.aborted'),String(h.aborted)],[t('journal.sync.receiving'),String(h.receiving)]]));
    if(!view.dirty.has(enrollment.value)){rotation.replaceChildren();for(const [id,name] of [['',t('journal.sync.new_device')],...data.devices.filter(d=>!d.revoked).map(d=>[d.id,d.label])]){const option=node('option',name);option.value=id;rotation.append(option);}}
    const rows=data.devices.map(device=>{
      const actions=node('div',undefined,'token-actions'),profile=button(t('journal.sync.profile'),()=>void view.run(async()=>{const reply=await view.session.request(`/journal/devices/${device.id}/profile`);download(new Blob([JSON.stringify(reply.data,null,2)+'\n'],{type:'application/json'}),`timekeeper-journal-${device.id}.json`);},{submit:profile,feedback,canSubmit:()=>!device.revoked&&data.settings.enabled&&data.deployment.ready}));profile.disabled=device.revoked||!data.settings.enabled||!data.deployment.ready;
      actions.append(profile);if(!device.revoked){const revoke=button(t('journal.sync.revoke'),()=>revokeDevice(device));revoke.disabled=!view.canWrite||!data.canManage;actions.append(revoke);}
      return [device.label,node('code',device.fingerprint),t(device.revoked?'journal.sync.revoked':'journal.sync.active'),actions];
    });devices.replaceChildren(rows.length?table([t('journal.sync.device_label'),t('journal.sync.fingerprint'),t('journal.sync.status'),t('journal.sync.actions')],rows):node('p',t('journal.sync.no_devices')));
    const entries=data.items.map(item=>{
      const actions=node('div',undefined,'token-actions');if(item.canDownload){const save=button(t('journal.sync.download_original'),()=>void view.run(async()=>{const reply=await view.session.request(`/journal/items/${item.id}/source`,{binary:'application/octet-stream'});download(reply.data,item.filename);},{submit:save,feedback}));actions.append(save);}
      if(item.state==='CONFLICT'){const review=button(t('journal.sync.review'),()=>void reviewCandidate(item));actions.append(review);}
      if(item.canDownload&&item.state!=='PENDING'){const discard=button(t('journal.sync.discard_copy'),()=>void view.run(async()=>{if(!window.confirm(t('journal.sync.discard_confirm')))return;await view.session.request(`/journal/items/${item.id}/discard`,{method:'POST',etag:item.candidateEtag});await refresh();},{submit:discard,feedback,write:true}));discard.disabled=!view.canWrite;actions.append(discard);}
      return [item.filename,t('journal.sync.state.'+item.state),item.error?errorText(item.error):'',actions];
    });items.replaceChildren(entries.length?table([t('journal.sync.file'),t('journal.sync.status'),t('journal.sync.details'),t('journal.sync.actions')],entries):node('p',t('journal.sync.no_imports')));
  }
  function revokeDevice(device){
    if(!closeAction())return;
    const action=form(view,t('journal.sync.revoke_title',{device:device.label}),t('journal.sync.revoke'),async()=>{await view.session.request(`/journal/devices/${device.id}/revoke`,{method:'POST',body:{currentPassword:takePassword(password)}});if(!view.valid())return;view.clean(action.value);actionHome.replaceChildren();actionForm=null;await refresh();},{canSubmit:()=>data.canManage});
    const card=action.value.closest('section');actionHome.append(card);actionForm=action.value;action.value.append(node('p',t('journal.sync.revoke_help')));const password=secret(action.value);action.finish();action.value.append(button(t('ui.cancel'),closeAction));password.focus();
  }
  async function reviewCandidate(item){
    if(!closeAction())return;const current=++actionGeneration;
    await view.run(async()=>{
      const reply=await view.session.request(`/journal/items/${item.id}`);if(!view.valid()||current!==actionGeneration)return;
      const candidate=reply.data,card=node('section',undefined,'card account-card'),message=node('p','','form-feedback');message.setAttribute('role','status');
      card.append(node('h2',t('journal.sync.review_title',{file:item.filename})),node('p',t('journal.sync.review_help')));
      for(const [heading,note] of [[t('journal.sync.calendar_copy'),candidate.current],[t('journal.sync.incoming_copy'),candidate]]){const pre=node('pre',note.text+'\n\n'+note.body,'journal-sync-preview');card.append(node('h3',heading),pre);}
      for(const [action,key] of [['INCOMING','journal.sync.use_incoming'],['CALENDAR','journal.sync.keep_calendar']]){const save=button(t(key),()=>void view.run(async()=>{await view.session.request(`/journal/items/${item.id}/resolve`,{method:'POST',etag:candidate.candidateEtag,body:{action,currentEtag:candidate.currentEtag}});if(!view.valid())return;actionHome.replaceChildren();await refresh();},{submit:save,feedback:message,write:true}));save.disabled=!view.canWrite||!data.settings.enabled||!data.deployment.ready||data.devices.find(d=>d.id===item.deviceId)?.revoked;card.append(save);}
      card.append(button(t('ui.cancel'),closeAction),message);actionHome.append(card);
    },{feedback});
  }
  function errorText(code){return t('journal.sync.error.'+code,{defaultValue:t('journal.sync.error.UNKNOWN')});}
  enabled.addEventListener('change',()=>{settings.submit.disabled=!view.canWrite||!data.canManage||(enabled.checked&&!data.deployment.ready);});
  paint();
}

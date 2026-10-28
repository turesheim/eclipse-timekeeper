import {t} from '/web/i18n.js';
import {node,form,field,passwordField,section,table,button} from '/web/account-ui.js';
import {uploadCiphertext} from '/web/instance-upload.js';

const base='/admin/instance';
const statuses={PREPARING:'backups.preparing',UPLOADING:'backups.uploading',VALIDATING:'backups.validating',REVIEW:'backups.review',READY:'backups.ready',FAILED:'backups.failed',INTERRUPTED:'backups.interrupted',EXPIRED:'backups.expired',RESTORED:'backups.restored'};
export async function renderInstanceBackups(view) {
  const {data:access}=await view.session.request('/admin/settings');if(!view.valid())return;
  if(!access.canEdit){section(view,t('backups.title')).append(node('p',t('backups.account_required'),'alert alert-info'));return;}
  let summary=(await view.session.request(base)).data;if(!view.valid())return;
  let worker=null,selected=null,digest=null,hashGeneration=0,timer=null,maintenance=false;
  const controller=new AbortController(),secrets=new Set(),cards=new Map();
  view.cleanup.add(()=>{controller.abort();clearTimeout(timer);worker?.terminate();++hashGeneration;selected=null;digest=null;for(const input of secrets)input.value='';});
  const password=(parent,title,current=false)=>{const input=passwordField(parent,t(title),current);secrets.add(input);return input;};
  const clear=(...inputs)=>{for(const input of inputs)input.value='';};
  const intro=section(view,t('backups.title'));intro.append(node('p',t('backups.description')));
  if(!summary.storageConfigured){intro.append(node('p',t('backups.storage_required'),'alert alert-warning'));return;}
  const create=form(view,t('backups.create'),t('backups.create'),async({value})=>{
    if(phrase.value!==confirm.value)throw new Error(t('backups.phrase_mismatch'));
    const body={currentPassword:proof.value,passphrase:phrase.value};clear(proof,phrase,confirm);view.clean(value);
    try {await view.session.request(base+'/backups',{method:'POST',body,timeout:45000});}finally{body.currentPassword='';body.passphrase='';}
    await refresh();
  });
  const proof=password(create.value,'backups.password',true),phrase=password(create.value,'backups.phrase'),confirm=password(create.value,'backups.confirm_phrase');phrase.minLength=12;phrase.maxLength=1024;
  create.value.append(node('p',t('backups.phrase_help'),'hint'));create.finish();
  const upload=form(view,t('backups.upload'),t('backups.start_upload'),async({value})=>{
    const file=selected,sha256=digest;if(!file||!sha256)throw new Error(t('backups.choose_file'));
    const body={currentPassword:uploadProof.value,bytes:file.size,sha256};clear(uploadProof);view.clean(value);
    let reply;try{reply=await view.session.request(base+'/uploads',{method:'POST',body,timeout:45000});}finally{body.currentPassword='';}
    const id=reply.data.operation.id;
    await uploadCiphertext(view.session,id,file,sha256,{signal:controller.signal,onProgress:received=>{if(view.valid())upload.feedback.textContent=t('backups.progress',{received,total:file.size});}});
    await refresh();
  },{canSubmit:()=>!!selected&&!!digest});
  const fileInput=field(upload.value,t('backups.file'),'',{type:'file'});fileInput.accept='.tkbackup';
  const hashing=node('p','', 'hint');hashing.setAttribute('role','status');upload.value.append(hashing);
  const uploadProof=password(upload.value,'backups.password',true);upload.finish();
  fileInput.addEventListener('change',()=>{
    worker?.terminate();const generation=++hashGeneration;selected=null;digest=null;upload.submit.disabled=true;
    const file=fileInput.files?.[0];if(!file)return;
    if(file.size<40||file.size>summary.maximumArchiveBytes){hashing.textContent=t('backups.file_size',{maximum:summary.maximumArchiveBytes});return;}
    selected=file;hashing.textContent=t('backups.hashing');const activeWorker=new Worker('/web/instance-upload-worker.js',{type:'module'});worker=activeWorker;
    const failed=()=>{if(view.valid()&&generation===hashGeneration){digest=null;hashing.textContent=t('backups.hash_failed');upload.submit.disabled=true;}activeWorker.terminate();if(worker===activeWorker)worker=null;};
    activeWorker.onerror=failed;
    activeWorker.onmessage=({data})=>{
      if(!view.valid()||generation!==hashGeneration)return;
      if(data.failed){failed();return;}
      if(data.sha256){digest=data.sha256;hashing.textContent=t('backups.file_ready');upload.submit.disabled=view.busy||view.uncertain;activeWorker.terminate();if(worker===activeWorker)worker=null;}
      else hashing.textContent=t('backups.hash_progress',{received:data.received,total:file.size});
    };activeWorker.postMessage({file});
  });
  const operations=section(view,t('backups.operations')),list=node('div');operations.append(button(t('backups.refresh'),()=>void view.run(refresh)),list);
  async function addOperation(item) {
    const op=item.operation,key=op.id;
    const data=op.type==='RESTORE'&&item.canAccessUpload&&['UPLOADING','VALIDATING','REVIEW'].includes(op.state)?(await view.session.request(base+'/uploads/'+key)).data:null;
    if(!view.valid())return;
    const staged=data&&op.state==='REVIEW'&&summary.restoreStorageConfigured?(await view.session.request(base+'/uploads/'+key+'/staging')).data:null;
    if(!view.valid())return;
    const entry=cards.get(key);
    const signature=op.state+':'+(data?.validationCode||'')+':'+item.canAccessUpload+':'+(staged?.phase||'')+':'+(staged?.operationVersion||'');
    if(entry?.state===signature){if(entry.progress&&data)entry.progress.textContent=t('backups.progress',{received:data.received,total:data.bytes});return;}
    if(entry){for(const input of entry.secrets){input.value='';secrets.delete(input);}for(const value of entry.forms)view.dirty.delete(value);entry.root.remove();}
    const root=node('section',undefined,'card account-card'),ownSecrets=[],forms=[];
    root.append(node('h3',t(op.type==='BACKUP'?'backups.backup':'backups.restore')),node('p',t(statuses[op.state])),node('p',op.id,'small text-break'));
    list.append(root);cards.set(key,{state:signature,root,secrets:ownSecrets,forms});
    const nested={...view,root,run:(...args)=>view.run(...args)};
    function action(title,work){const value=form(nested,t(title),t(title),work);forms.push(value.value);return value;}
    function credential(parent,title,current=false){const value=password(parent,title,current);ownSecrets.push(value);return value;}
    if(op.type==='BACKUP'&&op.state==='READY'){
      const download=action('backups.download',async({value})=>{
        const body={currentPassword:downloadProof.value};clear(downloadProof);view.clean(value);
        let reply;try{reply=await view.session.request(base+'/operations/'+key+'/download-grant',{method:'POST',body,timeout:45000});}finally{body.currentPassword='';}
        if(!view.valid())return;
        const expected='/api/v1'+base+'/operations/'+key+'/content';if(reply.data.downloadUrl!==expected)throw new Error(t('backups.download_failed'));
        // Native attachment navigation streams directly to the browser's download manager.
        const anchor=node('a');anchor.href=expected;anchor.referrerPolicy='origin';anchor.download='';root.append(anchor);anchor.click();anchor.remove();
        download.feedback.textContent=t('backups.download_started');
      });
      const downloadProof=credential(download.value,'backups.password',true);download.finish();
    }
    if(op.type==='RESTORE'&&item.canAccessUpload&&['UPLOADING','VALIDATING','REVIEW'].includes(op.state)){
      const details=node('div');root.append(details);
      {
        const progress=node('p',t('backups.progress',{received:data.received,total:data.bytes}));cards.get(key).progress=progress;details.append(progress);
        if(data.validationCode)details.append(node('p',t(data.validationCode==='INVALID_INSTANCE_AUTHENTICATION'?'backups.invalid_authentication':'backups.invalid_archive'),'alert alert-warning'));
        if(data.review){
          const review=data.review;details.append(node('p',t(summary.restoreStorageConfigured?'backups.review_preparation':'backups.review_warning'),'alert alert-warning'),table([t('backups.table'),t('backups.rows')],Object.entries(review.counts).map(([name,count])=>[name,String(count)])));
          details.append(node('p',t('backups.archive_source',{build:review.source.serverVersion,backend:review.source.databaseType,schema:review.schema})));
        }
      }
      if(op.state==='REVIEW'){
        const target=action('backups.check_target',async({value})=>{
          const body={currentPassword:targetProof.value};clear(targetProof);view.clean(value);
          let reply;try{reply=await view.session.request(base+'/uploads/'+key+'/target',{method:'POST',body,timeout:45000});}finally{body.currentPassword='';}
          if(!view.valid()||cards.get(key)?.root!==root)return;
          targetResult.replaceChildren(node('p',t(reply.data.databaseEligible?'backups.target_eligible':'backups.target_blocked'),reply.data.databaseEligible?'alert alert-info':'alert alert-warning'));
          const labels={TARGET_OTHER_ACCOUNTS:'backups.target_accounts',TARGET_BUSINESS_DATA:'backups.target_data',TARGET_API_TOKENS:'backups.target_tokens',TARGET_BUSINESS_HISTORY:'backups.target_history'};
          const blockers=node('ul');for(const code of reply.data.blockers)blockers.append(node('li',t(labels[code]||'backups.target_blocked')));if(reply.data.blockers.length)targetResult.append(blockers);
          targetResult.append(node('p',t('backups.target_advisory'),'hint'));
        });
        const targetProof=credential(target.value,'backups.password',true),targetResult=node('div');target.value.append(targetResult);target.finish();
      }
      if(op.state==='REVIEW'&&staged){
        if(staged.phase==='PREPARING')root.append(node('p',t('backups.stage_preparing'),'alert alert-info'));
        if(staged.phase==='FAILED')root.append(node('p',t('backups.stage_failed'),'alert alert-warning'));
        if(['NONE','FAILED'].includes(staged.phase)){
          const prepare=action('backups.prepare_restore',async({value})=>{
            const body={currentPassword:stageProof.value,passphrase:stagePhrase.value,operationVersion:staged.operationVersion,publicOrigin:origin.value,passkeyRpId:rp.value,passkeysEnabled:passkeys.checked};clear(stageProof,stagePhrase);view.clean(value);
            try{await view.session.request(base+'/uploads/'+key+'/staging',{method:'POST',body,timeout:45000});}finally{body.currentPassword='';body.passphrase='';}
            await refresh();
          });
          prepare.value.append(node('p',t('backups.stage_help'),'hint'));
          const origin=field(prepare.value,t('backups.destination_origin'),access.effective?.publicOrigin||'NONE',{maxLength:2048});
          const rp=field(prepare.value,t('backups.destination_rp'),access.effective?.passkeyRpId||'AUTO',{maxLength:253});
          const passkeys=field(prepare.value,t('backups.destination_passkeys'),!!access.effective?.passkeysEnabled&&!!access.effective?.publicOrigin,{type:'checkbox'});
          const stageProof=credential(prepare.value,'backups.password',true),stagePhrase=credential(prepare.value,'backups.phrase');stagePhrase.minLength=12;stagePhrase.maxLength=1024;prepare.finish();
        }
        if(staged.phase==='READY'){
          const target=staged.target;
          root.append(table([t('backups.destination'),t('backups.value')],[
            [t('backups.destination_package'),target.packageId],[t('backups.destination_origin'),target.publicOrigin],
            [t('backups.destination_rp'),target.passkeyRpId],[t('backups.destination_passkeys'),String(target.passkeysEnabled)],
            [t('backups.destination_database'),target.databaseType],[t('backups.destination_listener'),target.httpHost+':'+target.httpPort+' / '+target.httpsPort],
          ]),node('p',t('backups.restore_consequences'),'alert alert-warning'));
          const confirmRestore=action('backups.confirm_restore',async({value})=>{
            const body={currentPassword:confirmProof.value,operationVersion:staged.operationVersion,generation:staged.generation,confirmation:consent.value};clear(confirmProof);view.clean(value);
            let result;try{result=(await view.session.request(base+'/uploads/'+key+'/confirm',{method:'POST',body,timeout:45000})).data;}finally{body.currentPassword='';}
            if(!view.valid())return;maintenance=true;clearTimeout(timer);for(const input of secrets)input.value='';
            view.root.replaceChildren(node('h2',t('backups.pending_title')),node('p',t('backups.pending_restart'),'alert alert-warning'),node('p',t('backups.pending_recovery')),node('p',t('backups.pending_destination',{origin:result.publicOrigin})),node('p',result.id,'text-break'));
            const link=node('a',t('backups.check_restored'),'btn btn-primary');link.href=result.publicOrigin==='NONE'?'/web/':result.publicOrigin+'/web/';view.root.append(link);
          });
          confirmRestore.value.append(node('p',t('backups.confirm_restore_help',{confirmation:'RESTORE '+target.packageId})));
          const consent=field(confirmRestore.value,t('backups.confirmation_text'),'',{maxLength:80}),confirmProof=credential(confirmRestore.value,'backups.password',true);confirmRestore.finish();
        }
      }
      if(op.state==='UPLOADING'){
        const resume=button(t('backups.resume'),()=>void view.run(async()=>{
          if(!selected||!digest)throw new Error(t('backups.choose_file'));
          await uploadCiphertext(view.session,key,selected,digest,{signal:controller.signal,onProgress:received=>{if(view.valid())resumeStatus.textContent=t('backups.progress',{received,total:selected.size});}});await refresh();
        },{write:true}));const resumeStatus=node('p');root.append(resume,resumeStatus);
        const validate=action('backups.validate',async({value})=>{
          const body={currentPassword:validateProof.value,passphrase:validatePhrase.value};clear(validateProof,validatePhrase);view.clean(value);
          try{await view.session.request(base+'/uploads/'+key+'/validate',{method:'POST',body,timeout:45000});}finally{body.currentPassword='';body.passphrase='';}
          await refresh();
        });
        const validateProof=credential(validate.value,'backups.password',true),validatePhrase=credential(validate.value,'backups.phrase');validatePhrase.minLength=12;validatePhrase.maxLength=1024;validate.finish();
      }
    }
    if(['READY','REVIEW','UPLOADING','FAILED','INTERRUPTED'].includes(op.state)){
      const discard=action('backups.discard',async({value})=>{
        const body={currentPassword:discardProof.value};clear(discardProof);view.clean(value);
        try{await view.session.request(base+'/operations/'+key+'/discard',{method:'POST',body});}finally{body.currentPassword='';}await refresh();
      });
      const discardProof=credential(discard.value,'backups.password',true);discard.value.append(node('p',t('backups.discard_help'),'hint'));discard.finish();
    }
  }
  async function refresh(){
    const {data}=await view.session.request(base);if(!view.valid())return;summary=data;
    for(const item of data.items)await addOperation(item);
  }
  async function poll(){if(!view.valid()||maintenance)return;if(!view.busy)await view.run(refresh);if(view.valid()&&!maintenance)timer=setTimeout(poll,3000);}
  for(const item of summary.items)await addOperation(item);timer=setTimeout(poll,3000);
}

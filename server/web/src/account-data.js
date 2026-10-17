import {node,button,field,section,form,table} from './account-ui.js';
import {instantText} from './model.js';

const MAX_BYTES=256*1024*1024;
const labels={clients:'Clients',projects:'Projects',tasks:'Tasks',labels:'Labels',activities:'Activities',workSessions:'Work sessions',importAuditRecords:'Import audit records'};
const base='/data/account';

export async function renderAccountData(view,zone) {
  let currentZone=zone,pendingRequest=0,historyRequest=0;
  const api=view.session,urls=new Set();let worker=null,cancelWorker=null,selected=null,fingerprint=null,createKey=null,uploadId=null,review=null,uncertain=false,historyAfter=null,historyItems=[];
  const downloadCard=section(view,'Download my data'),downloadFeedback=node('p','','form-feedback');downloadFeedback.setAttribute('role','status');
  downloadCard.append(node('p','A private ZIP with JSON for restoring your work and CSV files for other tools. Includes your work, preferences and import history.'));
  const download=button('Download my data',()=>void view.run(async()=>{
    download.disabled=true;
    try {
      const reply=await api.request(base+'/export',{binary:true,timeout:300000});if(!view.valid()) return;
      const url=URL.createObjectURL(reply.data);urls.add(url);const link=node('a','Save archive','btn btn-outline-secondary');link.href=url;link.download='timekeeper-account.zip';
      downloadFeedback.replaceChildren(node('span','Your private archive is ready. '),link);link.click();
    }finally {if(view.valid()) download.disabled=false;}
  },{feedback:downloadFeedback}),'btn btn-primary');downloadCard.append(download,downloadFeedback);

  const upload=form(view,'Restore from a Timekeeper archive','Review archive',reviewFile);
  upload.value.append(node('p','Adds the listed records to this account. Existing records and sign-in details are kept.'));
  const file=field(upload.value,'Timekeeper archive','',{type:'file'});file.accept='.zip,.json,application/zip,application/json';
  upload.value.append(node('p','Timekeeper ZIP or archive.json · up to 256 MiB.','hint'));
  const restoreZone=field(upload.value,'Restore the archived time zone',false,{type:'checkbox'});
  const cancelReading=button('Cancel reading',()=>cancelWorker?.());cancelReading.hidden=true;upload.value.append(cancelReading);upload.finish();
  const work=section(view,'Review archive');work.hidden=true;
  const pendingCard=section(view,'Pending uploads'),pendingList=node('div');pendingCard.append(pendingList);
  const historyCard=section(view,'Archive restore history'),historyList=node('div'),historyActions=node('div');historyCard.append(historyList,historyActions);
  upload.value.addEventListener('change',()=>{review=null;uncertain=false;work.hidden=true;if(file.files[0]!==selected) {selected=null;fingerprint=createKey=uploadId=null;}});
  view.cleanup.add(()=>{
    cancelWorker?.();worker?.terminate();worker=null;selected=fingerprint=createKey=uploadId=review=null;file.value='';historyItems.length=0;
    for(const url of urls) URL.revokeObjectURL(url);urls.clear();
  });
  await Promise.all([loadPending(),loadHistory()]);

  function hash(chosen) {
    return new Promise((resolve,reject)=>{
      const current=new Worker(new URL('./account-data-worker.js',import.meta.url),{type:'module'});worker=current;cancelReading.hidden=false;
      const finish=(result,error)=>{
        current.terminate();if(worker===current) {worker=null;cancelWorker=null;cancelReading.hidden=true;}
        if(error) reject(new Error(error));else resolve(result);
      };
      cancelWorker=()=>finish(null,'Reading cancelled.');current.onmessage=event=>finish(event.data.sha256,event.data.error);
      current.onerror=()=>finish(null,'The archive could not be read. Choose the unchanged Timekeeper file.');current.postMessage({file:chosen});
    });
  }
  async function locked(work) {
    const controls=[...upload.value.querySelectorAll('input,button')].filter(control=>!control.disabled && control!==cancelReading);for(const control of controls) control.disabled=true;
    try {return await work();}finally {if(view.valid() && !uncertain) for(const control of controls) control.disabled=false;}
  }
  async function reviewFile() {
    await locked(async()=>{
      const chosen=file.files[0];if(!chosen || chosen.size<1 || chosen.size>MAX_BYTES) throw new Error('Choose a Timekeeper ZIP or archive.json up to 256 MiB.');
      const mediaType=chosen.name.toLowerCase().endsWith('.zip') ? 'application/zip':chosen.name.toLowerCase().endsWith('.json') ? 'application/json':null;
      if(!mediaType) throw new Error('Choose a Timekeeper ZIP or archive.json.');
      work.hidden=true;review=null;uncertain=false;
      try {
        if(selected!==chosen || !fingerprint) {upload.feedback.textContent='Reading archive…';fingerprint=await hash(chosen);selected=chosen;createKey=crypto.randomUUID();}
        if(!view.valid()) return;
        const pending=await api.request(base+'/uploads');if(!view.valid()) return;
        let current=pending.data.items.find(value=>value.sha256===fingerprint && value.bytes===chosen.size && value.mediaType===mediaType);
        if(!current) current=(await api.request(base+'/uploads',{method:'POST',key:createKey,body:{fileName:chosen.name,mediaType,bytes:chosen.size,sha256:fingerprint}})).data;
        if(!view.valid()) return;uploadId=current.id;
        current=(await api.request(base+'/uploads/'+uploadId)).data;
        for(let index=current.nextChunk;index<current.chunkCount;index++) {
          const bytes=new Uint8Array(await chosen.slice(index*current.chunkBytes,Math.min(chosen.size,(index+1)*current.chunkBytes)).arrayBuffer());
          // Avoid spreading an entire MiB into a browser's function argument stack.
          let text='';for(let offset=0;offset<bytes.length;offset+=8192) text+=String.fromCharCode(...bytes.subarray(offset,offset+8192));
          await api.request(`${base}/uploads/${uploadId}/chunks/${index}`,{method:'PUT',body:{data:btoa(text)},timeout:60000});if(!view.valid()) return;
          upload.feedback.textContent=`Uploading archive: ${Math.min(100,Math.round((index+1)/current.chunkCount*100))}%.`;
        }
        upload.feedback.textContent='Validating archive…';
        review=await api.request(`${base}/uploads/${uploadId}/preview`,{method:'POST',body:{restoreTimeZone:restoreZone.checked},timeout:300000});if(!view.valid()) return;
        upload.feedback.textContent='Archive reviewed.';await loadPending();if(!view.valid()) return;
        if(review.data.alreadyImported) await restored(review.data,true);else showReview();
      }catch(error) {
        if(!view.valid()) return;
        if(error.uncertain) {upload.feedback.textContent='Upload paused. Select Review archive to resume safely.';return;}
        if([400,422].includes(error.status)) throw new Error('This is not a valid supported Timekeeper archive. Check the complete file and its work-record links.');
        if(error.status===413) throw new Error('The archive exceeds a supported size limit, or pending uploads need to be discarded.');
        throw error;
      }
    });
  }
  function countTable(counts) {return table(['Records','Count'],Object.entries(labels).map(([key,label])=>[label,String(counts[key] ?? 0)]));}
  function showReview() {
    const value=review.data,captured=review,reviewedUploadId=uploadId;work.hidden=false;work.replaceChildren(node('h2','Review archive'));
    work.append(node('p',`${value.sourceAccount.displayName || value.sourceAccount.username || 'Account archive'} · ${instantText(value.generatedAt,currentZone)}`),countTable(value.counts));
    work.append(node('p',value.restoreTimeZone ? `Time zone: ${value.currentTimeZone} → ${value.sourceTimeZone}`:`Keep time zone: ${value.currentTimeZone}`));
    if(value.runningActivities || value.runningWorkSessions) work.append(node('p',`${value.runningActivities} running activities · ${value.runningWorkSessions} running work sessions.`));
    for(const conflict of value.conflicts) work.append(node('p',conflict,'alert alert-warning'));
    const feedback=node('p','','form-feedback');feedback.setAttribute('role','status');
    const apply=button('Import reviewed records',()=>void view.run(()=>locked(async()=>{
      try {
        const reply=await api.request(`${base}/uploads/${reviewedUploadId}/import`,{method:'POST',etag:captured.etag,body:{restoreTimeZone:value.restoreTimeZone},timeout:300000});
        if(view.valid()) await restored(reply.data,false);
      }catch(error) {
        if(error.uncertain) {uncertain=true;feedback.textContent='The import result is unknown. Check its result before continuing.';check.hidden=false;return;}
        if(error.status===412) {value.canImport=false;feedback.textContent='Your work changed after review. Select Review archive to review the current records again.';return;}
        throw error;
      }
    }),{submit:apply,feedback,write:true,canSubmit:()=>value.canImport && !uncertain}),'btn btn-primary');apply.disabled=!view.canWrite || !value.canImport;
    const check=button('Check import result',()=>void view.run(async()=>{
      const current=await api.request(base+'/uploads/'+reviewedUploadId);if(!view.valid()) return;
      if(current.data.state==='IMPORTED') {await restored(current.data.outcome,false);return;}
      feedback.textContent='No completed import is recorded. You can retry the same import.';uncertain=false;unlockUpload();apply.disabled=!view.canWrite || !value.canImport;check.hidden=true;
    },{feedback}));check.hidden=true;
    work.append(feedback,apply,node('span',' '),check);
  }
  function unlockUpload() {file.disabled=restoreZone.disabled=false;upload.submit.disabled=!view.canWrite;}
  async function restored(value,already) {
    if(!view.valid()) return;view.clean(upload.value);uncertain=false;unlockUpload();work.hidden=false;
    work.replaceChildren(node('h2',already ? 'Archive already restored':'Archive restored'),node('p',already ? 'This archive was already restored. Its records were not added again.':'Your work records have been restored.'),countTable(value.counts));
    upload.feedback.textContent='';
    try {const profile=await api.request('/me');if(!view.valid()) return;currentZone=profile.data.zoneId;void view.refreshProfile(profile).catch(()=>{});}
    catch(error) {if(error.status===401) throw error;upload.feedback.textContent='Restore completed. Refresh to reload the current records.';}
    historyAfter=null;historyItems=[];
    const results=await Promise.allSettled([loadPending(),loadHistory()]);
    for(const result of results) if(result.status==='rejected') {if(result.reason.status===401) throw result.reason;upload.feedback.textContent='Restore completed. Refresh to reload the current records.';}
  }
  async function loadPending() {
    const request=++pendingRequest,reply=await api.request(base+'/uploads');if(!view.valid() || request!==pendingRequest) return;
    pendingCard.hidden=!reply.data.items.length;
    pendingList.replaceChildren(table(['Archive','Expires','Actions'],reply.data.items.map(item=>{
      const discard=button('Discard upload',()=>void view.run(async()=>{
        const current=await api.request(base+'/uploads/'+item.id);await api.request(base+'/uploads/'+item.id,{method:'DELETE',etag:current.etag});if(item.id===uploadId || item.sha256===fingerprint) {createKey=crypto.randomUUID();uploadId=null;review=null;work.hidden=true;}if(view.valid()) await loadPending();
      },{submit:discard,write:true}));discard.disabled=!view.canWrite;
      return [item.fileName,instantText(item.expiresAt,currentZone),discard];
    })));
  }
  async function loadHistory() {
    const request=++historyRequest,reply=await api.request(base+'/imports'+(historyAfter ? '?after='+encodeURIComponent(historyAfter):''));if(!view.valid() || request!==historyRequest) return;
    historyItems.push(...reply.data.items);historyAfter=reply.data.nextAfter;
    historyList.replaceChildren(historyItems.length ? table(['Restored','Work sessions','Activities'],historyItems.map(item=>[instantText(item.importedAt,currentZone),String(item.counts.workSessions),String(item.counts.activities)])):node('p','No archives restored.'));
    historyActions.replaceChildren();if(historyAfter) historyActions.append(button('More restores',()=>void view.run(loadHistory)));
  }
}

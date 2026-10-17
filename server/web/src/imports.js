import {node, button, field, section, form, table} from './account-ui.js';
import {instantText, nanosText} from './model.js';

const names = {CLIENT:'Client', PROJECT:'Project', TASK:'Task', LABEL:'Label'};
const signature = value => JSON.stringify(value);

export async function renderImports(view, zone) {
  let parsed = null, fingerprint = null, plan = null, batches = [], reviews = [], resume = null;
  let worker = null, cancelWorker = null, pause = false, undoPause = false, historyAfter = null, historyItems = [], createKey = null;
  const api = view.session, catalog = {};
  const upload = form(view, 'Import from Toggl Track', 'Read CSV', read);
  upload.value.append(node('p', 'Detailed Report CSV. Completed entries become activities; work sessions are separate.'));
  const file = field(upload.value, 'Detailed Report CSV', '', {type:'file'});file.accept = '.csv,text/csv';
  const sourceZone = field(upload.value, 'Source time zone', zone);
  const dateFormat = field(upload.value, 'Source date format', 'ISO', {choices:[['ISO','YYYY-MM-DD'],['DMY','DD/MM/YYYY'],['MDY','MM/DD/YYYY']]});
  const workspace = field(upload.value, 'Source workspace (if absent from CSV)', '', {required:false, maxLength:1000});
  const stopParsing = button('Cancel reading', () => cancelWorker?.());stopParsing.hidden = true;upload.value.append(stopParsing);upload.finish();
  const work = section(view, 'Review import');work.hidden = true;
  const history = section(view, 'Import history'), historyList = node('div');history.append(historyList);
  const operations = node('div');history.append(operations);
  upload.value.addEventListener('change', () => {cancelWorker?.();parsed = plan = null;work.hidden = true;});
  view.cleanup.add(() => {
    cancelWorker?.();worker?.terminate();worker = null;
    if(parsed) parsed.rows.length = 0;if(plan) plan.rows.length = 0;
    parsed = plan = resume = null;reviews.length = batches.length = historyItems.length = 0;file.value = '';fingerprint = createKey = null;
  });
  await Promise.all(Object.entries({CLIENT:'clients',PROJECT:'projects',TASK:'tasks',LABEL:'labels'}).map(async ([type, path]) => {
    const reply = await api.page(`/${path}`);if(view.valid()) catalog[type] = reply.items;
  }));
  if(!view.valid()) return;
  await loadHistory();

  function runWorker(data) {
    cancelWorker?.();
    return new Promise((resolve, reject) => {
      const current = new Worker(new URL('./toggl-worker.js', import.meta.url), {type:'module'});worker = current;
      const finish = (value, error) => {
        current.terminate();if(worker === current) {worker = null;cancelWorker = null;stopParsing.hidden = true;}
        if(error) reject(new Error(error));else resolve(value);
      };
      cancelWorker = () => finish(null, 'Reading cancelled.');stopParsing.hidden = false;
      current.onmessage = event => finish(event.data, event.data.error);
      current.onerror = () => finish(null, 'The CSV could not be read. Check the file and try again.');
      current.postMessage(data);
    });
  }
  async function locked(work) {
    const enabled = [...upload.value.querySelectorAll('input,select,button'),...workCardControls()].filter(value=>!value.disabled && value!==stopParsing);
    for(const value of enabled) value.disabled = true;
    try {return await work();} finally {if(view.valid()) for(const value of enabled) value.disabled = false;}
  }
  function workCardControls() {return work.querySelectorAll('input,select,button');}
  async function read() {
    return locked(async()=>{
    if(!file.files[0]) throw new Error('Choose a CSV file.');
    work.hidden = true;plan = null;reviews = [];batches = [];parsed = null;createKey = null;
    const result = await runWorker({file:file.files[0],options:{zone:sourceZone.value.trim(),dateFormat:dateFormat.value,workspace:workspace.value}});
    if(!view.valid()) return;
    parsed = result.parsed;fingerprint = result.fileFingerprint;
    if(resume && fingerprint !== resume.fileFingerprint) throw new Error('Choose the unchanged CSV used by this import.');
    work.hidden = false;sourceReview();upload.feedback.textContent = `${parsed.rows.length} source rows read. Original file unchanged.`;
    });
  }
  function sourceReview() {
    work.replaceChildren(node('h2','Review import'));
    const source = field(work, 'Source user', parsed.people.length === 1 ? parsed.people[0].key : '', {choices:[['','Choose source user'],...parsed.people.map(person => [person.key,[person.name,person.email,person.workspace].filter(Boolean).join(' · ')])]});
    if(parsed.unsupported.length) work.append(node('p', `Unsupported metadata: ${parsed.unsupported.join(', ')}. These columns will not be imported.`, 'alert alert-info'));
    const fallbackClient = field(work, 'Client for entries without a client', 'Imported from Toggl', {maxLength:1000});
    const fallbackProject = field(work, 'Project for entries without a project', 'Imported time', {maxLength:1000});
    const invalid = field(work, 'Exclude invalid rows after reviewing the diagnostics', false, {type:'checkbox'});
    const duplicates = field(work, 'Keep indistinguishable source rows as separate entries', false, {type:'checkbox'});
    const rowList = node('div');work.append(rowList);let page = 0;
    function showRows() {
      rowList.replaceChildren();const selected = parsed.rows.filter(row => row.personKey === source.value || !row.personKey);
      const errors = selected.filter(row => row.errors.length).length;
      rowList.append(node('p', `${selected.length} source rows · ${errors} invalid. Displayed times use ${zone}.`));
      const rows = selected.slice(page * 100, (page + 1) * 100).map(row => {
        const select = node('input');select.type = 'checkbox';select.className = 'form-check-input';select.checked = !row.excluded && !row.errors.length;select.disabled = !!row.errors.length;
        select.setAttribute('aria-label', `Include source line ${row.line}`);select.addEventListener('change', () => {row.excluded = !select.checked;view.dirty.add(upload.value);});
        return [select,String(row.line),row.start ? instantText(row.start,zone) : '',row.end ? instantText(row.end,zone) : '',row.durationNanos === undefined ? '' : nanosText(row.durationNanos),row.summary || '',[...row.errors,...row.warnings,...(row.ambiguousDuplicate ? ['Indistinguishable duplicate source row.'] : [])].join(' ')];
      });
      rowList.append(table(['Include','Line','Start','End','Duration','Description','Diagnostics'],rows));
      if(selected.length > 100) {
        const previous = button('Previous rows', () => {page--;showRows();});previous.disabled = page === 0;
        const next = button('Next rows', () => {page++;showRows();});next.disabled = (page + 1) * 100 >= selected.length;
        rowList.append(previous,node('span',` Page ${page+1} `),next);
      }
    }
    source.addEventListener('change', () => {page = 0;showRows();});showRows();
    const feedback = node('p','','form-feedback');feedback.setAttribute('role','status');
    const next = button('Review mappings', () => void view.run(() => locked(async () => {
      if(!source.value) throw new Error('Choose the source user.');
      const selected = parsed.rows.filter(row => row.personKey === source.value || !row.personKey);
      if(selected.some(row => row.errors.length) && !invalid.checked) throw new Error('Review and explicitly exclude invalid rows before continuing.');
      if(selected.some(row => row.ambiguousDuplicate && !row.excluded && !row.errors.length) && !duplicates.checked) throw new Error('Review indistinguishable source rows, then keep them separately or exclude them.');
      for(const row of selected) row.duplicateReviewed = duplicates.checked;
      const result = await runWorker({operation:'plan',rows:parsed.rows,personKey:source.value,reportZone:resume?.reportZone || zone,
        options:{fallbackClient:fallbackClient.value,fallbackProject:fallbackProject.value}});
      if(!view.valid()) return;plan = result.plan;batches = result.batches;reviews = [];
      if(resume) {
        if(plan.sourceKey !== resume.sourceKey || plan.selectedEntries !== resume.selectedEntries || plan.selectedNanos !== resume.selectedNanos
          || resume.selectionFingerprint && plan.selectionFingerprint !== resume.selectionFingerprint) throw new Error('Choose the original source user and the same row exclusions to resume this import.');
        if(zone !== resume.reportZone) throw new Error('The account time zone changed. Undo this job or start a newly reviewed import.');
        plan.mappings = structuredClone(resume.mappings);
      }
      mappingReview();
    }),{submit:next,feedback,write:true}),'btn btn-primary');next.disabled = !view.canWrite;
    work.append(feedback,next);
  }
  function mappingReview() {
    work.replaceChildren(node('h2','Review mappings'),node('p',`${plan.selectedEntries} entries · ${nanosText(BigInt(plan.selectedNanos))} · ${plan.mappings.length} catalog mappings`));
    if(resume) work.append(node('p','The original mapping plan is retained while resuming.','alert alert-info'));
    const list = node('div');work.append(list);const definitions = new Map(plan.mappings.map(value => [value.key,value]));
    const paths = {CLIENT:'clients',PROJECT:'projects',TASK:'tasks',LABEL:'labels'};
    let mappingRequests = 0;
    function draw() {
      list.replaceChildren();
      for(const definition of plan.mappings) {
        const parent = definitions.get(definition.parentKey), type = definition.resourceType;
        const choices = (catalog[type] || []).filter(value => !parent || parent.action === 'REUSE' && value[type === 'PROJECT' ? 'clientId' : 'projectId'] === parent.id);
        if(definition.action === 'REUSE' && !choices.some(value => value.id === definition.id) && !resume) {
          definition.action = 'CREATE';delete definition.id;delete definition.etag;
        }
        const row = node('div',undefined,'import-mapping');
        const select = field(row,`${names[type]}: ${definition.name}`,definition.action === 'CREATE' ? '' : definition.id,
          {choices:[['','Create new'],...choices.map(value => [value.id,`Reuse ${value.name || value.summary}`])]});
        select.disabled = !!resume;
        const name = field(row,`New ${names[type].toLowerCase()} name`,definition.name,{maxLength:1000});name.disabled = definition.action !== 'CREATE' || !!resume;
        name.addEventListener('input', () => {definition.name = name.value;view.dirty.add(upload.value);});
        if(type === 'CLIENT' || type === 'LABEL') {
          const color = field(row,`${names[type]} color`,definition.color || '#2563eb',{type:'color'});color.disabled = definition.action !== 'CREATE' || !!resume;
          color.addEventListener('input', () => {definition.color = color.value;view.dirty.add(upload.value);});
        }
        select.addEventListener('change', () => void view.run(async () => {
          const selected = select.value;mappingRequests++;
          try {
            if(selected) {
              const reply = await api.request(`/${paths[type]}/${selected}`);if(!view.valid() || select.value !== selected) return;
              definition.action = 'REUSE';definition.id = selected;definition.etag = reply.etag;
            } else {definition.action = 'CREATE';delete definition.id;delete definition.etag;}
            if(view.valid()) {view.dirty.add(upload.value);draw();}
          } finally {mappingRequests--;}
        },{feedback}));
        list.append(row);
      }
    }
    const feedback = node('p','','form-feedback');feedback.setAttribute('role','status');draw();
    const review = button('Review server matches', () => void view.run(() => locked(async () => {
      if(mappingRequests) throw new Error('Wait for the selected records to load.');
      reviews = [];let offset = 0;
      for(const count of batches) {
        if(!view.valid()) return;
        if(resume && offset+count<=resume.importedEntries+resume.skippedEntries) {reviews.push(Array.from({length:count},()=>({status:'COMMITTED'})));offset+=count;continue;}
        const rows = plan.rows.slice(offset,offset+count),reply = resume ? await api.request(`/imports/jobs/${resume.id}/preview`,{method:'POST',body:{rows}})
          : await api.request('/imports/toggl/preview',{method:'POST',body:{sourceKey:plan.sourceKey,mappings:plan.mappings,rows}});
        if(!view.valid()) return;reviews.push(reply.data.rows);offset += count;feedback.textContent = `Reviewed ${offset} of ${plan.rows.length} entries.`;
      }
      matchReview();
    }),{submit:review,feedback,write:true}),'btn btn-primary');review.disabled = !view.canWrite;
    work.append(feedback,review,button('Back to source rows',()=>{if(!view.busy) sourceReview();}));
  }
  function matchReview() {
    work.replaceChildren(node('h2','Confirm import'));const flat = reviews.flat();
    const matches = flat.map((value,index) => ({value,index})).filter(({value}) => value.status === 'REVIEW_MATCHES' || value.status === 'SOURCE_CHANGED');
    const reused = flat.filter(value => value.status.startsWith('REUSED')).length;
    work.append(node('p',`${plan.rows.length} selected entries · ${nanosText(BigInt(plan.selectedNanos))} · ${reused} already imported · ${matches.length} need review.`));
    const list = node('div');work.append(list);let page = 0;
    function draw() {
      list.replaceChildren();
      for(const {value,index} of matches.slice(page*50,(page+1)*50)) {
        const row = plan.rows[index], card = node('div',undefined,'card account-card');
        card.append(node('h3',row.summary || 'Untitled entry'),node('p',`${instantText(row.start,zone)} → ${instantText(row.end,zone)}`));
        if(value.status === 'SOURCE_CHANGED') card.append(node('p','The source entry ID has changed. Go back and exclude it or resolve the source change.','alert alert-warning'));
        else {
          const canReuse = value.matches.every(group => group.length > 0);
          const choice = field(card,'Matching activities',row.duplicateDecision || '',{choices:[['','Choose…'],['CREATE_DISTINCT','Create a distinct entry'],...(canReuse ? [['REUSE','Reuse existing daily activities']] : [])]});
          const parts = node('div');card.append(parts);
          function showParts() {
            parts.replaceChildren();row.duplicateDecision = choice.value;delete row.reuseActivities;
            if(choice.value === 'REUSE') {
              row.reuseActivities = [];
              value.matches.forEach((group,part) => {
                const select = field(parts,`Daily activity ${part+1}`,group.length === 1 ? group[0].id : '',{choices:[['','Choose activity'],...group.map(item => [item.id,item.id])]});
                const update = () => {row.reuseActivities[part] = group.find(item => item.id === select.value) || null;};select.addEventListener('change',update);update();
              });
            }
          }
          choice.addEventListener('change',showParts);showParts();
        }
        list.append(card);
      }
      if(matches.length > 50) {
        const previous = button('Previous matches',()=>{page--;draw();});previous.disabled = page===0;
        const next = button('Next matches',()=>{page++;draw();});next.disabled = (page+1)*50>=matches.length;list.append(previous,next);
      }
    }
    draw();const feedback = node('p','','form-feedback');feedback.setAttribute('role','status');
    const start = button(resume ? 'Resume import' : 'Import selected entries',()=>void view.run(async()=>{
      if(matches.some(({value,index})=>value.status==='SOURCE_CHANGED' || !plan.rows[index].duplicateDecision || plan.rows[index].duplicateDecision==='REUSE' && plan.rows[index].reuseActivities?.some(item=>!item))) throw new Error('Resolve every matching entry before importing.');
      const initial = {label:resume?.label || 'Toggl Track import',sourceKey:plan.sourceKey,sourceZone:sourceZone.value.trim(),sourceDateFormat:dateFormat.value,
        fileFingerprint:fingerprint,selectionFingerprint:plan.selectionFingerprint,mappings:plan.mappings,selectedEntries:plan.selectedEntries,selectedNanos:plan.selectedNanos};
      const enabled = [...work.querySelectorAll('input,select,button')].filter(value=>!value.disabled && value!==stop);
      const sourceControls = [...upload.value.querySelectorAll('input,select,button')].filter(value=>!value.disabled);
      for(const value of [...enabled,...sourceControls]) value.disabled = true;
      try {
        createKey ||= crypto.randomUUID();
        const loaded = resume ? await api.request(`/imports/jobs/${resume.id}`) : await api.request('/imports/toggl/jobs',{method:'POST',key:createKey,body:initial});
        if(!view.valid()) return;resume = loaded.data;pause = false;await importLoop(feedback);
      } finally {if(view.valid()) for(const value of [...enabled,...sourceControls]) value.disabled = false;}
    },{submit:start,feedback,write:true}),'btn btn-primary');start.disabled = !view.canWrite;
    const stop = button('Pause after current batch',()=>{pause = true;feedback.textContent = 'Pausing after the current batch…';});
    work.append(feedback,start,stop,button('Back to mappings',()=>{if(!view.busy) mappingReview();}));
  }
  async function importLoop(feedback) {
    let committed = resume.importedEntries + resume.skippedEntries, offset = 0;
    for(let index=0;index<batches.length;index++) {
      const count = batches[index];
      if(offset+count<=committed) {offset+=count;continue;}
      if(offset!==committed || index!==resume.nextBatch) throw new Error('Batch layout changed. Reselect the original file and mappings.');
      if(pause || !view.valid()) break;
      const rows = plan.rows.slice(offset,offset+count);
      const fresh = await api.request(`/imports/jobs/${resume.id}/preview`,{method:'POST',body:{rows}});if(!view.valid()) return;
      // A new job changes the root token. Compare actual row matches, not that transport token.
      const previous = reviews[index].map(value=>({...value}));
      if(signature(fresh.data.rows)!==signature(previous)) throw new Error('Matching records changed since review. Reload this import and review current matches before continuing.');
      const body = {rows,reviewToken:fresh.data.reviewToken,finished:index===batches.length-1};
      try {await api.request(`/imports/jobs/${resume.id}/batches/${index}`,{method:'POST',body,timeout:60000});}
      catch(error) {
        if(!error.uncertain) throw error;
        const authority = await api.request(`/imports/jobs/${resume.id}`);if(!view.valid()) return;resume = authority.data;
        if(resume.nextBatch!==index+1) throw error;
      }
      if(!view.valid()) return;resume = (await api.request(`/imports/jobs/${resume.id}`)).data;if(!view.valid()) return;
      committed = resume.importedEntries+resume.skippedEntries;offset+=count;
      feedback.textContent = `${committed} of ${resume.selectedEntries} entries committed · ${nanosText(BigInt(resume.importedNanos))} imported · ${nanosText(BigInt(resume.skippedNanos))} reused.`;
    }
    if(!view.valid()) return;
    if(resume.state==='IMPORTED') {
      view.clean(upload.value);feedback.append(' Import complete.');void view.refreshProfile().catch(()=>{});
      work.replaceChildren(node('h2','Import complete'),feedback,button('Import another file',()=>{
        if(view.busy) return;resume = parsed = plan = null;reviews = [];batches = [];file.value = '';work.hidden = true;upload.feedback.textContent = '';file.focus();
      }));
    }
    else feedback.append(' Paused. Resume from Import history.');
    await loadHistory();
  }
  async function loadHistory(after=null) {
    const reply = await api.request(`/imports${after ? `?after=${encodeURIComponent(after)}` : ''}`);if(!view.valid()) return;
    historyItems = after ? [...historyItems,...reply.data.items] : reply.data.items;historyAfter = reply.data.nextAfter;
    historyList.replaceChildren();
    const rows = [...historyItems].sort((a,b)=>b.createdAt.localeCompare(a.createdAt)).map(job=>{
      const actions = node('div',undefined,'token-actions');
      if(job.state==='IMPORTING') actions.append(button('Resume',()=>void view.run(()=>locked(async()=>{
        resume = (await api.request(`/imports/jobs/${job.id}`)).data;if(!view.valid()) return;
        sourceZone.value = resume.sourceZone;dateFormat.value = resume.sourceDateFormat || 'ISO';parsed = plan = null;work.hidden = true;file.value = '';createKey = null;
        upload.feedback.textContent = 'Choose the original CSV, source user and row exclusions to resume.';file.focus();
      }),{write:true})));
      if(['IMPORTING','IMPORTED','BLOCKED','UNDOING'].includes(job.state)) {
        const undo = button(job.state==='UNDOING' ? 'Resume Undo' : 'Undo import',()=>void view.run(()=>undoReview(job)));undo.disabled = !view.canWrite;actions.append(undo);
      }
      actions.append(button('Download audit',()=>void view.run(()=>download(job))));
      return [job.label,instantText(job.createdAt,zone),job.state,`${job.importedEntries} imported · ${job.skippedEntries} reused`,nanosText(BigInt(job.importedNanos)),actions];
    });
    historyList.append(rows.length ? table(['Import','Created','State','Entries','Imported time','Actions'],rows) : node('p','No imports yet.','hint'));
    if(historyAfter) historyList.append(button('Load more imports',()=>void view.run(()=>loadHistory(historyAfter))));
  }
  async function undoReview(job) {
    const loaded = await api.request(`/imports/jobs/${job.id}`);if(!view.valid()) return;
    const current = loaded.data;operations.replaceChildren(node('h3',`Undo ${current.label}`),node('p',`${current.importedEntries} created entries · ${current.activityCount} daily activities · ${current.catalogCount} catalog records reviewed. Reused records stay. Edited or referenced records require review.`));
    const feedback = node('p','','form-feedback');feedback.setAttribute('role','status');
    const confirm = button(current.state==='UNDOING' ? 'Continue Undo' : 'Confirm Undo',()=>void view.run(async()=>{
      if(current.state!=='UNDOING') await api.request(`/imports/jobs/${current.id}/undo`,{method:'POST',key:crypto.randomUUID(),etag:loaded.etag});
      if(view.valid()) {undoPause = false;operations.replaceChildren(feedback,pauseUndo(feedback));await undoLoop(current.id,feedback);}
    },{submit:confirm,feedback,write:true}),'btn btn-danger');
    operations.append(feedback,confirm,button('Cancel',()=>operations.replaceChildren()));
  }
  async function undoLoop(id,feedback) {
    while(view.valid()) {
      if(undoPause) {feedback.textContent = 'Undo paused. Continue from Import history.';await loadHistory();return;}
      const current = (await api.request(`/imports/jobs/${id}`)).data;if(!view.valid()) return;
      if(current.state.startsWith('UNDONE')) {feedback.textContent = `${current.state==='UNDONE' ? 'Undo complete' : 'Undo completed with retained records'} · ${current.undoRemoved} removed · ${current.undoRetained} retained.`;await loadHistory();void view.refreshProfile().catch(()=>{});return;}
      const preview = (await api.request(`/imports/jobs/${id}/undo/preview`)).data;if(!view.valid()) return;
      if(undoPause) continue;
      const conflicts = preview.targets.filter(target=>!['DELETE','MISSING','REUSED'].includes(target.status));
      if(conflicts.length) {undoConflicts(id,current,preview,conflicts);return;}
      await api.request(`/imports/jobs/${id}/undo/batches/${current.undoNextBatch}`,{method:'POST',body:{reviewToken:preview.reviewToken,choices:{}},timeout:60000});
      if(view.valid()) feedback.textContent = `Undo in progress · ${current.undoRemoved} records removed.`;
    }
  }
  function pauseUndo(feedback) {return button('Pause Undo',()=>{undoPause = true;feedback.textContent = 'Pausing after the current batch…';});}
  function undoConflicts(id,job,preview,conflicts) {
    operations.replaceChildren(node('h3','Review records retained or changed since import'));const choices = {};
    for(const target of conflicts) {
      const record = target.current;
      const title = `${names[target.recordType] || 'Activity'}: ${record?.name || record?.summary || target.recordId}`;
      const reasons = {REVIEW:'Edited since import.',REFERENCED:'Used by another import. Undo that import first or keep this record.',RUNNING:'This activity is running. Stop it first or keep it.',DEPENDENCY:'Other records still use this catalog record. Keep it or remove those dependencies separately.'};
      const card = node('div',undefined,'card account-card');card.append(node('h4',title),node('p',reasons[target.status] || target.status));
      if(record?.start) card.append(node('p',`${instantText(record.start,zone)} → ${record.end ? instantText(record.end,zone) : 'Running'}`));
      const choice = field(card,'Undo decision','',{choices:[['','Choose…'],['KEEP','Keep this record'],...(target.status==='REVIEW' ? [['DELETE','Delete the reviewed edited record']] : [])]});
      choice.addEventListener('change',()=>{choices[target.key] = choice.value;});operations.append(card);
    }
    const feedback = node('p','','form-feedback');feedback.setAttribute('role','status');
    const apply = button('Apply decisions and continue Undo',()=>void view.run(async()=>{
      if(conflicts.some(target=>!choices[target.key])) throw new Error('Choose a resolution for every record.');
      await api.request(`/imports/jobs/${id}/undo/batches/${job.undoNextBatch}`,{method:'POST',body:{reviewToken:preview.reviewToken,choices},timeout:60000});
      if(view.valid()) {undoPause = false;operations.replaceChildren(feedback,pauseUndo(feedback));await undoLoop(id,feedback);}
    },{submit:apply,feedback,write:true}),'btn btn-danger');
    operations.append(feedback,apply,button('Pause Undo',()=>{operations.replaceChildren(node('p','Undo paused. Continue from Import history.'));}));
  }
  async function download(job) {
    const details = (await api.request(`/imports/jobs/${job.id}`)).data, items = [];let offset = 0;
    do {
      const reply = await api.request(`/imports/jobs/${job.id}/items?offset=${offset}`);if(!view.valid()) return;
      items.push(...reply.data.items);offset = reply.data.nextOffset;
    } while(offset!==null);
    if(!view.valid()) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify({job:details,items},null,2)],{type:'application/json'}));
    const link = node('a');link.href = url;link.download = `timekeeper-import-${job.id}.json`;link.click();URL.revokeObjectURL(url);
  }
}

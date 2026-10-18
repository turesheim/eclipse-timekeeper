import {node, button, field, section, form, table} from './account-ui.js';
import {instantText, nanosText} from './model.js';

const names = {CLIENT:'Client', PROJECT:'Project', TASK:'Task', LABEL:'Label', WORK_SESSION:'Work session'};
const signature = value => JSON.stringify(value);

export async function renderImports(view, zone) {
  let parsed = null, fingerprint = null, plan = null, batches = [], reviews = [], resume = null, selectedPerson = null;
  let worker = null, cancelWorker = null, pause = false, undoPause = false, historyAfter = null, historyItems = [], createKey = null;
  const api = view.session, catalog = {}, clientSourceNames = new Map();
  const upload = form(view, 'Import from Toggl Track', 'Read CSV', read);
  upload.value.append(node('p', 'Detailed Report or Legacy CSV. Use Legacy to retain source client names. Import completed entries as work sessions or activities.'));
  const importMode = field(upload.value, 'Import as', 'WORK_SESSION', {choices:[['WORK_SESSION','Work sessions'],['ACTIVITY','Activities']]});
  const file = field(upload.value, 'Toggl CSV', '', {type:'file'});file.accept = '.csv,text/csv';
  const sourceZone = field(upload.value, 'Source time zone', zone);
  const dateFormat = field(upload.value, 'Source date format', 'ISO', {choices:[['ISO','YYYY-MM-DD'],['DMY','DD/MM/YYYY'],['MDY','MM/DD/YYYY']]});
  const stopParsing = button('Cancel reading', () => cancelWorker?.());stopParsing.hidden = true;upload.value.append(stopParsing);upload.finish();
  const work = section(view, 'Review import');work.hidden = true;
  const history = section(view, 'Import history'), historyList = node('div');history.append(historyList);
  const operations = node('div');history.append(operations);
  const downloadUrls = new Set();
  upload.value.addEventListener('change', () => {cancelWorker?.();parsed = plan = null;work.hidden = true;});
  view.cleanup.add(() => {
    cancelWorker?.();worker?.terminate();worker = null;
    if(parsed) parsed.rows.length = 0;if(plan) plan.rows.length = 0;
    parsed = plan = resume = null;clientSourceNames.clear();reviews.length = batches.length = historyItems.length = 0;file.value = '';fingerprint = createKey = null;
    for(const url of downloadUrls) URL.revokeObjectURL(url);downloadUrls.clear();selectedPerson = null;
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
  function sourceClientName(definition) {return clientSourceNames.get(definition.key) || 'Entries without a client';}
  async function read() {
    return locked(async()=>{
    if(!file.files[0]) throw new Error('Choose a CSV file.');
    work.hidden = true;plan = null;reviews = [];batches = [];parsed = null;createKey = null;
    const result = await runWorker({file:file.files[0],options:{zone:sourceZone.value.trim(),dateFormat:dateFormat.value}});
    if(!view.valid()) return;
    parsed = result.parsed;fingerprint = result.fileFingerprint;
    if(resume && fingerprint !== resume.fileFingerprint) throw new Error('Choose the unchanged CSV used by this import.');
    work.hidden = false;sourceReview();upload.feedback.textContent = `${parsed.rows.length} source rows read. Original file unchanged.`;
    });
  }
  function sourceReview() {
    work.replaceChildren(node('h2','Review import'));
    const source = field(work, 'Source user', parsed.people.length === 1 ? parsed.people[0].key : '', {choices:[['','Choose source user'],...parsed.people.map(person => [person.key,[person.name,person.email].filter(Boolean).join(' · ')])]});
    if(parsed.unsupported.length) work.append(node('p', `Unsupported metadata: ${parsed.unsupported.join(', ')}. These columns will not be imported.`, 'alert alert-info'));
    const fallbackClient = field(work, 'Client for entries without a client', 'Imported from Toggl', {maxLength:1000});
    if(importMode.value==='WORK_SESSION') work.append(node('p','Each entry becomes one work session. Descriptions become titles and are retained in full as notes. Source projects suggest client names when Client is missing; review each client below. Tags become work-session labels; tasks remain in the import audit only.'));
    const fallbackProject = importMode.value==='ACTIVITY' ? field(work, 'Project for entries without a project', 'Imported time', {maxLength:1000}) : null;
    const decisions = node('div'), invalidSummary = node('p');decisions.append(invalidSummary);
    const invalid = field(decisions, 'Exclude invalid rows after reviewing the diagnostics', false, {type:'checkbox'});
    const duplicates = field(decisions, 'Keep indistinguishable source rows as separate entries', false, {type:'checkbox'});
    const rowList = node('div');work.append(rowList);let page = 0;
    function showRows() {
      selectedPerson = source.value || null;
      rowList.replaceChildren();const selected = parsed.rows.filter(row => row.personKey === source.value || !row.personKey);
      const errors = selected.filter(row => row.errors.length).length;
      invalidSummary.hidden = !errors;invalidSummary.textContent = `${errors} invalid ${errors===1 ? 'row cannot' : 'rows cannot'} be imported.`;
      invalid.parentElement.hidden = !errors;
      duplicates.parentElement.hidden = !selected.some(row=>row.ambiguousDuplicate && !row.errors.length);
      rowList.append(node('p', `${selected.length} source rows · ${errors} invalid. Displayed times use ${zone}.`));
      const rows = selected.slice(page * 100, (page + 1) * 100).map(row => {
        const select = node('input');select.type = 'checkbox';select.className = 'form-check-input';select.checked = !row.excluded && !row.errors.length;select.disabled = !!row.errors.length;
        select.setAttribute('aria-label', `Include source line ${row.line}`);select.addEventListener('change', () => {row.excluded = !select.checked;view.dirty.add(upload.value);});
        return [select,String(row.line),row.start ? instantText(row.start,zone) : '',row.end ? instantText(row.end,zone) : '',row.durationNanos === undefined ? '' : nanosText(row.durationNanos),...(importMode.value==='WORK_SESSION' ? [row.summary.trim().slice(0,1000) || 'Imported work session'] : []),row.summary || '',[...row.errors,...row.warnings,...(row.ambiguousDuplicate ? ['Indistinguishable duplicate source row.'] : [])].join(' ')];
      });
      rowList.append(table(['Include','Line','Start','End','Duration',...(importMode.value==='WORK_SESSION' ? ['Work-session title'] : []),'Description','Diagnostics'],rows));
      if(selected.length > 100) {
        const previous = button('Previous rows', () => {page--;showRows();});previous.disabled = page === 0;
        const next = button('Next rows', () => {page++;showRows();});next.disabled = (page + 1) * 100 >= selected.length;
        rowList.append(previous,node('span',` Page ${page+1} `),next);
      }
    }
    source.addEventListener('change', () => {page = 0;invalid.checked = duplicates.checked = false;showRows();});showRows();
    const feedback = node('p','','form-feedback');feedback.setAttribute('role','status');
    const next = button('Review mappings', () => void view.run(async () => {
      if(!source.value) throw new Error('Choose the source user.');
      const selected = parsed.rows.filter(row => row.personKey === source.value || !row.personKey);
      if(selected.some(row => row.errors.length) && !invalid.checked) {invalid.focus();throw new Error('Review and explicitly exclude invalid rows before continuing.');}
      if(selected.some(row => row.ambiguousDuplicate && !row.excluded && !row.errors.length) && !duplicates.checked) throw new Error('Review indistinguishable source rows, then keep them separately or exclude them.');
      await locked(async () => {
      for(const row of selected) row.duplicateReviewed = duplicates.checked;
      selectedPerson = source.value;
      const result = await runWorker({operation:'plan',rows:parsed.rows,personKey:source.value,reportZone:resume?.reportZone || zone,
        options:{mode:importMode.value,fallbackClient:fallbackClient.value,fallbackProject:fallbackProject?.value,
          mapWorkSessionTags:!resume || resume.mappings.some(value=>value.resourceType==='LABEL'),
          mapWorkSessionProjects:!resume || resume.mappings.some(value=>value.resourceType==='PROJECT')}});
      if(!view.valid()) return;plan = result.plan;batches = result.batches;reviews = [];
      if(resume) {
        if(plan.mode !== (resume.mode || 'ACTIVITY') || plan.sourceKey !== resume.sourceKey || plan.selectedEntries !== resume.selectedEntries || plan.selectedNanos !== resume.selectedNanos
          || resume.selectionFingerprint && plan.selectionFingerprint !== resume.selectionFingerprint) throw new Error('Choose the original source user and the same row exclusions to resume this import.');
        if(zone !== resume.reportZone) throw new Error('The account time zone changed. Undo this job or start a newly reviewed import.');
        plan.mappings = structuredClone(resume.mappings);
      }
      mappingReview();
      });
    },{submit:next,feedback,write:true}),'btn btn-primary');next.disabled = !view.canWrite;
    work.append(decisions,feedback,next,button('Download preview diagnostics',downloadPreview));
  }
  function mappingReview() {
    work.replaceChildren(node('h2','Review mappings'),node('p',`${plan.selectedEntries} ${plan.mode==='WORK_SESSION' ? 'work sessions' : 'activity entries'} · ${nanosText(BigInt(plan.selectedNanos))} · ${plan.mappings.length} catalog mappings`));
    if(resume) work.append(node('p','The original mapping plan is retained while resuming.','alert alert-info'));
    if(plan.mode==='WORK_SESSION' && plan.rows.some(row=>row.summary.trim().length>1000)) work.append(node('p','Long descriptions use the first 1,000 characters as the title. The full description is retained as notes.','alert alert-info'));
    const list = node('div');work.append(list);const definitions = new Map(plan.mappings.map(value => [value.key,value]));
    clientSourceNames.clear();
    for(const row of plan.rows) {
      const key = row.clientKey || definitions.get(row.projectKey)?.parentKey;
      if(!clientSourceNames.has(key)) clientSourceNames.set(key,row.client || (plan.mode==='WORK_SESSION' && row.project) || 'Entries without a client');
    }
    const paths = {CLIENT:'clients',PROJECT:'projects',TASK:'tasks',LABEL:'labels'};
    let mappingRequests = 0;
    function draw() {
      list.replaceChildren();
      const clients = node('div'), other = node('div');
      list.append(node('h3','Client mappings'),clients);
      if(plan.mappings.some(value=>value.resourceType!=='CLIENT')) list.append(node('h3','Other mappings'),other);
      for(const definition of plan.mappings) {
        const parent = definitions.get(definition.parentKey), type = definition.resourceType;
        const choices = (catalog[type] || []).filter(value => !parent || parent.action === 'REUSE' && value[type === 'PROJECT' ? 'clientId' : 'projectId'] === parent.id)
          .sort((a,b)=>(a.name || a.summary).localeCompare(b.name || b.summary));
        if(definition.action === 'REUSE' && !choices.some(value => value.id === definition.id) && !resume) {
          definition.action = 'CREATE';delete definition.id;delete definition.etag;
        }
        const row = node('div',undefined,'import-mapping');
        const select = field(row,type==='CLIENT' ? `Timekeeper client for ${sourceClientName(definition)}` : `${names[type]}: ${definition.name}`,definition.action === 'CREATE' ? '' : definition.id,
          {choices:[['',type==='CLIENT' ? 'Create a new client' : 'Create new'],...choices.map(value => [value.id,`${type==='CLIENT' ? 'Use existing:' : 'Reuse'} ${value.name || value.summary}`])]});
        select.disabled = !!resume;
        const creation = node('div');creation.hidden = definition.action !== 'CREATE';row.append(creation);
        const name = field(creation,`New ${names[type].toLowerCase()} name`,definition.name,{maxLength:1000});name.disabled = definition.action !== 'CREATE' || !!resume;
        name.addEventListener('input', () => {definition.name = name.value;view.dirty.add(upload.value);});
        if(type === 'CLIENT' || type === 'LABEL') {
          const color = field(creation,`${names[type]} color`,definition.color || '#2563eb',{type:'color'});color.disabled = definition.action !== 'CREATE' || !!resume;
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
        (type==='CLIENT' ? clients : other).append(row);
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
          : await api.request('/imports/toggl/preview',{method:'POST',body:{mode:plan.mode,sourceKey:plan.sourceKey,mappings:plan.mappings,rows}});
        if(!view.valid()) return;reviews.push(reply.data.rows);offset += count;feedback.textContent = `Reviewed ${offset} of ${plan.rows.length} entries.`;
      }
      matchReview();
    }),{submit:review,feedback,write:true}),'btn btn-primary');review.disabled = !view.canWrite;
    work.append(feedback,review,button('Back to source rows',()=>{if(!view.busy) sourceReview();}),button('Download preview diagnostics',downloadPreview));
  }
  function matchReview() {
    work.replaceChildren(node('h2','Confirm import'));const flat = reviews.flat();
    work.append(node('h3','Client mappings'),table(['Toggl client','Timekeeper client'],plan.mappings.filter(value=>value.resourceType==='CLIENT').map(value=>[
      sourceClientName(value),value.action==='REUSE' ? `Use existing: ${catalog.CLIENT.find(client=>client.id===value.id)?.name || value.id}` : `Create new: ${value.name}`
    ])));
    const matches = flat.map((value,index) => ({value,index})).filter(({value}) => value.status === 'REVIEW_MATCHES' || value.status === 'SOURCE_CHANGED');
    const reused = flat.filter(value => value.status.startsWith('REUSED')).length;
    const edited = flat.map((value,index)=>({value,index})).filter(({value})=>value.status==='REUSED_EDITED');
    work.append(node('p',`${plan.rows.length} selected entries · ${nanosText(BigInt(plan.selectedNanos))} · ${reused} already imported · ${matches.length} need review.`));
    if(edited.length) {
      const details = node('details');details.append(node('summary',`${edited.length} previously imported entries have edits or missing daily parts. Existing records will be reused without overwriting them.`));
      details.append(table(['Source entry','Source start','Source end','Current daily records'],edited.slice(0,50).map(({value,index})=>[
        plan.rows[index].summary || `Entry ${index+1}`,instantText(plan.rows[index].start,zone),instantText(plan.rows[index].end,zone),
        (value.workSessions || value.activities).map(activity=>`${instantText(activity.start,zone)} → ${activity.end ? instantText(activity.end,zone) : 'Running'}`).join('; ')
      ])));
      if(edited.length>50) details.append(node('p','Showing the first 50. Download preview diagnostics for the complete list.'));work.append(details);
    }
    const list = node('div');work.append(list);let page = 0;
    function draw() {
      list.replaceChildren();
      for(const {value,index} of matches.slice(page*50,(page+1)*50)) {
        const row = plan.rows[index], card = node('div',undefined,'card account-card');
        card.append(node('h3',row.summary || 'Untitled entry'),node('p',`${instantText(row.start,zone)} → ${instantText(row.end,zone)}`));
        if(value.status === 'SOURCE_CHANGED') card.append(node('p','The source entry ID has changed. Go back and exclude it or resolve the source change.','alert alert-warning'));
        else {
          const canReuse = value.matches.every(group => group.length > 0);
          const choice = field(card,plan.mode==='WORK_SESSION' ? 'Matching work sessions' : 'Matching activities',row.duplicateDecision || '',{choices:[['','Choose…'],['CREATE_DISTINCT','Create a distinct entry'],...(canReuse ? [['REUSE',plan.mode==='WORK_SESSION' ? 'Reuse existing work session' : 'Reuse existing daily activities']] : [])]});
          const parts = node('div');card.append(parts);
          function showParts() {
            parts.replaceChildren();row.duplicateDecision = choice.value;delete row.reuseActivities;delete row.reuseWorkSessions;
            if(choice.value === 'REUSE') {
              const reuseField = plan.mode==='WORK_SESSION' ? 'reuseWorkSessions' : 'reuseActivities';row[reuseField] = [];
              value.matches.forEach((group,part) => {
                const select = field(parts,plan.mode==='WORK_SESSION' ? 'Work session' : `Daily activity ${part+1}`,group.length === 1 ? group[0].id : '',{choices:[['','Choose record'],...group.map(item => [item.id,item.id])]});
                const update = () => {row[reuseField][part] = group.find(item => item.id === select.value) || null;};select.addEventListener('change',update);update();
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
      if(matches.some(({value,index})=>value.status==='SOURCE_CHANGED' || !plan.rows[index].duplicateDecision || plan.rows[index].duplicateDecision==='REUSE' && (plan.rows[index].reuseWorkSessions || plan.rows[index].reuseActivities)?.some(item=>!item))) throw new Error('Resolve every matching entry before importing.');
      const initial = {mode:plan.mode,label:resume?.label || 'Toggl Track import',sourceKey:plan.sourceKey,sourceZone:sourceZone.value.trim(),sourceDateFormat:dateFormat.value,
        fileFingerprint:fingerprint,selectionFingerprint:plan.selectionFingerprint,mappings:plan.mappings,selectedEntries:plan.selectedEntries,selectedNanos:plan.selectedNanos,sourceDiagnostics:diagnostics()};
      stop.hidden = false;
      const enabled = [...work.querySelectorAll('input,select,button')].filter(value=>!value.disabled && value!==stop);
      const sourceControls = [...upload.value.querySelectorAll('input,select,button')].filter(value=>!value.disabled);
      for(const value of [...enabled,...sourceControls]) value.disabled = true;
      try {
        createKey ||= crypto.randomUUID();
        const loaded = resume ? await api.request(`/imports/jobs/${resume.id}`) : await api.request('/imports/toggl/jobs',{method:'POST',key:createKey,body:initial});
        if(!view.valid()) return;resume = loaded.data;pause = false;await importLoop(feedback);
      } finally {if(view.valid()) {for(const value of [...enabled,...sourceControls]) value.disabled = false;stop.hidden = true;}}
    },{submit:start,feedback,write:true}),'btn btn-primary');start.disabled = !view.canWrite;
    const stop = button('Pause after current batch',()=>{pause = true;feedback.textContent = 'Pausing after the current batch…';});stop.hidden = true;
    work.append(feedback,start,stop,button('Back to mappings',()=>{if(!view.busy) mappingReview();}),button('Download preview diagnostics',downloadPreview));
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
      work.replaceChildren(node('h2','Import complete'),feedback,button('Download preview diagnostics',downloadPreview),button('Import another file',()=>{
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
        importMode.value = resume.mode || 'ACTIVITY';sourceZone.value = resume.sourceZone;dateFormat.value = resume.sourceDateFormat || 'ISO';parsed = plan = null;work.hidden = true;file.value = '';createKey = null;
        upload.feedback.textContent = 'Choose the original CSV, source user and row exclusions to resume.';file.focus();
      }),{write:true})));
      if(['IMPORTING','IMPORTED','BLOCKED','UNDOING'].includes(job.state)) {
        const undo = button(job.state==='UNDOING' ? 'Resume Undo' : 'Undo import',()=>void view.run(()=>undoReview(job)));undo.disabled = !view.canWrite;actions.append(undo);
      }
      actions.append(button('Download audit',()=>void view.run(()=>download(job))));
      return [`${job.label} · ${job.mode==='WORK_SESSION' ? 'Work sessions' : 'Activities'}`,instantText(job.createdAt,zone),job.state,`${job.importedEntries} imported · ${job.skippedEntries} reused`,nanosText(BigInt(job.importedNanos)),actions];
    });
    historyList.append(rows.length ? table(['Import','Created','State','Entries','Imported time','Actions'],rows) : node('p','No imports yet.','hint'));
    if(historyAfter) historyList.append(button('Load more imports',()=>void view.run(()=>loadHistory(historyAfter))));
  }
  async function undoReview(job) {
    const loaded = await api.request(`/imports/jobs/${job.id}`);if(!view.valid()) return;
    const current = loaded.data;operations.replaceChildren(node('h3',`Undo ${current.label}`),node('p',`${current.importedEntries} created entries · ${current.mode==='WORK_SESSION' ? `${current.workSessionCount || 0} work sessions` : `${current.activityCount} daily activities`} · ${current.catalogCount} catalog records reviewed. Reused records stay. Edited or referenced records require review.`));
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
      const title = `${names[target.recordType] || 'Activity'}: ${record?.name || record?.title || record?.summary || target.recordId}`;
      const reasons = {REVIEW:'Edited since import.',REFERENCED:'Used by another import. Undo that import first or keep this record.',RUNNING:'This record is running. Stop it first or keep it.',DEPENDENCY:'Other records still use this catalog record. Keep it or remove those dependencies separately.'};
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
    const details = (await api.request(`/imports/jobs/${job.id}`)).data, chunks = [`{"job":${JSON.stringify(details)},"items":[`];let offset = 0,first = true;
    do {
      const reply = await api.request(`/imports/jobs/${job.id}/items?offset=${offset}`);if(!view.valid()) return;
      if(reply.data.items.length) {chunks.push((first ? '' : ',')+JSON.stringify(reply.data.items).slice(1,-1));first = false;}offset = reply.data.nextOffset;
    } while(offset!==null);
    if(!view.valid()) return;
    chunks.push(']}');saveJson(chunks,`timekeeper-import-${job.id}.json`);
  }
  function diagnostics() {
    const selected = parsed.rows.filter(row=>row.personKey===selectedPerson || !row.personKey);
    return {sourceRows:parsed.rows.length,rejectedRows:selected.filter(row=>row.errors.length).length,
      excludedRows:selected.filter(row=>!row.errors.length && row.excluded).length,unselectedRows:parsed.rows.length-selected.length,unsupportedFields:parsed.unsupported};
  }
  function downloadPreview() {
    if(!view.valid() || !parsed) return;
    const rows = parsed.rows.map(row=>({line:row.line,selected:row.personKey===selectedPerson || !row.personKey,excluded:!!row.excluded,
      errors:row.errors,warnings:row.warnings,ambiguousDuplicate:!!row.ambiguousDuplicate}));
    saveJson([JSON.stringify({mode:importMode.value,sourceZone:sourceZone.value.trim(),sourceDateFormat:dateFormat.value,diagnostics:diagnostics(),rows,
      serverReview:reviews.flat().map((value,index)=>({entry:index+1,...value}))},null,2)],'timekeeper-import-diagnostics.json');
  }
  function saveJson(chunks,filename) {
    const url = URL.createObjectURL(new Blob(chunks,{type:'application/json'}));downloadUrls.add(url);
    const link = node('a');link.href = url;link.download = filename;link.hidden = true;document.body.append(link);link.click();link.remove();
    setTimeout(()=>{URL.revokeObjectURL(url);downloadUrls.delete(url);},1000);
  }
}

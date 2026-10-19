import { getLocale } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { node, button, field, section, form, table } from './account-ui.js';
import { instantText, nanosText } from './model.js';

const names = { CLIENT: i18nText("ui.client"), PROJECT: i18nText("ui.project"), TASK: i18nText("ui.task"), LABEL: i18nText("ui.label"), WORK_SESSION: i18nText("ui.work_session_21aa04") };
const signature = (value) => JSON.stringify(value);

export async function renderImports(view, zone) {
  let parsed = null,fingerprint = null,plan = null,batches = [],reviews = [],resume = null,selectedPerson = null;
  let worker = null,cancelWorker = null,pause = false,undoPause = false,historyAfter = null,historyItems = [],createKey = null;
  const api = view.session,catalog = {},clientSourceNames = new Map();
  const upload = form(view, i18nText("ui.import_from_toggl_track"), i18nText("ui.read_csv"), read);
  upload.value.append(node('p', i18nText("ui.detailed_report_or_legacy_csv_use_legacy_to_retain_source_client_names_import_complet")));
  const importMode = field(upload.value, i18nText("ui.import_as"), 'WORK_SESSION', { choices: [['WORK_SESSION', i18nText("ui.work_sessions")], ['ACTIVITY', i18nText("ui.activities")]] });
  const file = field(upload.value, i18nText("ui.toggl_csv"), '', { type: 'file' });file.accept = '.csv,text/csv';
  const sourceZone = field(upload.value, i18nText("ui.source_time_zone"), zone);
  const dateFormat = field(upload.value, i18nText("ui.source_date_format"), 'ISO', { choices: [['ISO', 'YYYY-MM-DD'], ['DMY', 'DD/MM/YYYY'], ['MDY', 'MM/DD/YYYY']] });
  const stopParsing = button(i18nText("ui.cancel_reading"), () => cancelWorker?.());stopParsing.hidden = true;upload.value.append(stopParsing);upload.finish();
  const work = section(view, i18nText("ui.review_import"));work.hidden = true;
  const history = section(view, i18nText("ui.import_history")),historyList = node('div');history.append(historyList);
  const operations = node('div');history.append(operations);
  const downloadUrls = new Set();
  upload.value.addEventListener('change', () => {cancelWorker?.();parsed = plan = null;work.hidden = true;});
  view.cleanup.add(() => {
    cancelWorker?.();worker?.terminate();worker = null;
    if (parsed) parsed.rows.length = 0;if (plan) plan.rows.length = 0;
    parsed = plan = resume = null;clientSourceNames.clear();reviews.length = batches.length = historyItems.length = 0;file.value = '';fingerprint = createKey = null;
    for (const url of downloadUrls) URL.revokeObjectURL(url);downloadUrls.clear();selectedPerson = null;
  });
  await Promise.all(Object.entries({ CLIENT: 'clients', PROJECT: 'projects', TASK: 'tasks', LABEL: 'labels' }).map(async ([type, path]) => {
    const reply = await api.page(`/${path}`);if (view.valid()) catalog[type] = reply.items;
  }));
  if (!view.valid()) return;
  await loadHistory();

  function runWorker(data) {
    cancelWorker?.();
    return new Promise((resolve, reject) => {
      const current = new Worker(new URL('./toggl-worker.js', import.meta.url), { type: 'module' });worker = current;
      const finish = (value, error) => {
        current.terminate();if (worker === current) {worker = null;cancelWorker = null;stopParsing.hidden = true;}
        if (error) reject(new Error(error));else resolve(value);
      };
      cancelWorker = () => finish(null, i18nText("ui.reading_cancelled"));stopParsing.hidden = false;
      current.onmessage = (event) => finish(event.data, event.data.error);
      current.onerror = () => finish(null, i18nText("ui.the_csv_could_not_be_read_check_the_file_and_try_again"));
      current.postMessage({ ...data, locale: getLocale() });
    });
  }
  async function locked(work) {
    const enabled = [...upload.value.querySelectorAll('input,select,button'), ...workCardControls()].filter((value) => !value.disabled && value !== stopParsing);
    for (const value of enabled) value.disabled = true;
    try {return await work();} finally {if (view.valid()) for (const value of enabled) value.disabled = false;}
  }
  function workCardControls() {return work.querySelectorAll('input,select,button');}
  function sourceClientName(definition) {return clientSourceNames.get(definition.key) || i18nText("ui.entries_without_a_client");}
  async function read() {
    return locked(async () => {
      if (!file.files[0]) throw new Error(i18nText("ui.choose_a_csv_file"));
      work.hidden = true;plan = null;reviews = [];batches = [];parsed = null;createKey = null;
      const result = await runWorker({ file: file.files[0], options: { zone: sourceZone.value.trim(), dateFormat: dateFormat.value } });
      if (!view.valid()) return;
      parsed = result.parsed;fingerprint = result.fileFingerprint;
      if (resume && fingerprint !== resume.fileFingerprint) throw new Error(i18nText("ui.choose_the_unchanged_csv_used_by_this_import"));
      work.hidden = false;sourceReview();upload.feedback.textContent = i18nText("ui.source_rows_read_original_file_unchanged", { value0: parsed.rows.length });
    });
  }
  function sourceReview() {
    work.replaceChildren(node('h2', i18nText("ui.review_import")));
    const source = field(work, i18nText("ui.source_user"), parsed.people.length === 1 ? parsed.people[0].key : '', { choices: [['', i18nText("ui.choose_source_user")], ...parsed.people.map((person) => [person.key, [person.name, person.email].filter(Boolean).join(' · ')])] });
    if (parsed.unsupported.length) work.append(node('p', i18nText("ui.unsupported_metadata_these_columns_will_not_be_imported", { value0: parsed.unsupported.join(', ') }), 'alert alert-info'));
    const fallbackClient = field(work, i18nText("ui.client_for_entries_without_a_client"), i18nText("ui.imported_from_toggl"), { maxLength: 1000 });
    if (importMode.value === 'WORK_SESSION') work.append(node('p', i18nText("ui.each_entry_becomes_one_work_session_descriptions_become_work_session_summaries_source")));
    const fallbackProject = importMode.value === 'ACTIVITY' ? field(work, i18nText("ui.project_for_entries_without_a_project"), i18nText("ui.imported_time"), { maxLength: 1000 }) : null;
    const decisions = node('div'),invalidSummary = node('p');decisions.append(invalidSummary);
    const invalid = field(decisions, i18nText("ui.exclude_invalid_rows_after_reviewing_the_diagnostics"), false, { type: 'checkbox' });
    const duplicates = field(decisions, i18nText("ui.keep_indistinguishable_source_rows_as_separate_entries"), false, { type: 'checkbox' });
    const rowList = node('div');work.append(rowList);let page = 0;
    function showRows() {
      selectedPerson = source.value || null;
      rowList.replaceChildren();const selected = parsed.rows.filter((row) => row.personKey === source.value || !row.personKey);
      const errors = selected.filter((row) => row.errors.length).length;
      invalidSummary.hidden = !errors;invalidSummary.textContent = i18nText('import.invalid_rows',{count:errors});
      invalid.parentElement.hidden = !errors;
      duplicates.parentElement.hidden = !selected.some((row) => row.ambiguousDuplicate && !row.errors.length);
      rowList.append(node('p', i18nText("ui.source_rows_invalid_displayed_times_use", { value0: selected.length, value1: errors, value2: zone })));
      const rows = selected.slice(page * 100, (page + 1) * 100).map((row) => {
        const select = node('input');select.type = 'checkbox';select.className = 'form-check-input';select.checked = !row.excluded && !row.errors.length;select.disabled = !!row.errors.length;
        select.setAttribute('aria-label', i18nText("ui.include_source_line", { value0: row.line }));select.addEventListener('change', () => {row.excluded = !select.checked;view.dirty.add(upload.value);});
        return [select, String(row.line), row.start ? instantText(row.start, zone) : '', row.end ? instantText(row.end, zone) : '', row.durationNanos === undefined ? '' : nanosText(row.durationNanos), row.summary || '', [...row.errors, ...row.warnings, ...(row.ambiguousDuplicate ? [i18nText("ui.indistinguishable_duplicate_source_row")] : [])].join(' ')];
      });
      rowList.append(table([i18nText("ui.include"), i18nText("ui.line"), i18nText("ui.start"), i18nText("ui.end"), i18nText("ui.duration"), i18nText("ui.description"), i18nText("ui.diagnostics")], rows));
      if (selected.length > 100) {
        const previous = button(i18nText("ui.previous_rows"), () => {page--;showRows();});previous.disabled = page === 0;
        const next = button(i18nText("ui.next_rows"), () => {page++;showRows();});next.disabled = (page + 1) * 100 >= selected.length;
        rowList.append(previous, node('span', i18nText("ui.page", { value0: page + 1 })), next);
      }
    }
    source.addEventListener('change', () => {page = 0;invalid.checked = duplicates.checked = false;showRows();});showRows();
    const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
    const next = button(i18nText("ui.review_mappings"), () => void view.run(async () => {
      if (!source.value) throw new Error(i18nText("ui.choose_the_source_user"));
      const selected = parsed.rows.filter((row) => row.personKey === source.value || !row.personKey);
      if (selected.some((row) => row.errors.length) && !invalid.checked) {invalid.focus();throw new Error(i18nText("ui.review_and_explicitly_exclude_invalid_rows_before_continuing"));}
      if (selected.some((row) => row.ambiguousDuplicate && !row.excluded && !row.errors.length) && !duplicates.checked) throw new Error(i18nText("ui.review_indistinguishable_source_rows_then_keep_them_separately_or_exclude_them"));
      await locked(async () => {
        for (const row of selected) row.duplicateReviewed = duplicates.checked;
        selectedPerson = source.value;
        const result = await runWorker({ operation: 'plan', rows: parsed.rows, personKey: source.value, reportZone: resume?.reportZone || zone,
          options: { mode: importMode.value, fallbackClient: fallbackClient.value, fallbackProject: fallbackProject?.value,
            mapWorkSessionTags: !resume || resume.mappings.some((value) => value.resourceType === 'LABEL'),
            mapWorkSessionProjects: !resume || resume.mappings.some((value) => value.resourceType === 'PROJECT') } });
        if (!view.valid()) return;plan = result.plan;batches = result.batches;reviews = [];
        if (resume) {
          if (plan.mode !== (resume.mode || 'ACTIVITY') || plan.sourceKey !== resume.sourceKey || plan.selectedEntries !== resume.selectedEntries || plan.selectedNanos !== resume.selectedNanos ||
          resume.selectionFingerprint && plan.selectionFingerprint !== resume.selectionFingerprint) throw new Error(i18nText("ui.choose_the_original_source_user_and_the_same_row_exclusions_to_resume_this_import"));
          if (zone !== resume.reportZone) throw new Error(i18nText("ui.the_account_time_zone_changed_undo_this_job_or_start_a_newly_reviewed_import"));
          plan.mappings = structuredClone(resume.mappings);
        }
        mappingReview();
      });
    }, { submit: next, feedback, write: true }), "btn btn-primary");next.disabled = !view.canWrite;
    work.append(decisions, feedback, next, button(i18nText("ui.download_preview_diagnostics"), downloadPreview));
  }
  function mappingReview() {
    work.replaceChildren(node('h2', i18nText("ui.review_mappings")), node('p', i18nText("ui.catalog_mappings", { value0: plan.selectedEntries, value1: plan.mode === 'WORK_SESSION' ? i18nText("ui.work_sessions_26db04") : i18nText("ui.activity_entries"), value2: nanosText(BigInt(plan.selectedNanos)), value3: plan.mappings.length })));
    if (resume) work.append(node('p', i18nText("ui.the_original_mapping_plan_is_retained_while_resuming"), 'alert alert-info'));
    const list = node('div');work.append(list);const definitions = new Map(plan.mappings.map((value) => [value.key, value]));
    clientSourceNames.clear();
    for (const row of plan.rows) {
      const key = row.clientKey || definitions.get(row.projectKey)?.parentKey;
      if (!clientSourceNames.has(key)) clientSourceNames.set(key, row.client || plan.mode === 'WORK_SESSION' && row.project || i18nText("ui.entries_without_a_client"));
    }
    const paths = { CLIENT: 'clients', PROJECT: 'projects', TASK: 'tasks', LABEL: 'labels' };
    let mappingRequests = 0;
    function draw() {
      list.replaceChildren();
      const clients = node('div'),other = node('div');
      list.append(node('h3', i18nText("ui.client_mappings")), clients);
      if (plan.mappings.some((value) => value.resourceType !== 'CLIENT')) list.append(node('h3', i18nText("ui.other_mappings")), other);
      for (const definition of plan.mappings) {
        const parent = definitions.get(definition.parentKey),type = definition.resourceType;
        const choices = (catalog[type] || []).filter((value) => !parent || parent.action === 'REUSE' && value[type === 'PROJECT' ? 'clientId' : 'projectId'] === parent.id).
        sort((a, b) => (a.name || a.summary).localeCompare(b.name || b.summary,getLocale()));
        if (definition.action === 'REUSE' && !choices.some((value) => value.id === definition.id) && !resume) {
          definition.action = 'CREATE';delete definition.id;delete definition.etag;
        }
        const row = node('div', undefined, 'import-mapping');
        const select = field(row, type === 'CLIENT' ? i18nText("ui.timekeeper_client_for", { value0: sourceClientName(definition) }) : `${names[type]}: ${definition.name}`, definition.action === 'CREATE' ? '' : definition.id,
        { choices: [['', type === 'CLIENT' ? i18nText("ui.create_a_new_client") : i18nText("ui.create_new")], ...choices.map((value) => [value.id, `${type === 'CLIENT' ? i18nText("ui.use_existing") : i18nText("ui.reuse")} ${value.name || value.summary}`])] });
        select.disabled = !!resume;
        const creation = node('div');creation.hidden = definition.action !== 'CREATE';row.append(creation);
        const name = field(creation, i18nText("ui.new_name", { value0: names[type].toLowerCase() }), definition.name, { maxLength: 1000 });name.disabled = definition.action !== 'CREATE' || !!resume;
        name.addEventListener('input', () => {definition.name = name.value;view.dirty.add(upload.value);});
        if (type === 'CLIENT' || type === 'LABEL') {
          const color = field(creation, i18nText("ui.color_1d7fc8", { value0: names[type] }), definition.color || '#2563eb', { type: 'color' });color.disabled = definition.action !== 'CREATE' || !!resume;
          color.addEventListener('input', () => {definition.color = color.value;view.dirty.add(upload.value);});
        }
        select.addEventListener('change', () => void view.run(async () => {
          const selected = select.value;mappingRequests++;
          try {
            if (selected) {
              const reply = await api.request(`/${paths[type]}/${selected}`);if (!view.valid() || select.value !== selected) return;
              definition.action = 'REUSE';definition.id = selected;definition.etag = reply.etag;
            } else {definition.action = 'CREATE';delete definition.id;delete definition.etag;}
            if (view.valid()) {view.dirty.add(upload.value);draw();}
          } finally {mappingRequests--;}
        }, { feedback }));
        (type === 'CLIENT' ? clients : other).append(row);
      }
    }
    const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');draw();
    const review = button(i18nText("ui.review_server_matches"), () => void view.run(() => locked(async () => {
      if (mappingRequests) throw new Error(i18nText("ui.wait_for_the_selected_records_to_load"));
      reviews = [];let offset = 0;
      for (const count of batches) {
        if (!view.valid()) return;
        if (resume && offset + count <= resume.importedEntries + resume.skippedEntries) {reviews.push(Array.from({ length: count }, () => ({ status: 'COMMITTED' })));offset += count;continue;}
        const rows = plan.rows.slice(offset, offset + count),reply = resume ? await api.request(`/imports/jobs/${resume.id}/preview`, { method: 'POST', body: { rows } }) :
          await api.request('/imports/toggl/preview', { method: 'POST', body: { mode: plan.mode, sourceKey: plan.sourceKey, mappings: plan.mappings, rows } });
        if (!view.valid()) return;reviews.push(reply.data.rows);offset += count;feedback.textContent = i18nText("ui.reviewed_of_entries", { value0: offset, value1: plan.rows.length });
      }
      matchReview();
    }), { submit: review, feedback, write: true }), "btn btn-primary");review.disabled = !view.canWrite;
    work.append(feedback, review, button(i18nText("ui.back_to_source_rows"), () => {if (!view.busy) sourceReview();}), button(i18nText("ui.download_preview_diagnostics"), downloadPreview));
  }
  function matchReview() {
    work.replaceChildren(node('h2', i18nText("ui.confirm_import")));const flat = reviews.flat();
    work.append(node('h3', i18nText("ui.client_mappings")), table([i18nText("ui.toggl_client"), i18nText("ui.timekeeper_client")], plan.mappings.filter((value) => value.resourceType === 'CLIENT').map((value) => [
    sourceClientName(value), value.action === 'REUSE' ? i18nText("ui.use_existing_70c424", { value0: catalog.CLIENT.find((client) => client.id === value.id)?.name || value.id }) : i18nText("ui.create_new_c54c34", { value0: value.name })]
    )));
    const matches = flat.map((value, index) => ({ value, index })).filter(({ value }) => value.status === 'REVIEW_MATCHES' || value.status === 'SOURCE_CHANGED');
    const reused = flat.filter((value) => value.status.startsWith('REUSED')).length;
    const edited = flat.map((value, index) => ({ value, index })).filter(({ value }) => value.status === 'REUSED_EDITED');
    work.append(node('p', i18nText("ui.selected_entries_already_imported_need_review", { value0: plan.rows.length, value1: nanosText(BigInt(plan.selectedNanos)), value2: reused, value3: matches.length })));
    if (edited.length) {
      const details = node('details');details.append(node('summary', i18nText("ui.previously_imported_entries_have_edits_or_missing_daily_parts_existing_records_will_b", { value0: edited.length })));
      details.append(table([i18nText("ui.source_entry"), i18nText("ui.source_start"), i18nText("ui.source_end"), i18nText("ui.current_daily_records")], edited.slice(0, 50).map(({ value, index }) => [
      plan.rows[index].summary || i18nText("ui.entry", { value0: index + 1 }), instantText(plan.rows[index].start, zone), instantText(plan.rows[index].end, zone),
      (value.workSessions || value.activities).map((activity) => `${instantText(activity.start, zone)} → ${activity.end ? instantText(activity.end, zone) : i18nText("ui.running")}`).join('; ')]
      )));
      if (edited.length > 50) details.append(node('p', i18nText("ui.showing_the_first_50_download_preview_diagnostics_for_the_complete_list")));work.append(details);
    }
    const list = node('div');work.append(list);let page = 0;
    function draw() {
      list.replaceChildren();
      for (const { value, index } of matches.slice(page * 50, (page + 1) * 50)) {
        const row = plan.rows[index],card = node('div', undefined, 'card account-card');
        card.append(node('h3', row.summary || i18nText("ui.untitled_entry")), node('p', `${instantText(row.start, zone)} → ${instantText(row.end, zone)}`));
        if (value.status === 'SOURCE_CHANGED') card.append(node('p', i18nText("ui.the_source_entry_id_has_changed_go_back_and_exclude_it_or_resolve_the_source_change"), 'alert alert-warning'));else
        {
          const canReuse = value.matches.every((group) => group.length > 0);
          const choice = field(card, plan.mode === 'WORK_SESSION' ? i18nText("ui.matching_work_sessions") : i18nText("ui.matching_activities"), row.duplicateDecision || '', { choices: [['', i18nText("ui.choose")], ['CREATE_DISTINCT', i18nText("ui.create_a_distinct_entry")], ...(canReuse ? [['REUSE', plan.mode === 'WORK_SESSION' ? i18nText("ui.reuse_existing_work_session") : i18nText("ui.reuse_existing_daily_activities")]] : [])] });
          const parts = node('div');card.append(parts);
          function showParts() {
            parts.replaceChildren();row.duplicateDecision = choice.value;delete row.reuseActivities;delete row.reuseWorkSessions;
            if (choice.value === 'REUSE') {
              const reuseField = plan.mode === 'WORK_SESSION' ? 'reuseWorkSessions' : 'reuseActivities';row[reuseField] = [];
              value.matches.forEach((group, part) => {
                const select = field(parts, plan.mode === 'WORK_SESSION' ? i18nText("ui.work_session_21aa04") : i18nText("ui.daily_activity", { value0: part + 1 }), group.length === 1 ? group[0].id : '', { choices: [['', i18nText("ui.choose_record")], ...group.map((item) => [item.id, item.id])] });
                const update = () => {row[reuseField][part] = group.find((item) => item.id === select.value) || null;};select.addEventListener('change', update);update();
              });
            }
          }
          choice.addEventListener('change', showParts);showParts();
        }
        list.append(card);
      }
      if (matches.length > 50) {
        const previous = button(i18nText("ui.previous_matches"), () => {page--;draw();});previous.disabled = page === 0;
        const next = button(i18nText("ui.next_matches"), () => {page++;draw();});next.disabled = (page + 1) * 50 >= matches.length;list.append(previous, next);
      }
    }
    draw();const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
    const start = button(resume ? i18nText("ui.resume_import") : i18nText("ui.import_selected_entries"), () => void view.run(async () => {
      if (matches.some(({ value, index }) => value.status === 'SOURCE_CHANGED' || !plan.rows[index].duplicateDecision || plan.rows[index].duplicateDecision === 'REUSE' && (plan.rows[index].reuseWorkSessions || plan.rows[index].reuseActivities)?.some((item) => !item))) throw new Error(i18nText("ui.resolve_every_matching_entry_before_importing"));
      const initial = { mode: plan.mode, label: resume?.label || i18nText("ui.toggl_track_import"), sourceKey: plan.sourceKey, sourceZone: sourceZone.value.trim(), sourceDateFormat: dateFormat.value,
        fileFingerprint: fingerprint, selectionFingerprint: plan.selectionFingerprint, mappings: plan.mappings, selectedEntries: plan.selectedEntries, selectedNanos: plan.selectedNanos, sourceDiagnostics: diagnostics() };
      stop.hidden = false;
      const enabled = [...work.querySelectorAll('input,select,button')].filter((value) => !value.disabled && value !== stop);
      const sourceControls = [...upload.value.querySelectorAll('input,select,button')].filter((value) => !value.disabled);
      for (const value of [...enabled, ...sourceControls]) value.disabled = true;
      try {
        createKey ||= crypto.randomUUID();
        const loaded = resume ? await api.request(`/imports/jobs/${resume.id}`) : await api.request('/imports/toggl/jobs', { method: 'POST', key: createKey, body: initial });
        if (!view.valid()) return;resume = loaded.data;pause = false;await importLoop(feedback);
      } finally {if (view.valid()) {for (const value of [...enabled, ...sourceControls]) value.disabled = false;stop.hidden = true;}}
    }, { submit: start, feedback, write: true }), "btn btn-primary");start.disabled = !view.canWrite;
    const stop = button(i18nText("ui.pause_after_current_batch"), () => {pause = true;feedback.textContent = i18nText("ui.pausing_after_the_current_batch");});stop.hidden = true;
    work.append(feedback, start, stop, button(i18nText("ui.back_to_mappings"), () => {if (!view.busy) mappingReview();}), button(i18nText("ui.download_preview_diagnostics"), downloadPreview));
  }
  async function importLoop(feedback) {
    let committed = resume.importedEntries + resume.skippedEntries,offset = 0;
    for (let index = 0; index < batches.length; index++) {
      const count = batches[index];
      if (offset + count <= committed) {offset += count;continue;}
      if (offset !== committed || index !== resume.nextBatch) throw new Error(i18nText("ui.batch_layout_changed_reselect_the_original_file_and_mappings"));
      if (pause || !view.valid()) break;
      const rows = plan.rows.slice(offset, offset + count);
      const fresh = await api.request(`/imports/jobs/${resume.id}/preview`, { method: 'POST', body: { rows } });if (!view.valid()) return;
      // A new job changes the root token. Compare actual row matches, not that transport token.
      const previous = reviews[index].map((value) => ({ ...value }));
      if (signature(fresh.data.rows) !== signature(previous)) throw new Error(i18nText("ui.matching_records_changed_since_review_reload_this_import_and_review_current_matches_b"));
      const body = { rows, reviewToken: fresh.data.reviewToken, finished: index === batches.length - 1 };
      try {await api.request(`/imports/jobs/${resume.id}/batches/${index}`, { method: 'POST', body, timeout: 60000 });}
      catch (error) {
        if (!error.uncertain) throw error;
        const authority = await api.request(`/imports/jobs/${resume.id}`);if (!view.valid()) return;resume = authority.data;
        if (resume.nextBatch !== index + 1) throw error;
      }
      if (!view.valid()) return;resume = (await api.request(`/imports/jobs/${resume.id}`)).data;if (!view.valid()) return;
      committed = resume.importedEntries + resume.skippedEntries;offset += count;
      feedback.textContent = i18nText("ui.of_entries_committed_imported_reused", { value0: committed, value1: resume.selectedEntries, value2: nanosText(BigInt(resume.importedNanos)), value3: nanosText(BigInt(resume.skippedNanos)) });
    }
    if (!view.valid()) return;
    if (resume.state === 'IMPORTED') {
      view.clean(upload.value);feedback.append(i18nText("ui.import_complete"));void view.refreshProfile().catch(() => {});
      work.replaceChildren(node('h2', i18nText("ui.import_complete_a9c747")), feedback, button(i18nText("ui.download_preview_diagnostics"), downloadPreview), button(i18nText("ui.import_another_file"), () => {
        if (view.busy) return;resume = parsed = plan = null;reviews = [];batches = [];file.value = '';work.hidden = true;upload.feedback.textContent = '';file.focus();
      }));
    } else
    feedback.append(i18nText("ui.paused_resume_from_import_history"));
    await loadHistory();
  }
  async function loadHistory(after = null) {
    const reply = await api.request(`/imports${after ? `?after=${encodeURIComponent(after)}` : ''}`);if (!view.valid()) return;
    historyItems = after ? [...historyItems, ...reply.data.items] : reply.data.items;historyAfter = reply.data.nextAfter;
    historyList.replaceChildren();
    const rows = [...historyItems].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map((job) => {
      const actions = node('div', undefined, 'token-actions');
      if (job.state === 'IMPORTING') actions.append(button(i18nText("ui.resume"), () => void view.run(() => locked(async () => {
        resume = (await api.request(`/imports/jobs/${job.id}`)).data;if (!view.valid()) return;
        importMode.value = resume.mode || 'ACTIVITY';sourceZone.value = resume.sourceZone;dateFormat.value = resume.sourceDateFormat || 'ISO';parsed = plan = null;work.hidden = true;file.value = '';createKey = null;
        upload.feedback.textContent = i18nText("ui.choose_the_original_csv_source_user_and_row_exclusions_to_resume");file.focus();
      }), { write: true })));
      if (['IMPORTING', 'IMPORTED', 'BLOCKED', 'UNDOING'].includes(job.state)) {
        const undo = button(job.state === 'UNDOING' ? i18nText("ui.resume_undo") : i18nText("ui.undo_import"), () => void view.run(() => undoReview(job)));undo.disabled = !view.canWrite;actions.append(undo);
      }
      actions.append(button(i18nText("ui.download_audit"), () => void view.run(() => download(job))));
      return [`${job.label} · ${job.mode === 'WORK_SESSION' ? i18nText("ui.work_sessions") : i18nText("ui.activities")}`, instantText(job.createdAt, zone), i18nText('import.state.'+job.state), i18nText("ui.imported_reused", { value0: job.importedEntries, value1: job.skippedEntries }), nanosText(BigInt(job.importedNanos)), actions];
    });
    historyList.append(rows.length ? table([i18nText("ui.import"), i18nText("ui.created"), i18nText("ui.state"), i18nText("ui.entries"), i18nText("ui.imported_time"), i18nText("ui.actions")], rows) : node('p', i18nText("ui.no_imports_yet"), 'hint'));
    if (historyAfter) historyList.append(button(i18nText("ui.load_more_imports"), () => void view.run(() => loadHistory(historyAfter))));
  }
  async function undoReview(job) {
    const loaded = await api.request(`/imports/jobs/${job.id}`);if (!view.valid()) return;
    const current = loaded.data;operations.replaceChildren(node('h3', i18nText("ui.undo", { value0: current.label })), node('p', i18nText("ui.created_entries_catalog_records_reviewed_reused_records_stay_edited_or_referenced_rec", { value0: current.importedEntries, value1: current.mode === 'WORK_SESSION' ? i18nText("ui.work_sessions_363477", { value0: current.workSessionCount || 0 }) : i18nText("ui.daily_activities", { value0: current.activityCount }), value2: current.catalogCount })));
    const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
    const confirm = button(current.state === 'UNDOING' ? i18nText("ui.continue_undo") : i18nText("ui.confirm_undo"), () => void view.run(async () => {
      if (current.state !== 'UNDOING') await api.request(`/imports/jobs/${current.id}/undo`, { method: 'POST', key: crypto.randomUUID(), etag: loaded.etag });
      if (view.valid()) {undoPause = false;operations.replaceChildren(feedback, pauseUndo(feedback));await undoLoop(current.id, feedback);}
    }, { submit: confirm, feedback, write: true }), "btn btn-danger");
    operations.append(feedback, confirm, button(i18nText("ui.cancel"), () => operations.replaceChildren()));
  }
  async function undoLoop(id, feedback) {
    while (view.valid()) {
      if (undoPause) {feedback.textContent = i18nText("ui.undo_paused_continue_from_import_history");await loadHistory();return;}
      const current = (await api.request(`/imports/jobs/${id}`)).data;if (!view.valid()) return;
      if (current.state.startsWith('UNDONE')) {feedback.textContent = i18nText("ui.removed_retained", { value0: current.state === 'UNDONE' ? i18nText("ui.undo_complete") : i18nText("ui.undo_completed_with_retained_records"), value1: current.undoRemoved, value2: current.undoRetained });await loadHistory();void view.refreshProfile().catch(() => {});return;}
      const preview = (await api.request(`/imports/jobs/${id}/undo/preview`)).data;if (!view.valid()) return;
      if (undoPause) continue;
      const conflicts = preview.targets.filter((target) => !['DELETE', 'MISSING', 'REUSED'].includes(target.status));
      if (conflicts.length) {undoConflicts(id, current, preview, conflicts);return;}
      await api.request(`/imports/jobs/${id}/undo/batches/${current.undoNextBatch}`, { method: 'POST', body: { reviewToken: preview.reviewToken, choices: {} }, timeout: 60000 });
      if (view.valid()) feedback.textContent = i18nText("ui.undo_in_progress_records_removed", { value0: current.undoRemoved });
    }
  }
  function pauseUndo(feedback) {return button(i18nText("ui.pause_undo"), () => {undoPause = true;feedback.textContent = i18nText("ui.pausing_after_the_current_batch");});}
  function undoConflicts(id, job, preview, conflicts) {
    operations.replaceChildren(node('h3', i18nText("ui.review_records_retained_or_changed_since_import")));const choices = {};
    for (const target of conflicts) {
      const record = target.current;
      const title = `${names[target.recordType] || i18nText("ui.activity")}: ${record?.name || record?.title || record?.summary || target.recordId}`;
      const reasons = { REVIEW: i18nText("ui.edited_since_import"), REFERENCED: i18nText("ui.used_by_another_import_undo_that_import_first_or_keep_this_record"), RUNNING: i18nText("ui.this_record_is_running_stop_it_first_or_keep_it"), DEPENDENCY: i18nText("ui.other_records_still_use_this_catalog_record_keep_it_or_remove_those_dependencies_sepa") };
      const card = node('div', undefined, 'card account-card');card.append(node('h4', title), node('p', reasons[target.status] || target.status));
      if (record?.start) card.append(node('p', `${instantText(record.start, zone)} → ${record.end ? instantText(record.end, zone) : i18nText("ui.running")}`));
      const choice = field(card, i18nText("ui.undo_decision"), '', { choices: [['', i18nText("ui.choose")], ['KEEP', i18nText("ui.keep_this_record")], ...(target.status === 'REVIEW' ? [['DELETE', i18nText("ui.delete_the_reviewed_edited_record")]] : [])] });
      choice.addEventListener('change', () => {choices[target.key] = choice.value;});operations.append(card);
    }
    const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
    const apply = button(i18nText("ui.apply_decisions_and_continue_undo"), () => void view.run(async () => {
      if (conflicts.some((target) => !choices[target.key])) throw new Error(i18nText("ui.choose_a_resolution_for_every_record"));
      await api.request(`/imports/jobs/${id}/undo/batches/${job.undoNextBatch}`, { method: 'POST', body: { reviewToken: preview.reviewToken, choices }, timeout: 60000 });
      if (view.valid()) {undoPause = false;operations.replaceChildren(feedback, pauseUndo(feedback));await undoLoop(id, feedback);}
    }, { submit: apply, feedback, write: true }), "btn btn-danger");
    operations.append(feedback, apply, button(i18nText("ui.pause_undo"), () => {operations.replaceChildren(node('p', i18nText("ui.undo_paused_continue_from_import_history")));}));
  }
  async function download(job) {
    const details = (await api.request(`/imports/jobs/${job.id}`)).data,chunks = [`{"job":${JSON.stringify(details)},"items":[`];let offset = 0,first = true;
    do {
      const reply = await api.request(`/imports/jobs/${job.id}/items?offset=${offset}`);if (!view.valid()) return;
      if (reply.data.items.length) {chunks.push((first ? '' : ',') + JSON.stringify(reply.data.items).slice(1, -1));first = false;}offset = reply.data.nextOffset;
    } while (offset !== null);
    if (!view.valid()) return;
    chunks.push(']}');saveJson(chunks, `timekeeper-import-${job.id}.json`);
  }
  function diagnostics() {
    const selected = parsed.rows.filter((row) => row.personKey === selectedPerson || !row.personKey);
    return { sourceRows: parsed.rows.length, rejectedRows: selected.filter((row) => row.errors.length).length,
      excludedRows: selected.filter((row) => !row.errors.length && row.excluded).length, unselectedRows: parsed.rows.length - selected.length, unsupportedFields: parsed.unsupported };
  }
  function downloadPreview() {
    if (!view.valid() || !parsed) return;
    const rows = parsed.rows.map((row) => ({ line: row.line, selected: row.personKey === selectedPerson || !row.personKey, excluded: !!row.excluded,
      errors: row.errors, warnings: row.warnings, ambiguousDuplicate: !!row.ambiguousDuplicate }));
    saveJson([JSON.stringify({ mode: importMode.value, sourceZone: sourceZone.value.trim(), sourceDateFormat: dateFormat.value, diagnostics: diagnostics(), rows,
      serverReview: reviews.flat().map((value, index) => ({ entry: index + 1, ...value })) }, null, 2)], 'timekeeper-import-diagnostics.json');
  }
  function saveJson(chunks, filename) {
    const url = URL.createObjectURL(new Blob(chunks, { type: 'application/json' }));downloadUrls.add(url);
    const link = node('a');link.href = url;link.download = filename;link.hidden = true;document.body.append(link);link.click();link.remove();
    setTimeout(() => {URL.revokeObjectURL(url);downloadUrls.delete(url);}, 1000);
  }
}

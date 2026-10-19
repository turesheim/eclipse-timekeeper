import { getLocale } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { node, button, field, section, form, table } from './account-ui.js';
import { instantText } from './model.js';

const MAX_BYTES = 256 * 1024 * 1024;
const labels = { clients: i18nText("ui.clients"), projects: i18nText("ui.projects"), tasks: i18nText("ui.tasks"), labels: i18nText("ui.labels"), activities: i18nText("ui.activities"), workSessions: i18nText("ui.work_sessions"), importAuditRecords: i18nText("ui.import_audit_records") };
const relationshipLabels = { projectClients: i18nText("ui.project_clients"), taskProjects: i18nText("ui.task_projects"), parentTasks: i18nText("ui.parent_tasks"), activityTasks: i18nText("ui.activity_tasks"), activityProjects: i18nText("ui.activity_projects"), activityLabels: i18nText("ui.activity_labels"), workSessionClients: i18nText("ui.work_session_clients"), workSessionProjects: i18nText("ui.work_session_projects"), workSessionLabels: i18nText("ui.work_session_labels") };
const base = '/data/account';

export async function renderAccountData(view, zone) {
  let currentZone = zone,pendingRequest = 0,historyRequest = 0;
  const api = view.session,urls = new Set();let worker = null,cancelWorker = null,selected = null,fingerprint = null,createKey = null,uploadId = null,review = null,uncertain = false,historyAfter = null,historyItems = [];
  const downloadCard = section(view, i18nText("ui.download_my_data")),downloadFeedback = node('p', '', 'form-feedback');downloadFeedback.setAttribute('role', 'status');
  downloadCard.append(node('p', i18nText("ui.a_private_zip_with_json_for_restoring_your_work_and_csv_files_for_other_tools_include")));
  const download = button(i18nText("ui.download_my_data"), () => void view.run(async () => {
    download.disabled = true;
    try {
      const reply = await api.request(base + '/export', { binary: true, timeout: 300000 });if (!view.valid()) return;
      const url = URL.createObjectURL(reply.data);urls.add(url);const link = node('a', i18nText("ui.save_archive"), 'btn btn-outline-secondary');link.href = url;link.download = 'timekeeper-account.zip';
      downloadFeedback.replaceChildren(node('span', i18nText("ui.your_private_archive_is_ready")), link);link.click();
    } finally {if (view.valid()) download.disabled = false;}
  }, { feedback: downloadFeedback }), "btn btn-primary");downloadCard.append(download, downloadFeedback);

  const upload = form(view, i18nText("ui.restore_from_a_timekeeper_archive"), i18nText("ui.review_archive"), reviewFile);
  upload.value.append(node('p', i18nText("ui.adds_the_listed_records_to_this_account_existing_records_and_sign_in_details_are_kept")));
  const file = field(upload.value, i18nText("ui.timekeeper_archive"), '', { type: 'file' });file.accept = '.zip,.json,application/zip,application/json';
  upload.value.append(node('p', i18nText("ui.timekeeper_zip_or_archive_json_up_to_256_mib"), 'hint'));
  const restoreZone = field(upload.value, i18nText("ui.restore_the_archived_time_zone"), false, { type: 'checkbox' });
  const cancelReading = button(i18nText("ui.cancel_reading"), () => cancelWorker?.());cancelReading.hidden = true;upload.value.append(cancelReading);upload.finish();
  const work = section(view, i18nText("ui.review_archive"));work.hidden = true;
  const pendingCard = section(view, i18nText("ui.pending_uploads")),pendingList = node('div');pendingCard.append(pendingList);
  const historyCard = section(view, i18nText("ui.archive_restore_history")),historyList = node('div'),historyActions = node('div');historyCard.append(historyList, historyActions);
  upload.value.addEventListener('change', () => {review = null;uncertain = false;work.hidden = true;if (file.files[0] !== selected) {selected = null;fingerprint = createKey = uploadId = null;}});
  view.cleanup.add(() => {
    cancelWorker?.();worker?.terminate();worker = null;selected = fingerprint = createKey = uploadId = review = null;file.value = '';historyItems.length = 0;
    for (const url of urls) URL.revokeObjectURL(url);urls.clear();
  });
  await Promise.all([loadPending(), loadHistory()]);

  function hash(chosen) {
    return new Promise((resolve, reject) => {
      const current = new Worker(new URL('./account-data-worker.js', import.meta.url), { type: 'module' });worker = current;cancelReading.hidden = false;
      const finish = (result, error) => {
        current.terminate();if (worker === current) {worker = null;cancelWorker = null;cancelReading.hidden = true;}
        if (error) reject(new Error(error));else resolve(result);
      };
      cancelWorker = () => finish(null, i18nText("ui.reading_cancelled"));current.onmessage = (event) => finish(event.data.sha256, event.data.error);
      current.onerror = () => finish(null, i18nText("ui.the_archive_could_not_be_read_choose_the_unchanged_timekeeper_file"));current.postMessage({ locale: getLocale(), file: chosen });
    });
  }
  async function locked(work) {
    const controls = [...upload.value.querySelectorAll('input,button')].filter((control) => !control.disabled && control !== cancelReading);for (const control of controls) control.disabled = true;
    try {return await work();} finally {if (view.valid() && !uncertain) for (const control of controls) control.disabled = false;}
  }
  async function reviewFile() {
    await locked(async () => {
      const chosen = file.files[0];if (!chosen || chosen.size < 1 || chosen.size > MAX_BYTES) throw new Error(i18nText("ui.choose_a_timekeeper_zip_or_archive_json_up_to_256_mib"));
      const mediaType = chosen.name.toLowerCase().endsWith('.zip') ? 'application/zip' : chosen.name.toLowerCase().endsWith('.json') ? 'application/json' : null;
      if (!mediaType) throw new Error(i18nText("ui.choose_a_timekeeper_zip_or_archive_json"));
      work.hidden = true;review = null;uncertain = false;
      try {
        if (selected !== chosen || !fingerprint) {upload.feedback.textContent = i18nText("ui.reading_archive");fingerprint = await hash(chosen);selected = chosen;createKey = crypto.randomUUID();}
        if (!view.valid()) return;
        const pending = await api.request(base + '/uploads');if (!view.valid()) return;
        let current = pending.data.items.find((value) => value.sha256 === fingerprint && value.bytes === chosen.size && value.mediaType === mediaType);
        if (!current) current = (await api.request(base + '/uploads', { method: 'POST', key: createKey, body: { fileName: chosen.name, mediaType, bytes: chosen.size, sha256: fingerprint } })).data;
        if (!view.valid()) return;uploadId = current.id;
        current = (await api.request(base + '/uploads/' + uploadId)).data;
        for (let index = current.nextChunk; index < current.chunkCount; index++) {
          const bytes = new Uint8Array(await chosen.slice(index * current.chunkBytes, Math.min(chosen.size, (index + 1) * current.chunkBytes)).arrayBuffer());
          // Avoid spreading an entire MiB into a browser's function argument stack.
          let text = '';for (let offset = 0; offset < bytes.length; offset += 8192) text += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
          await api.request(`${base}/uploads/${uploadId}/chunks/${index}`, { method: 'PUT', body: { data: btoa(text) }, timeout: 60000 });if (!view.valid()) return;
          upload.feedback.textContent = i18nText("ui.uploading_archive", { value0: Math.min(100, Math.round((index + 1) / current.chunkCount * 100)) });
        }
        upload.feedback.textContent = i18nText("ui.validating_archive");
        review = await api.request(`${base}/uploads/${uploadId}/preview`, { method: 'POST', body: { restoreTimeZone: restoreZone.checked }, timeout: 300000 });if (!view.valid()) return;
        upload.feedback.textContent = i18nText("ui.archive_reviewed");await loadPending();if (!view.valid()) return;
        if (review.data.alreadyImported) await restored(review.data, true);else showReview();
      } catch (error) {
        if (!view.valid()) return;
        if (error.uncertain) {upload.feedback.textContent = i18nText("ui.upload_paused_select_review_archive_to_resume_safely");return;}
        if ([400, 422].includes(error.status)) throw new Error(i18nText("ui.this_is_not_a_valid_supported_timekeeper_archive_check_the_complete_file_and_its_work"));
        if (error.status === 413) throw new Error(i18nText("ui.the_archive_exceeds_a_supported_size_limit_or_pending_uploads_need_to_be_discarded"));
        throw error;
      }
    });
  }
  function countTable(counts) {
    const value = table([i18nText("ui.records"), i18nText("ui.count")], Object.entries(labels).map(([key, label]) => [label, String(counts[key] ?? 0)]));
    const grid = value.querySelector('table');grid.classList.remove('record-table');grid.classList.add('table-sm');return value;
  }
  function showReview() {
    const value = review.data,captured = review,reviewedUploadId = uploadId;work.hidden = false;work.replaceChildren(node('h2', i18nText("ui.review_archive")));
    work.append(node('p', `${value.sourceAccount.displayName || value.sourceAccount.username || i18nText("ui.account_archive")} · ${instantText(value.generatedAt, currentZone)}`), countTable(value.counts));
    if (value.relationships) {
      work.append(node('h3', i18nText("ui.linked_records"), 'h6'), node('p', i18nText("ui.these_links_are_preserved_with_new_record_ids")));
      const rows = Object.entries(relationshipLabels).filter(([key]) => value.relationships[key] > 0).map(([key, label]) => [label, String(value.relationships[key])]);
      if (rows.length) {
        const related = table([i18nText("ui.links"), i18nText("ui.count")], rows),grid = related.querySelector('table');grid.setAttribute('aria-label', i18nText("ui.record_links"));grid.classList.remove('record-table');grid.classList.add('table-sm');work.append(related);
      } else work.append(node('p', i18nText("ui.this_archive_has_no_record_links")));
    }
    work.append(node('p', value.restoreTimeZone ? i18nText("ui.time_zone", { value0: value.currentTimeZone, value1: value.sourceTimeZone }) : i18nText("ui.keep_time_zone", { value0: value.currentTimeZone })));
    if (value.runningActivities || value.runningWorkSessions) work.append(node('p', i18nText("ui.running_activities_running_work_sessions", { value0: value.runningActivities, value1: value.runningWorkSessions })));
    for (const conflict of value.conflicts) work.append(node('p', conflict, 'alert alert-warning'));
    const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
    const apply = button(i18nText("ui.import_reviewed_records"), () => void view.run(() => locked(async () => {
      try {
        const reply = await api.request(`${base}/uploads/${reviewedUploadId}/import`, { method: 'POST', etag: captured.etag, body: { restoreTimeZone: value.restoreTimeZone }, timeout: 300000 });
        if (view.valid()) await restored(reply.data, false);
      } catch (error) {
        if (error.uncertain) {uncertain = true;feedback.textContent = i18nText("ui.the_import_result_is_unknown_check_its_result_before_continuing");check.hidden = false;return;}
        if (error.status === 412) {value.canImport = false;feedback.textContent = i18nText("ui.your_work_changed_after_review_select_review_archive_to_review_the_current_records_ag");return;}
        throw error;
      }
    }), { submit: apply, feedback, write: true, canSubmit: () => value.canImport && !uncertain }), "btn btn-primary");apply.disabled = !view.canWrite || !value.canImport;
    const check = button(i18nText("ui.check_import_result"), () => void view.run(async () => {
      const current = await api.request(base + '/uploads/' + reviewedUploadId);if (!view.valid()) return;
      if (current.data.state === 'IMPORTED') {await restored(current.data.outcome, false);return;}
      feedback.textContent = i18nText("ui.no_completed_import_is_recorded_you_can_retry_the_same_import");uncertain = false;unlockUpload();apply.disabled = !view.canWrite || !value.canImport;check.hidden = true;
    }, { feedback }));check.hidden = true;
    work.append(feedback, apply, node('span', ' '), check);
  }
  function unlockUpload() {file.disabled = restoreZone.disabled = false;upload.submit.disabled = !view.canWrite;}
  async function restored(value, already) {
    if (!view.valid()) return;view.clean(upload.value);uncertain = false;unlockUpload();work.hidden = false;
    work.replaceChildren(node('h2', already ? i18nText("ui.archive_already_restored") : i18nText("ui.archive_restored")), node('p', already ? i18nText("ui.this_archive_was_already_restored_its_records_were_not_added_again") : i18nText("ui.your_work_records_have_been_restored")), countTable(value.counts));
    upload.feedback.textContent = '';
    try {const profile = await api.request('/me');if (!view.valid()) return;currentZone = profile.data.zoneId;void view.refreshProfile(profile).catch(() => {});}
    catch (error) {if (error.status === 401) throw error;upload.feedback.textContent = i18nText("ui.restore_completed_refresh_to_reload_the_current_records");}
    historyAfter = null;historyItems = [];
    const results = await Promise.allSettled([loadPending(), loadHistory()]);
    for (const result of results) if (result.status === 'rejected') {if (result.reason.status === 401) throw result.reason;upload.feedback.textContent = i18nText("ui.restore_completed_refresh_to_reload_the_current_records");}
  }
  async function loadPending() {
    const request = ++pendingRequest,reply = await api.request(base + '/uploads');if (!view.valid() || request !== pendingRequest) return;
    pendingCard.hidden = !reply.data.items.length;
    pendingList.replaceChildren(table([i18nText("ui.archive"), i18nText("ui.expires"), i18nText("ui.actions")], reply.data.items.map((item) => {
      const discard = button(i18nText("ui.discard_upload"), () => void view.run(async () => {
        const current = await api.request(base + '/uploads/' + item.id);await api.request(base + '/uploads/' + item.id, { method: 'DELETE', etag: current.etag });if (item.id === uploadId || item.sha256 === fingerprint) {createKey = crypto.randomUUID();uploadId = null;review = null;work.hidden = true;}if (view.valid()) await loadPending();
      }, { submit: discard, write: true }));discard.disabled = !view.canWrite;
      return [item.fileName, instantText(item.expiresAt, currentZone), discard];
    })));
  }
  async function loadHistory() {
    const request = ++historyRequest,reply = await api.request(base + '/imports' + (historyAfter ? '?after=' + encodeURIComponent(historyAfter) : ''));if (!view.valid() || request !== historyRequest) return;
    historyItems.push(...reply.data.items);historyAfter = reply.data.nextAfter;
    historyList.replaceChildren(historyItems.length ? table([i18nText("ui.restored"), i18nText("ui.work_sessions"), i18nText("ui.activities")], historyItems.map((item) => [instantText(item.importedAt, currentZone), String(item.counts.workSessions), String(item.counts.activities)])) : node('p', i18nText("ui.no_archives_restored")));
    historyActions.replaceChildren();if (historyAfter) historyActions.append(button(i18nText("ui.more_restores"), () => void view.run(loadHistory)));
  }
}

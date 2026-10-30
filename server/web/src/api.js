import {dateTimeFormat} from './formatters.js';
import {getLocale} from './i18n.js';
import { t as i18nText } from "./i18n.js";const nameOrder = new Intl.Collator(getLocale(), { numeric: true, sensitivity: 'base' });
const sortedNames = (values) => [...values].sort((a, b) => nameOrder.compare(a.name, b.name) || a.id.localeCompare(b.id));
const messages = {
  VAULT_CONFIG_DISABLED:'notes.error_config_disabled',
  VAULT_CONFIG_PRESENT:'notes.error_config_present',
  VAULT_SPECIAL_FOLDER:'notes.error_special_folder',
  VAULT_ASSOCIATION_MOVE:'notes.error_association',
  VAULT_QUOTA:'notes.error_quota',
  VAULT_IMPORT_NOT_EMPTY:'notes.error_import_not_empty',
  VAULT_FILE_TOO_LARGE:'notes.error_file_size',
  INSTANCE_RESTORE_MATERIAL_REQUIRED: 'backups.error_material_required',
  INSTANCE_RESTORE_PENDING: 'backups.error_restore_pending',
  INSTANCE_RESTART_REQUIRED: 'backups.error_restart_required',
  INSTANCE_MAINTENANCE_ACTIVE: 'backups.error_maintenance',
  INSTANCE_FENCE_UNAVAILABLE: 'backups.error_fence',
  INSTANCE_UPLOAD_FILE_MISMATCH: 'backups.error_file_mismatch',
  INSTANCE_UPLOAD_PROGRESS_INVALID: 'backups.error_progress',
  INSTANCE_OPERATION_IN_PROGRESS: 'backups.error_in_progress',
  INSTANCE_OPERATION_BUSY: 'backups.error_in_progress',
  INSTANCE_ARCHIVE_TOO_LARGE: 'backups.error_size',
  INSTANCE_STORAGE_REQUIRED: 'backups.storage_required',
  INSTANCE_UPLOAD_CONFLICT: 'backups.error_conflict',
  INSTANCE_OPERATION_STATE: 'backups.error_state',
  INSTANCE_OPERATION_LEASE_LOST: 'backups.error_state',
  INSTANCE_DOWNLOAD_REQUIRED: 'backups.error_download',
  JOURNAL_HTTPS_REQUIRED: "journal.webdav.error.https_required",
  RATE_LIMITED: "api.error.rate_limited",
  CALENDAR_FUTURE_ACTIVITY: "calendar.error.CALENDAR_FUTURE_ACTIVITY",
  CALENDAR_INVALID_FEED: "calendar.error.CALENDAR_INVALID_FEED",
  CALENDAR_TOO_COMPLEX: "calendar.error.CALENDAR_TOO_COMPLEX",
  CALENDAR_URL_BLOCKED: "calendar.error.CALENDAR_URL_BLOCKED",
  CALENDAR_INVALID_URL: "calendar.error.CALENDAR_INVALID_URL",
  CALENDAR_DNS_FAILED: "calendar.error.CALENDAR_DNS_FAILED",
  CALENDAR_TIMEOUT: "calendar.error.CALENDAR_TIMEOUT",
  CALENDAR_REDIRECT_FAILED: "calendar.error.CALENDAR_REDIRECT_FAILED",
  CALENDAR_ACCESS_DENIED: "calendar.error.CALENDAR_ACCESS_DENIED",
  CALENDAR_NOT_FOUND: "calendar.error.CALENDAR_NOT_FOUND",
  CALENDAR_RATE_LIMITED: "calendar.error.CALENDAR_RATE_LIMITED",
  CALENDAR_REMOTE_ERROR: "calendar.error.CALENDAR_REMOTE_ERROR",
  CALENDAR_TOO_LARGE: "calendar.error.CALENDAR_TOO_LARGE",
  CALENDAR_TLS_FAILED: "calendar.error.CALENDAR_TLS_FAILED",
  CALENDAR_CONNECTION_FAILED: "calendar.error.CALENDAR_CONNECTION_FAILED",
  CALENDAR_SUBSCRIPTION_LIMIT: "calendar.error.CALENDAR_SUBSCRIPTION_LIMIT",
  CALENDAR_REFRESH_LIMIT: "calendar.error.CALENDAR_REFRESH_LIMIT",
  CALENDAR_BUSY: "calendar.error.CALENDAR_BUSY",
  CALENDAR_ALREADY_CONVERTED: "calendar.error.CALENDAR_ALREADY_CONVERTED",
  CALENDAR_EVENT_CHANGED: "calendar.error.CALENDAR_EVENT_CHANGED",
  CALENDAR_CLIENT_MISMATCH: "calendar.error.CALENDAR_CLIENT_MISMATCH",
  CALENDAR_DUPLICATE_SUBSCRIPTION: "calendar.error.CALENDAR_DUPLICATE_SUBSCRIPTION",
  INVALID_TOKEN: "ui.the_token_contains_invalid_characters_or_is_too_long_copy_the_complete_api_token_and_",
  UNDO_CONFLICT: "ui.this_work_session_or_its_references_changed_undo_cannot_replace_the_current_records",
  UNDO_EXPIRED: "ui.the_time_to_undo_this_change_has_expired",
  UNAUTHENTICATED: "ui.your_session_is_no_longer_authorized_sign_in_again_with_your_account",
  OWNER_CHANGED: "ui.the_account_for_this_session_has_changed_sign_out_before_using_the_intended_account",
  FORBIDDEN: "ui.your_account_does_not_have_permission_to_make_this_change",
  SERVICE_PROOF_REQUIRED: "ui.confirm_your_current_administrator_password_to_save_service_settings",
  PASSKEY_PROOF_REQUIRED: "ui.confirm_your_current_password_or_use_an_existing_passkey_to_make_this_change",
  PASSKEY_REJECTED: "ui.the_passkey_could_not_be_verified_try_again_or_use_your_username_and_password",
  PASSKEY_ACCOUNT_SIGN_IN_REQUIRED: "ui.sign_in_with_your_account_password_or_a_passkey_to_manage_passkeys_an_api_token_canno",
  STALE_VERSION: "ui.this_record_changed_your_draft_is_kept_cancel_and_reopen_the_editor_to_review_the_cur",
  PRECONDITION_FAILED: "ui.this_record_changed_your_draft_is_kept_cancel_and_reopen_the_editor_to_review_the_cur",
  ACTIVE_STATE_CHANGED: "ui.tracking_changed_in_another_client_review_the_current_activity_before_trying_again",
  VALIDATION: "ui.check_the_values_and_relationships_then_try_again",
  VALIDATION_FAILED: "ui.check_the_values_and_relationships_then_try_again",
  BAD_REQUEST: "ui.check_the_entered_values_then_try_again",
  CONFLICT: "ui.this_change_conflicts_with_another_record_check_dependencies_and_current_values",
  NOT_FOUND: "ui.this_record_no_longer_exists_your_draft_is_kept",
  UNAVAILABLE: "ui.the_server_could_not_be_reached_reconnecting",
  BUSY: "ui.records_are_changing_too_quickly_to_load_a_consistent_view_try_refresh",
  IMPORT_SOURCE_CHANGED: "ui.an_entry_with_this_source_id_changed_exclude_it_or_resolve_the_source_change_before_c",
  IMPORT_DUPLICATE_REVIEW_REQUIRED: "ui.review_matching_entries_and_choose_whether_to_reuse_them_or_create_distinct_activitie",
  IMPORT_UNDO_REVIEW_REQUIRED: "ui.review_the_edited_or_referenced_records_before_continuing_undo",
  IMPORT_RUNNING_MATCH: "ui.a_previously_imported_activity_is_now_running_stop_it_or_exclude_that_source_entry_be",
  IMPORT_TOO_MANY_MATCHES: "ui.too_many_existing_activities_match_this_start_time_resolve_these_matches_before_impor",
  IMPORT_INTERVAL_TOO_LARGE: "ui.an_entry_spans_too_many_days_split_or_exclude_that_source_entry",
  IMPORT_BATCH_TOO_LARGE: "ui.this_batch_produces_more_than_1_000_daily_activities_use_a_smaller_batch"
};
export class ApiError extends Error {
  constructor(code, status = 0) {super(messages[code] ? i18nText(messages[code]) : i18nText("ui.the_request_was_rejected_review_the_current_records_and_try_again"));this.code = code;this.status = status;}
  get uncertain() {return this.status === 0 || this.status >= 500 && this.status !== 507;}
}
export function ownerHeader(owner) {
  return btoa(String.fromCharCode(...new TextEncoder().encode(owner))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
function decodeOwner(value) {
  try {
    if (!value || !/^[A-Za-z0-9_-]{1,1100}$/.test(value)) throw new Error();
    const bytes = Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), (c) => c.charCodeAt(0));
    const owner = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (!owner.trim() || owner.trim() !== owner || [...owner].length > 200 || ownerHeader(owner) !== value) throw new Error();
    return owner;
  } catch {throw new ApiError('UNAVAILABLE');}
}
export class Api {
  #token;#csrf;#owner = null;#requests = new Set();#closed = false;
  constructor(token = null, fetcher = (...args) => globalThis.fetch(...args), context = {}) {this.#token = token?.trim() ?? null;this.#csrf = context.csrf;this.#owner = context.ownerId ?? null;this.fetcher = fetcher;}
  get owner() {return this.#owner;}
  close() {this.#closed = true;this.#token = '';this.#csrf = null;for (const request of this.#requests) request.abort();}
  async request(path, { method = 'GET', body, rawBody, etag, noneMatch, key, timeout = 15000, binary = false, emptyResponse = false, undo = false, signal } = {}) {
    if (this.#closed) throw new ApiError('UNAUTHENTICATED', 401);
    if (rawBody !== undefined && (body !== undefined || !(rawBody instanceof Uint8Array || rawBody instanceof Blob))) throw new TypeError('Use one JSON or binary request body');
    if (this.#token && (this.#token.length > 4096 || !/^[A-Za-z0-9._~+/-]+=*$/.test(this.#token))) throw new ApiError('INVALID_TOKEN', 401);
    const controller = new AbortController();this.#requests.add(controller);
    const abort=()=>controller.abort();
    if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});
    const timer = setTimeout(abort, timeout);
    try {
      const binaryType=binary===true?'application/zip':binary==='application/octet-stream'?binary:null;
      const headers = { Accept: binaryType || 'application/json' };
      if (this.#token) headers.Authorization = `Bearer ${this.#token}`;else
      if (this.#csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers['X-CSRF-Token'] = this.#csrf;
      if (this.#owner !== null) headers['X-Timekeeper-Owner'] = ownerHeader(this.#owner);
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (rawBody !== undefined) headers['Content-Type'] = 'application/octet-stream';
      if (etag) headers["If-Match"] = etag;
      if (noneMatch) headers["If-None-Match"] = noneMatch;
      if (key) headers["Idempotency-Key"] = key;
      if (undo) headers["X-Timekeeper-Undo"] = 'true';
      const response = await this.fetcher(`/api/v1${path}`, { method, headers, body: rawBody !== undefined ? rawBody : body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, redirect: 'error', referrerPolicy: 'origin', credentials: this.#token && path !== '/auth/token-login' ? 'omit' : 'same-origin', cache: 'no-store' });
      if (binary && response.ok && (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase()!==binaryType) throw new ApiError('UNAVAILABLE');
      const data = response.status === 204 || emptyResponse && response.ok ? null : binary && response.ok ? await response.blob() : await response.json();
      if (!response.ok) throw new ApiError(typeof data?.code === 'string' ? data.code : 'UNAVAILABLE', response.status);
      if (this.#closed) throw new ApiError('UNAUTHENTICATED', 401);
      const owner = decodeOwner(response.headers.get('X-Timekeeper-Owner'));
      if (this.#owner !== null && owner !== this.#owner) throw new ApiError('OWNER_CHANGED', 401);
      this.#owner = owner;
      if (data?.ownerId !== undefined && data.ownerId !== owner) throw new ApiError('OWNER_CHANGED', 401);
      if (data?.items?.some((row) => row.ownerId !== undefined && row.ownerId !== owner)) throw new ApiError('OWNER_CHANGED', 401);
      const undoId = response.headers.get("X-Timekeeper-Undo-Id"),seconds = Number(response.headers.get("X-Timekeeper-Undo-Seconds"));
      const offer = undoId && /^[a-f0-9-]{36}$/.test(undoId) && seconds > 0 && seconds <= 600 ? { id: undoId, seconds } : null;
      // Only accept the server's dated account-archive filename. Never pass
      // arbitrary Content-Disposition paths or header text to a browser save.
      const archiveName = binaryType === 'application/zip' ? (response.headers.get('Content-Disposition') || '').trim().match(/^attachment;\s*filename="(timekeeper-account-\d{4}-\d{2}-\d{2}\.zip)"$/i)?.[1] : null;
      return { data, etag: response.headers.get('ETag'), ...(archiveName ? { fileName: archiveName } : {}), ...(offer ? { undo: offer } : {}) };
    } catch (error) {
      if (error instanceof ApiError) throw error;
      // Never expose response bodies, URLs, tokens or fetch exception details.
      throw new ApiError('UNAVAILABLE');
    } finally {clearTimeout(timer);signal?.removeEventListener('abort',abort);this.#requests.delete(controller);}
  }
  async page(path, parameters = {}, options = {}) {
    let cursor = null,revision = null;const items = [],seen = new Set();
    do {
      const query = new URLSearchParams({ ...parameters, limit: 500, ...(cursor ? { cursor } : {}) });
      const { data } = await this.request(`${path}?${query}`,options);
      if (!Array.isArray(data.items) || !Number.isSafeInteger(data.revision) || revision !== null && revision !== data.revision) throw new ApiError('BUSY');
      revision = data.revision;items.push(...data.items);
      cursor = data.nextCursor;
      if (cursor !== null && (typeof cursor !== 'string' || !cursor || seen.has(cursor))) throw new ApiError('UNAVAILABLE');
      seen.add(cursor);
    } while (cursor !== null);
    return { items, revision };
  }
  async initializeTimeZone(zone = Intl.DateTimeFormat().resolvedOptions().timeZone) {
    const profile = await this.request('/me');
    // Version zero is the untouched server default. An explicitly selected UTC
    // zone has a newer version and must be kept, just like any other choice.
    if (profile.data.version !== 0 || profile.data.zoneId !== 'UTC' || !zone || zone === 'UTC' || !profile.etag) return profile;
    try {dateTimeFormat(getLocale(), { timeZone: zone });} catch {return profile;}
    try {return await this.request('/me', { method: 'PUT', body: { zoneId: zone }, etag: profile.etag });}
    catch (error) {
      // A peer's selection wins. Read-only clients can still sign in, and a
      // lost response is resolved by reading, never by repeating the write.
      if (error.status !== 403 && error.status !== 412 && error.code !== 'VALIDATION' && !error.uncertain) throw error;
      return this.request('/me');
    }
  }
  async snapshot(range, selectedSession = null, {timeout = 30000} = {}) {
    const started=performance.now();let attempts=0;
    try {
      for (let attempt = 0; attempt < 3; attempt++) {
        const remaining=timeout-(performance.now()-started);if(remaining<=0)throw new ApiError('UNAVAILABLE');
        const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),remaining),options={signal:controller.signal};++attempts;
        try {
          // Bind the owner before issuing parallel reads, including empty pages.
          const profile = await this.request('/me',options);
          const dates = range(profile.data.zoneId);
          const [clients,projects,tasks,labels,workSessions,activeWorkSession,activities,active,report] = await Promise.all([
            this.page('/clients',{},options),this.page('/projects',{},options),this.page('/tasks',{},options),this.page('/labels',{},options),
            this.page('/work-sessions',{},options),this.request('/work-sessions/active',options),
            this.page('/activities',{from:dates.from,to:dates.to},options),this.request('/activities/active',options),
            this.request(`/reports/activities?${new URLSearchParams({from:dates.from,to:dates.to,zoneId:profile.data.zoneId})}`,options)
          ]);
          const chosen = workSessions.items.find((value) => value.id === selectedSession) || activeWorkSession.data || [...workSessions.items].sort((a, b) => b.start.localeCompare(a.start))[0];
          const [sessionReport,confirmed,head] = await Promise.all([
            chosen?this.request(`/work-sessions/${chosen.id}/report`,options):null,this.request('/me',options),this.page('/projects',{},options)
          ]);
          const workSessionReport=sessionReport?.data??null;
          if ([clients, tasks, labels, activities, workSessions, head, report.data, ...(workSessionReport ? [workSessionReport] : [])].some((v) => v.revision !== projects.revision) || profile.data.version !== confirmed.data.version) throw new ApiError('BUSY');
          return { profile, dates, clients: sortedNames(clients.items), projects: sortedNames(projects.items), tasks: tasks.items, labels: labels.items, activities: activities.items, active, workSessions: workSessions.items, activeWorkSession, workSessionReport, report: report.data, revision: projects.revision };
        } catch (error) {if (!['BUSY', 'CURSOR_EXPIRED'].includes(error.code) || attempt === 2) throw error;}
        finally {clearTimeout(timer);controller.abort();}
      }
    } finally {
      const durationMs=Math.round(performance.now()-started);
      if(durationMs>=5000)console.warn('Timekeeper synchronization slow',{durationMs,attempts});
    }
  }
  async changes(after) {
    const { data } = await this.request(`/changes?after=${after}&waitSeconds=5`, { timeout: 10000 });
    let sequence = after;
    if (!Array.isArray(data.items) || typeof data.hasMore !== 'boolean') throw new ApiError('UNAVAILABLE');
    for (const item of data.items) {
      if (!Number.isSafeInteger(item.sequence) || item.sequence <= sequence) throw new ApiError('UNAVAILABLE');
      sequence = item.sequence;
    }
    if (data.nextSequence !== sequence || data.hasMore && data.items.length === 0) throw new ApiError('RESYNC_REQUIRED', 409);
    return data;
  }
}

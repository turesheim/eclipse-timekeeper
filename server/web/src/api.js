const nameOrder = new Intl.Collator(undefined, {numeric: true, sensitivity: 'base'});
const sortedNames = values => [...values].sort((a,b) => nameOrder.compare(a.name,b.name) || a.id.localeCompare(b.id));
const messages = {
  INVALID_TOKEN: 'The token contains invalid characters or is too long. Copy the complete API token and try again.',
  UNDO_CONFLICT: 'This work session or its references changed. Undo cannot replace the current records.',
  UNDO_EXPIRED: 'The time to undo this change has expired.',
  UNAUTHENTICATED: 'Your session is no longer authorized. Sign in again with your account.',
  OWNER_CHANGED: 'The account for this session has changed. Sign out before using the intended account.',
  FORBIDDEN: 'Your account does not have permission to make this change.',
  SERVICE_PROOF_REQUIRED: 'Confirm your current administrator password to save service settings.',
  PASSKEY_PROOF_REQUIRED: 'Confirm your current password or use an existing passkey to make this change.',
  PASSKEY_REJECTED: 'The passkey could not be verified. Try again or use your username and password.',
  PASSKEY_ACCOUNT_SIGN_IN_REQUIRED: 'Sign in with your account password or a passkey to manage passkeys. An API token cannot authorize this change.',
  STALE_VERSION: 'This record changed. Your draft is kept; cancel and reopen the editor to review the current record.',
  PRECONDITION_FAILED: 'This record changed. Your draft is kept; cancel and reopen the editor to review the current record.',
  ACTIVE_STATE_CHANGED: 'Tracking changed in another client. Review the current activity before trying again.',
  VALIDATION: 'Check the values and relationships, then try again.',
  VALIDATION_FAILED: 'Check the values and relationships, then try again.',
  BAD_REQUEST: 'Check the entered values, then try again.',
  CONFLICT: 'This change conflicts with another record. Check dependencies and current values.',
  NOT_FOUND: 'This record no longer exists. Your draft is kept.',
  UNAVAILABLE: 'The server could not be reached. Reconnecting…',
  BUSY: 'Records are changing too quickly to load a consistent view. Try Refresh.',
  IMPORT_SOURCE_CHANGED: 'An entry with this source ID changed. Exclude it or resolve the source change before continuing; existing records were kept.',
  IMPORT_DUPLICATE_REVIEW_REQUIRED: 'Review matching entries and choose whether to reuse them or create distinct activities.',
  IMPORT_UNDO_REVIEW_REQUIRED: 'Review the edited or referenced records before continuing Undo.',
  IMPORT_RUNNING_MATCH: 'A previously imported activity is now running. Stop it or exclude that source entry before importing again.',
  IMPORT_TOO_MANY_MATCHES: 'Too many existing activities match this start time. Resolve these matches before importing this entry.',
  IMPORT_INTERVAL_TOO_LARGE: 'An entry spans too many days. Split or exclude that source entry.',
  IMPORT_BATCH_TOO_LARGE: 'This batch produces more than 1,000 daily activities. Use a smaller batch.',
};
export class ApiError extends Error {
  constructor(code, status = 0) { super(messages[code] || 'The request was rejected. Review the current records and try again.'); this.code = code; this.status = status; }
  get uncertain() { return this.status === 0 || this.status >= 500; }
}
export function ownerHeader(owner) {
  return btoa(String.fromCharCode(...new TextEncoder().encode(owner))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
function decodeOwner(value) {
  try {
    if (!value || !/^[A-Za-z0-9_-]{1,1100}$/.test(value)) throw new Error();
    const bytes = Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0));
    const owner = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
    if (!owner.trim() || owner.trim() !== owner || [...owner].length > 200 || ownerHeader(owner) !== value) throw new Error();
    return owner;
  } catch { throw new ApiError('UNAVAILABLE'); }
}
export class Api {
  #token; #csrf; #owner = null; #requests = new Set(); #closed = false;
  constructor(token = null, fetcher = (...args) => globalThis.fetch(...args), context = {}) { this.#token = token?.trim() ?? null; this.#csrf = context.csrf; this.#owner = context.ownerId ?? null; this.fetcher = fetcher; }
  get owner() { return this.#owner; }
  close() { this.#closed = true; this.#token = ''; this.#csrf = null; for (const request of this.#requests) request.abort(); }
  async request(path, {method = 'GET', body, etag, key, timeout = 15000, binary = false, undo = false} = {}) {
    if (this.#closed) throw new ApiError('UNAUTHENTICATED', 401);
    if (this.#token && (this.#token.length > 4096 || !/^[A-Za-z0-9._~+/-]+=*$/.test(this.#token))) throw new ApiError('INVALID_TOKEN', 401);
    const controller = new AbortController(); this.#requests.add(controller);
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const headers = {Accept: binary ? 'application/zip' : 'application/json'};
      if (this.#token) headers.Authorization = `Bearer ${this.#token}`;
      else if (this.#csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers['X-CSRF-Token'] = this.#csrf;
      if (this.#owner !== null) headers['X-Timekeeper-Owner'] = ownerHeader(this.#owner);
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (etag) headers['If-Match'] = etag;
      if (key) headers['Idempotency-Key'] = key;
      if (undo) headers['X-Timekeeper-Undo'] = 'true';
      const response = await this.fetcher(`/api/v1${path}`, {method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, redirect: 'error', referrerPolicy:'origin', credentials: this.#token && path !== '/auth/token-login' ? 'omit' : 'same-origin', cache: 'no-store'});
      if (binary && response.ok && !/^application\/zip(?:;|$)/i.test(response.headers.get('Content-Type') || '')) throw new ApiError('UNAVAILABLE');
      const data = response.status === 204 ? null : binary && response.ok ? await response.blob() : await response.json();
      if (!response.ok) throw new ApiError(typeof data?.code === 'string' ? data.code : 'UNAVAILABLE', response.status);
      if (this.#closed) throw new ApiError('UNAUTHENTICATED', 401);
      const owner = decodeOwner(response.headers.get('X-Timekeeper-Owner'));
      if (this.#owner !== null && owner !== this.#owner) throw new ApiError('OWNER_CHANGED', 401);
      this.#owner = owner;
      if (data?.ownerId !== undefined && data.ownerId !== owner) throw new ApiError('OWNER_CHANGED', 401);
      if (data?.items?.some(row => row.ownerId !== undefined && row.ownerId !== owner)) throw new ApiError('OWNER_CHANGED', 401);
      const undoId=response.headers.get('X-Timekeeper-Undo-Id'),seconds=Number(response.headers.get('X-Timekeeper-Undo-Seconds'));
      const offer=undoId&&/^[a-f0-9-]{36}$/.test(undoId)&&seconds>0&&seconds<=600 ? {id:undoId,seconds}:null;
      return {data, etag: response.headers.get('ETag'),...(offer ? {undo:offer}:{})};
    } catch (error) {
      if (error instanceof ApiError) throw error;
      // Never expose response bodies, URLs, tokens or fetch exception details.
      throw new ApiError('UNAVAILABLE');
    } finally { clearTimeout(timer); this.#requests.delete(controller); }
  }
  async page(path, parameters = {}) {
    let cursor = null, revision = null; const items = [], seen = new Set();
    do {
      const query = new URLSearchParams({...parameters, limit: 500, ...(cursor ? {cursor} : {})});
      const {data} = await this.request(`${path}?${query}`);
      if (!Array.isArray(data.items) || !Number.isSafeInteger(data.revision) || (revision !== null && revision !== data.revision)) throw new ApiError('BUSY');
      revision = data.revision; items.push(...data.items);
      cursor = data.nextCursor;
      if (cursor !== null && (typeof cursor !== 'string' || !cursor || seen.has(cursor))) throw new ApiError('UNAVAILABLE');
      seen.add(cursor);
    } while (cursor !== null);
    return {items, revision};
  }
  async initializeTimeZone(zone = Intl.DateTimeFormat().resolvedOptions().timeZone) {
    const profile = await this.request('/me');
    // Version zero is the untouched server default. An explicitly selected UTC
    // zone has a newer version and must be kept, just like any other choice.
    if (profile.data.version !== 0 || profile.data.zoneId !== 'UTC' || !zone || zone === 'UTC' || !profile.etag) return profile;
    try { new Intl.DateTimeFormat(undefined, {timeZone: zone}); } catch { return profile; }
    try { return await this.request('/me', {method: 'PUT', body: {zoneId: zone}, etag: profile.etag}); }
    catch (error) {
      // A peer's selection wins. Read-only clients can still sign in, and a
      // lost response is resolved by reading, never by repeating the write.
      if (error.status !== 403 && error.status !== 412 && error.code !== 'VALIDATION' && !error.uncertain) throw error;
      return this.request('/me');
    }
  }
  async snapshot(range, selectedSession = null) {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const profile = await this.request('/me');
        const dates = range(profile.data.zoneId);
        const clients = await this.page('/clients');
        const projects = await this.page('/projects'), tasks = await this.page('/tasks'), labels = await this.page('/labels');
        const workSessions = await this.page('/work-sessions');
        const activeWorkSession = await this.request('/work-sessions/active');
        const chosen = workSessions.items.find(value => value.id === selectedSession) || activeWorkSession.data || [...workSessions.items].sort((a,b) => b.start.localeCompare(a.start))[0];
        const workSessionReport = chosen ? (await this.request(`/work-sessions/${chosen.id}/report`)).data : null;
        const activities = await this.page('/activities', {from: dates.from, to: dates.to});
        const active = await this.request('/activities/active');
        const report = await this.request(`/reports/activities?${new URLSearchParams({from: dates.from, to: dates.to, zoneId: profile.data.zoneId})}`);
        const confirmed = await this.request('/me'), head = await this.page('/projects');
        if ([clients, tasks, labels, activities, workSessions, head, report.data, ...(workSessionReport ? [workSessionReport] : [])].some(v => v.revision !== projects.revision) || profile.data.version !== confirmed.data.version) throw new ApiError('BUSY');
        return {profile, dates, clients: sortedNames(clients.items), projects: sortedNames(projects.items), tasks: tasks.items, labels: labels.items, activities: activities.items, active, workSessions: workSessions.items, activeWorkSession, workSessionReport, report: report.data, revision: projects.revision};
      } catch (error) { if (!['BUSY', 'CURSOR_EXPIRED'].includes(error.code) || attempt === 2) throw error; }
    }
  }
  async changes(after) {
    const {data} = await this.request(`/changes?after=${after}&waitSeconds=5`, {timeout: 10000});
    let sequence = after;
    if (!Array.isArray(data.items) || typeof data.hasMore !== 'boolean') throw new ApiError('UNAVAILABLE');
    for (const item of data.items) {
      if (!Number.isSafeInteger(item.sequence) || item.sequence <= sequence) throw new ApiError('UNAVAILABLE');
      sequence = item.sequence;
    }
    if (data.nextSequence !== sequence || (data.hasMore && data.items.length === 0)) throw new ApiError('RESYNC_REQUIRED', 409);
    return data;
  }
}

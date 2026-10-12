const messages = {
  UNAUTHENTICATED: 'Your session is no longer authorized. Sign out and sign in with a valid token.',
  OWNER_CHANGED: 'The account for this session has changed. Sign out before using the intended account.',
  FORBIDDEN: 'This token does not have permission to make this change.',
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
};
export class ApiError extends Error {
  constructor(code, status = 0) { super(messages[code] || 'The request was rejected. Review the current records and try again.'); this.code = code; this.status = status; }
  get uncertain() { return this.status === 0 || this.status >= 500; }
}
function ownerHeader(owner) {
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
  #token; #owner = null; #requests = new Set(); #closed = false;
  constructor(token, fetcher = fetch) { this.#token = token; this.fetcher = fetcher; }
  get owner() { return this.#owner; }
  close() { this.#closed = true; this.#token = ''; for (const request of this.#requests) request.abort(); }
  async request(path, {method = 'GET', body, etag, key, timeout = 15000} = {}) {
    if (this.#closed) throw new ApiError('UNAUTHENTICATED', 401);
    const controller = new AbortController(); this.#requests.add(controller);
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const headers = {Authorization: `Bearer ${this.#token}`, Accept: 'application/json'};
      if (this.#owner !== null) headers['X-Timekeeper-Owner'] = ownerHeader(this.#owner);
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (etag) headers['If-Match'] = etag;
      if (key) headers['Idempotency-Key'] = key;
      const response = await this.fetcher(`/api/v1${path}`, {method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, redirect: 'error', credentials: 'omit', cache: 'no-store'});
      const data = response.status === 204 ? null : await response.json();
      if (!response.ok) throw new ApiError(typeof data?.code === 'string' ? data.code : 'UNAVAILABLE', response.status);
      if (this.#closed) throw new ApiError('UNAUTHENTICATED', 401);
      const owner = decodeOwner(response.headers.get('X-Timekeeper-Owner'));
      if (this.#owner !== null && owner !== this.#owner) throw new ApiError('OWNER_CHANGED', 401);
      this.#owner = owner;
      if (data?.ownerId !== undefined && data.ownerId !== owner) throw new ApiError('OWNER_CHANGED', 401);
      if (data?.items?.some(row => row.ownerId !== undefined && row.ownerId !== owner)) throw new ApiError('OWNER_CHANGED', 401);
      return {data, etag: response.headers.get('ETag')};
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
  async snapshot(range) {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const profile = await this.request('/me');
        const dates = range(profile.data.zoneId);
        const projects = await this.page('/projects'), tasks = await this.page('/tasks'), labels = await this.page('/labels');
        const activities = await this.page('/activities', {from: dates.from, to: dates.to});
        const active = await this.request('/activities/active');
        const report = await this.request(`/reports/activities?${new URLSearchParams({from: dates.from, to: dates.to, zoneId: profile.data.zoneId})}`);
        const confirmed = await this.request('/me'), head = await this.page('/projects');
        if ([tasks, labels, activities, head, report.data].some(v => v.revision !== projects.revision) || profile.data.version !== confirmed.data.version) throw new ApiError('BUSY');
        return {profile, dates, projects: projects.items, tasks: tasks.items, labels: labels.items, activities: activities.items, active, report: report.data, revision: projects.revision};
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

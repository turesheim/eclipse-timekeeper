import { t as i18nText } from "./i18n.js";import { addDays, dateInZone, dayStart, instantNanos } from './model.js';

export async function importDigest(value) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Stable source keys exclude filenames and chosen destination names/IDs. All defaults are reviewed. */
export async function importPlan(sourceRows, personKey, { fallbackClient, fallbackProject, mode = 'WORK_SESSION', mapWorkSessionTags = false, mapWorkSessionProjects = true }) {
  if (!['WORK_SESSION', 'ACTIVITY'].includes(mode)) throw new Error(i18nText("ui.choose_an_import_type"));
  const sourceKey = await importDigest(personKey),definitions = new Map(),rows = [];
  async function mapping(identity, resourceType, name, parentKey) {
    const key = await importDigest(JSON.stringify(identity));
    if (!definitions.has(key)) {
      const value = { key, resourceType, action: 'CREATE', name };
      if (resourceType === 'CLIENT' || resourceType === 'LABEL') value.color = '#2563eb';
      if (parentKey) value.parentKey = parentKey;
      definitions.set(key, value);
    }
    return key;
  }
  let selectedNanos = 0n;
  for (const source of sourceRows) {
    if (source.personKey !== personKey || source.errors.length || source.excluded) continue;
    const clientName = source.client || (mode === 'WORK_SESSION' ? source.project : '') || fallbackClient?.trim(),projectName = source.project || fallbackProject?.trim();
    if (!clientName || mode === 'ACTIVITY' && !projectName) throw new Error(i18nText("ui.choose_fallback_names_for_missing_clients_and_projects"));
    const clientKey = await mapping(mode === 'WORK_SESSION' ? ['WORK_SESSION_CLIENT', source.client, source.client ? '' : source.project] : ['CLIENT', source.client], 'CLIENT', clientName);
    const projectKey = mode === 'ACTIVITY' || mapWorkSessionProjects && source.project ? await mapping(['PROJECT', source.client, source.project], 'PROJECT', projectName, clientKey) : null;
    const taskKey = mode === 'ACTIVITY' && source.task ? await mapping(['TASK', source.client, source.project, source.task], 'TASK', source.task, projectKey) : null;
    const labelKeys = [];
    for (const tag of mode === 'ACTIVITY' || mapWorkSessionTags ? source.tags : []) labelKeys.push(await mapping(['LABEL', tag], 'LABEL', tag));
    // Retain legacy source metadata for resumable plans and duplicate hashes; sessions store summary alone.
    rows.push({ sourceKey, sourceId: source.sourceId, ...(source.line ? { sourceLine: source.line } : {}), start: source.start, end: source.end,
      durationNanos: source.durationNanos.toString(), client: source.client, project: source.project, task: source.task,
      summary: source.summary, tags: source.tags, ...(mode === 'WORK_SESSION' ? { clientKey, ...(projectKey ? { projectKey } : {}), title: source.summary.trim().slice(0, 1000).replace(/[\uD800-\uDBFF]$/, '') || 'Imported work session', ...(mapWorkSessionTags ? { labelKeys } : {}) } : { projectKey, taskKey, labelKeys }), occurrence: source.occurrence,
      duplicateReviewed: !!source.duplicateReviewed, ...(source.duplicateDecision ? { duplicateDecision: source.duplicateDecision } : {}) });
    selectedNanos += source.durationNanos;
  }
  if (!rows.length) throw new Error(i18nText("ui.select_at_least_one_valid_source_entry"));
  if (definitions.size > 1000) throw new Error(i18nText("ui.split_this_export_into_files_with_at_most_1_000_catalogue_mappings"));
  const selection = rows.map(({ clientKey, projectKey, taskKey, labelKeys, duplicateReviewed, duplicateDecision, sourceLine, ...source }) => source);
  const selectionFingerprint = await importDigest(JSON.stringify(selection));
  return { mode, sourceKey, mappings: [...definitions.values()], rows, selectionFingerprint, selectedEntries: rows.length, selectedNanos: selectedNanos.toString() };
}

export function importDayParts(row, zone) {
  const end = instantNanos(row.end);let cursor = instantNanos(row.start),count = 1;
  let date = dateInZone(row.start, zone);
  if (date === dateInZone(row.end, zone)) return 1;
  while (true) {
    date = addDays(date, 1);let start;
    try {start = dayStart(date, zone);}
    catch (error) {
      // The server's atStartOfDay skips an absent civil date, such as Apia 2011-12-30.
      if (error.message === i18nText("ui.this_date_does_not_exist_in_the_selected_time_zone")) continue;
      throw error;
    }
    const boundary = instantNanos(start);
    if (boundary >= end) return count;
    // A skipped civil date may have the same boundary as the preceding date.
    if (boundary > cursor) {cursor = boundary;count++;}
    if (count > 1000) throw new Error(i18nText("ui.an_interval_produces_more_than_1_000_daily_activities_split_or_exclude_that_source_en"));
  }
}

/** Deterministic layout for restart: count source rows separately from daily records. */
export function importBatches(rows, zone, offset = 0, mode = 'ACTIVITY') {
  if (!Number.isSafeInteger(offset) || offset < 0 || offset > rows.length) throw new Error(i18nText("ui.the_import_progress_is_inconsistent"));
  const batches = [];let batch = [],parts = 0;
  for (const row of rows.slice(offset)) {
    const count = mode === 'WORK_SESSION' ? 1 : importDayParts(row, zone);
    if (batch.length === 100 || parts + count > 1000) {batches.push(batch);batch = [];parts = 0;}
    batch.push(row);parts += count;
  }
  if (batch.length) batches.push(batch);
  return batches;
}

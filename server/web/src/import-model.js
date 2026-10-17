import {addDays, dateInZone, dayStart, instantNanos} from './model.js';

export async function importDigest(value) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}

/** Stable source keys exclude filenames and chosen destination names/IDs. All defaults are reviewed. */
export async function importPlan(sourceRows, personKey, {fallbackClient, fallbackProject}) {
  const sourceKey = await importDigest(personKey), definitions = new Map(), rows = [];
  async function mapping(identity, resourceType, name, parentKey) {
    const key = await importDigest(JSON.stringify(identity));
    if (!definitions.has(key)) {
      const value = {key, resourceType, action: 'CREATE', name};
      if (resourceType === 'CLIENT' || resourceType === 'LABEL') value.color = '#2563eb';
      if (parentKey) value.parentKey = parentKey;
      definitions.set(key, value);
    }
    return key;
  }
  let selectedNanos = 0n;
  for (const source of sourceRows) {
    if (source.personKey !== personKey || source.errors.length || source.excluded) continue;
    const clientName = source.client || fallbackClient?.trim(), projectName = source.project || fallbackProject?.trim();
    if (!clientName || !projectName) throw new Error('Choose fallback names for missing clients and projects.');
    const clientKey = await mapping(['CLIENT', source.client], 'CLIENT', clientName);
    const projectKey = await mapping(['PROJECT', source.client, source.project], 'PROJECT', projectName, clientKey);
    const taskKey = source.task ? await mapping(['TASK', source.client, source.project, source.task], 'TASK', source.task, projectKey) : null;
    const labelKeys = [];
    for (const tag of source.tags) labelKeys.push(await mapping(['LABEL', tag], 'LABEL', tag));
    rows.push({sourceKey, sourceId: source.sourceId, ...(source.line ? {sourceLine:source.line} : {}), start: source.start, end: source.end,
      durationNanos: source.durationNanos.toString(), client: source.client, project: source.project, task: source.task,
      summary: source.summary, tags: source.tags, projectKey, taskKey, labelKeys, occurrence: source.occurrence,
      duplicateReviewed: !!source.duplicateReviewed, ...(source.duplicateDecision ? {duplicateDecision: source.duplicateDecision} : {})});
    selectedNanos += source.durationNanos;
  }
  if (!rows.length) throw new Error('Select at least one valid source entry.');
  if (definitions.size > 1000) throw new Error('Split this export into files with at most 1,000 catalogue mappings.');
  const selection = rows.map(({projectKey, taskKey, labelKeys, duplicateReviewed, duplicateDecision, sourceLine, ...source}) => source);
  const selectionFingerprint = await importDigest(JSON.stringify(selection));
  return {sourceKey, mappings: [...definitions.values()], rows, selectionFingerprint, selectedEntries: rows.length, selectedNanos: selectedNanos.toString()};
}

export function importDayParts(row, zone) {
  const end = instantNanos(row.end); let cursor = instantNanos(row.start), count = 1;
  let date = dateInZone(row.start, zone);
  if(date===dateInZone(row.end,zone)) return 1;
  while (true) {
    date = addDays(date, 1);let start;
    try {start = dayStart(date, zone);}
    catch(error) {
      // The server's atStartOfDay skips an absent civil date, such as Apia 2011-12-30.
      if(error.message==='This date does not exist in the selected time zone.') continue;
      throw error;
    }
    const boundary = instantNanos(start);
    if (boundary >= end) return count;
    // A skipped civil date may have the same boundary as the preceding date.
    if (boundary > cursor) {cursor = boundary; count++;}
    if (count > 1000) throw new Error('An interval produces more than 1,000 daily activities. Split or exclude that source entry.');
  }
}

/** Deterministic layout for restart: count source rows separately from daily records. */
export function importBatches(rows, zone, offset = 0) {
  if (!Number.isSafeInteger(offset) || offset < 0 || offset > rows.length) throw new Error('The import progress is inconsistent.');
  const batches = []; let batch = [], parts = 0;
  for (const row of rows.slice(offset)) {
    const count = importDayParts(row, zone);
    if (batch.length === 100 || parts + count > 1000) {batches.push(batch); batch = []; parts = 0;}
    batch.push(row); parts += count;
  }
  if (batch.length) batches.push(batch);
  return batches;
}

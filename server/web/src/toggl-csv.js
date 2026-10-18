import {instantNanos, localDateTimeToInstant} from './model.js';

const MAX_ROWS = 100000, MAX_BYTES = 20 * 1024 * 1024;

/** Strict CSV records, including quoted commas/newlines and UTF-8 BOM. */
export function csvRecords(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > MAX_BYTES || text.includes('\0'))
    throw new Error('Choose a UTF-8 CSV file no larger than 20 MiB.');
  text = text.replace(/^\uFEFF/, '');
  const records = []; let fields = [], field = '', quoted = false, closed = false, line = 1, firstLine = 1;
  const finishField = () => { fields.push(field); field = ''; closed = false; };
  const finishRecord = () => {
    finishField();
    if (fields.some(value => value !== '')) records.push({line:firstLine, fields});
    if (records.length > MAX_ROWS + 1) throw new Error('Split this export into files of at most 100,000 rows.');
    fields = []; firstLine = line + 1;
  };
  for (let index = 0; index < text.length; index++) {
    const value = text[index];
    if (quoted) {
      if (value === '"') {
        if (text[index + 1] === '"') { field += '"'; index++; }
        else { quoted = false; closed = true; }
      } else { field += value; if (value === '\n') line++; }
    } else if (value === ',') finishField();
    else if (value === '\r' || value === '\n') {
      if (value === '\r' && text[index + 1] === '\n') index++;
      finishRecord(); line++;
    } else if (value === '"' && !field && !closed) quoted = true;
    else if (value === '"' || closed) throw new Error(`Malformed CSV at line ${line}.`);
    else field += value;
  }
  if (quoted) throw new Error(`Unclosed CSV field at line ${firstLine}.`);
  if (field || fields.length || closed) finishRecord();
  return records;
}

function date(value, format) {
  const text = value.trim(); let result;
  if (format === 'ISO') result = /^\d{4}-\d{2}-\d{2}$/.test(text) ? text : null;
  else {
    const match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(text);
    if (match) result = `${match[3]}-${(format === 'DMY' ? match[2] : match[1]).padStart(2,'0')}-${(format === 'DMY' ? match[1] : match[2]).padStart(2,'0')}`;
  }
  if (!result || !['ISO','DMY','MDY'].includes(format)) throw new Error('Choose the date format used by this export.');
  return result;
}

/** Preview only: this module never writes records or infers work sessions. */
export function parseTogglDetailedCsv(text, {zone, dateFormat = 'ISO', now = new Date().toISOString()} = {}) {
  if (!zone) throw new Error('Choose the source IANA time zone.');
  new Intl.DateTimeFormat('en', {timeZone:zone});
  const records = csvRecords(text), header = records.shift()?.fields.map(value => value.trim());
  if (!header?.length || new Set(header).size !== header.length || header.some(value => !value))
    throw new Error('The CSV header is missing, blank or duplicated.');
  const has = value => header.includes(value);
  const member = has('Member') ? 'Member' : has('User') ? 'User' : null;
  const end = has('Stop date') && has('Stop time') ? 'Stop' : has('End date') && has('End time') ? 'End' : null;
  if (!member || !end || !['Description','Start date','Start time'].every(has))
    throw new Error('Choose a Toggl Track Detailed Report or Legacy CSV with individual start/end times; summary reports are unsupported.');
  if ((has('Member') && has('User')) || (has('Stop date') && has('End date')))
    throw new Error('The CSV mixes incompatible Detailed Report and Legacy headers.');
  const recognized = new Set(['Description','Duration',member,'Email','Client','Project','Task','Tags','Start date','Start time',`${end} date`,`${end} time`,'Time Entry ID']);
  const unsupported = header.filter(value => !recognized.has(value));
  const people = new Map(), occurrences = new Map(); let totalNanos = 0n;
  const rows = records.map(record => {
    const result = {line:record.line, errors:[], warnings:[]};
    if (record.fields.length !== header.length) return {...result, errors:['The number of fields does not match the header.']};
    const value = Object.fromEntries(header.map((key,index) => [key,record.fields[index]]));
    const person = {name:value[member].trim(), email:(value.Email || '').trim().toLowerCase()};
    result.personKey = JSON.stringify(person.email ? ['email',person.email] : ['member',person.name]);
    if (!person.name && !person.email) result.errors.push('The source member is missing.');
    else people.set(result.personKey, person);
    result.client = (value.Client || '').trim(); result.project = (value.Project || '').trim();
    result.task = (value.Task || '').trim(); result.summary = value.Description;
    if (result.summary !== result.summary.trim()) result.warnings.push('Timekeeper trims surrounding description whitespace.');
    result.tags = [...new Set((value.Tags || '').split(',').map(tag => tag.trim()).filter(Boolean))].sort();
    result.sourceId = (value['Time Entry ID'] || '').trim() || null;
    if (result.summary.length > 2000 || [result.client,result.project,result.task,...result.tags].some(name => name.length > 1000))
      result.errors.push('A description or catalogue name exceeds Timekeeper’s length limit.');
    try {
      if (!value[`${end} date`].trim() || !value[`${end} time`].trim()) throw new Error('Only completed intervals can be imported.');
      result.start = localDateTimeToInstant(`${date(value['Start date'],dateFormat)}T${value['Start time'].trim()}`,zone);
      result.end = localDateTimeToInstant(`${date(value[`${end} date`],dateFormat)}T${value[`${end} time`].trim()}`,zone);
      const elapsed = instantNanos(result.end) - instantNanos(result.start);
      result.durationNanos = elapsed;
      if (elapsed < 0n) throw new Error('The end precedes the start.');
      if (instantNanos(result.start) > instantNanos(now)) throw new Error('The entry starts in the future.');
      if (instantNanos(result.end) > instantNanos(now)) throw new Error('Only completed intervals ending in the past can be imported.');
    } catch (error) { result.errors.push(error.message); }
    if (!result.errors.length) {
      // Do not include filename, header variant or chosen destination IDs: overlapping exports must match.
      result.content = JSON.stringify([instantNanos(result.start).toString(),instantNanos(result.end).toString(),result.client,result.project,result.task,result.summary.trim().replace(/\r\n?/g,'\n'),result.tags]);
      result.identity = JSON.stringify([result.personKey,result.sourceId ? ['id',result.sourceId] : ['entry',result.content]]);
      result.occurrence = occurrences.get(result.identity) || 0;
      occurrences.set(result.identity,result.occurrence + 1); totalNanos += result.durationNanos;
    }
    return result;
  });
  for (const row of rows) {
    row.ambiguousDuplicate = !!row.identity && occurrences.get(row.identity) > 1;
    if (row.ambiguousDuplicate && row.sourceId) row.errors.push('A stable entry ID occurs more than once; resolve these duplicate source rows.');
  }
  totalNanos = rows.filter(row => !row.errors.length).reduce((sum,row) => sum + row.durationNanos,0n);
  return {variant:`${member.toLowerCase()}-${end.toLowerCase()}`, headers:header, unsupported,
    people:[...people].map(([key,value]) => ({key,...value})), rows, totalNanos,
    validRows:rows.filter(row => !row.errors.length).length, rejectedRows:rows.filter(row => row.errors.length).length};
}

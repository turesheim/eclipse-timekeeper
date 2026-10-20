import {dateTimeFormat} from './formatters.js';
import { t as i18nText } from "./i18n.js";import { instantNanos, localDateTimeToInstant } from './model.js';

const MAX_ROWS = 100000,MAX_BYTES = 20 * 1024 * 1024;

/** Strict CSV records, including quoted commas/newlines and UTF-8 BOM. */
export function csvRecords(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > MAX_BYTES || text.includes('\0'))
  throw new Error(i18nText("ui.choose_a_utf_8_csv_file_no_larger_than_20_mib"));
  text = text.replace(/^\uFEFF/, '');
  const records = [];let fields = [],field = '',quoted = false,closed = false,line = 1,firstLine = 1;
  const finishField = () => {fields.push(field);field = '';closed = false;};
  const finishRecord = () => {
    finishField();
    if (fields.some((value) => value !== '')) records.push({ line: firstLine, fields });
    if (records.length > MAX_ROWS + 1) throw new Error(i18nText("ui.split_this_export_into_files_of_at_most_100_000_rows"));
    fields = [];firstLine = line + 1;
  };
  for (let index = 0; index < text.length; index++) {
    const value = text[index];
    if (quoted) {
      if (value === '"') {
        if (text[index + 1] === '"') {field += '"';index++;} else
        {quoted = false;closed = true;}
      } else {field += value;if (value === '\n') line++;}
    } else if (value === ',') finishField();else
    if (value === '\r' || value === '\n') {
      if (value === '\r' && text[index + 1] === '\n') index++;
      finishRecord();line++;
    } else if (value === '"' && !field && !closed) quoted = true;else
    if (value === '"' || closed) throw new Error(i18nText("ui.malformed_csv_at_line", { value0: line }));else
    field += value;
  }
  if (quoted) throw new Error(i18nText("ui.unclosed_csv_field_at_line", { value0: firstLine }));
  if (field || fields.length || closed) finishRecord();
  return records;
}

function date(value, format) {
  const text = value.trim();let result;
  if (format === 'ISO') result = /^\d{4}-\d{2}-\d{2}$/.test(text) ? text : null;else
  {
    const match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(text);
    if (match) result = `${match[3]}-${(format === 'DMY' ? match[2] : match[1]).padStart(2, '0')}-${(format === 'DMY' ? match[1] : match[2]).padStart(2, '0')}`;
  }
  if (!result || !['ISO', 'DMY', 'MDY'].includes(format)) throw new Error(i18nText("ui.choose_the_date_format_used_by_this_export"));
  return result;
}

/** Preview only: this module never writes records or infers work sessions. */
export function parseTogglDetailedCsv(text, { zone, dateFormat = 'ISO', now = new Date().toISOString() } = {}) {
  if (!zone) throw new Error(i18nText("ui.choose_the_source_iana_time_zone"));
  try {dateTimeFormat('en', { timeZone: zone });}
  catch {throw new Error(i18nText("ui.choose_the_source_iana_time_zone"));}
  const records = csvRecords(text),header = records.shift()?.fields.map((value) => value.trim());
  if (!header?.length || new Set(header).size !== header.length || header.some((value) => !value))
  throw new Error(i18nText("ui.the_csv_header_is_missing_blank_or_duplicated"));
  const has = (value) => header.includes(value);
  const member = has('Member') ? 'Member' : has('User') ? 'User' : null;
  const end = has('Stop date') && has('Stop time') ? 'Stop' : has('End date') && has('End time') ? 'End' : null;
  if (!member || !end || !['Description', 'Start date', 'Start time'].every(has))
  throw new Error(i18nText("ui.choose_a_toggl_track_detailed_report_or_legacy_csv_with_individual_start_end_times_su"));
  if (has('Member') && has('User') || has('Stop date') && has('End date'))
  throw new Error(i18nText("ui.the_csv_mixes_incompatible_detailed_report_and_legacy_headers"));
  const recognized = new Set(['Description', 'Duration', member, 'Email', 'Client', 'Project', 'Task', 'Tags', 'Start date', 'Start time', `${end} date`, `${end} time`, 'Time Entry ID']);
  const unsupported = header.filter((value) => !recognized.has(value));
  const people = new Map(),occurrences = new Map();let totalNanos = 0n;
  const rows = records.map((record) => {
    const result = { line: record.line, errors: [], warnings: [] };
    if (record.fields.length !== header.length) return { ...result, errors: [i18nText("ui.the_number_of_fields_does_not_match_the_header")] };
    const value = Object.fromEntries(header.map((key, index) => [key, record.fields[index]]));
    const person = { name: value[member].trim(), email: (value.Email || '').trim().toLowerCase() };
    result.personKey = JSON.stringify(person.email ? ['email', person.email] : ['member', person.name]);
    if (!person.name && !person.email) result.errors.push(i18nText("ui.the_source_member_is_missing"));else
    people.set(result.personKey, person);
    result.client = (value.Client || '').trim();result.project = (value.Project || '').trim();
    result.task = (value.Task || '').trim();result.summary = value.Description;
    if (result.summary !== result.summary.trim()) result.warnings.push(i18nText("ui.timekeeper_trims_surrounding_description_whitespace"));
    result.tags = [...new Set((value.Tags || '').split(',').map((tag) => tag.trim()).filter(Boolean))].sort();
    result.sourceId = (value['Time Entry ID'] || '').trim() || null;
    if (result.summary.length > 2000 || [result.client, result.project, result.task, ...result.tags].some((name) => name.length > 1000))
    result.errors.push(i18nText("ui.a_description_or_catalogue_name_exceeds_timekeeper_s_length_limit"));
    try {
      if (!value[`${end} date`].trim() || !value[`${end} time`].trim()) throw new Error(i18nText("ui.only_completed_intervals_can_be_imported"));
      result.start = localDateTimeToInstant(`${date(value['Start date'], dateFormat)}T${value['Start time'].trim()}`, zone);
      result.end = localDateTimeToInstant(`${date(value[`${end} date`], dateFormat)}T${value[`${end} time`].trim()}`, zone);
      const elapsed = instantNanos(result.end) - instantNanos(result.start);
      result.durationNanos = elapsed;
      if (elapsed < 0n) throw new Error(i18nText("ui.the_end_precedes_the_start"));
      if (instantNanos(result.start) > instantNanos(now)) throw new Error(i18nText("ui.the_entry_starts_in_the_future"));
      if (instantNanos(result.end) > instantNanos(now)) throw new Error(i18nText("ui.only_completed_intervals_ending_in_the_past_can_be_imported"));
    } catch (error) {result.errors.push(error.message);}
    if (!result.errors.length) {
      // Do not include filename, header variant or chosen destination IDs: overlapping exports must match.
      result.content = JSON.stringify([instantNanos(result.start).toString(), instantNanos(result.end).toString(), result.client, result.project, result.task, result.summary.trim().replace(/\r\n?/g, '\n'), result.tags]);
      result.identity = JSON.stringify([result.personKey, result.sourceId ? ['id', result.sourceId] : ['entry', result.content]]);
      result.occurrence = occurrences.get(result.identity) || 0;
      occurrences.set(result.identity, result.occurrence + 1);totalNanos += result.durationNanos;
    }
    return result;
  });
  for (const row of rows) {
    row.ambiguousDuplicate = !!row.identity && occurrences.get(row.identity) > 1;
    if (row.ambiguousDuplicate && row.sourceId) row.errors.push(i18nText("ui.a_stable_entry_id_occurs_more_than_once_resolve_these_duplicate_source_rows"));
  }
  totalNanos = rows.filter((row) => !row.errors.length).reduce((sum, row) => sum + row.durationNanos, 0n);
  return { variant: `${member.toLowerCase()}-${end.toLowerCase()}`, headers: header, unsupported,
    people: [...people].map(([key, value]) => ({ key, ...value })), rows, totalNanos,
    validRows: rows.filter((row) => !row.errors.length).length, rejectedRows: rows.filter((row) => row.errors.length).length };
}

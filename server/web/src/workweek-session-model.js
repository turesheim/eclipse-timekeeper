import {getLocale} from './i18n.js';
import { t as i18nText } from "./i18n.js";import { addDays, dayStart, instantNanos, localDateTime, localDateTimeToInstant } from './model.js';

function boundary(date, zone) {
  try {return instantNanos(dayStart(date, zone));}
  catch {return instantNanos(dayStart(addDays(date, 1), zone));}
}
const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
/** Group exact local-day slices without merging the underlying work sessions. */
export function workweekSessionGroups({ sessions, clients, projects, first, zone, asOf, selectedDay = null }) {
  const now = typeof asOf === 'bigint' ? asOf : instantNanos(asOf);
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = addDays(first, index);return { date, from: boundary(date, zone), to: boundary(addDays(date, 1), zone) };
  });
  const groups = new Map(),daily = Array(7).fill(0n);
  for (const session of [...sessions].sort((a, b) => compare(instantNanos(a.start), instantNanos(b.start)) || a.id.localeCompare(b.id))) {
    const start = instantNanos(session.start),end = session.end ? instantNanos(session.end) : now;
    if (selectedDay && !days.some((day) => day.date === selectedDay && start < day.to && end > day.from)) continue;
    for (const [index, day] of days.entries()) {
      const from = start > day.from ? start : day.from,to = end < day.to ? end : day.to;
      if (to <= from) continue;
      if (!groups.has(session.clientId)) {
        const client = clients.find((client) => client.id === session.clientId);
        groups.set(session.clientId, { id: session.clientId, name: client?.name || i18nText("ui.client"), color: client?.color, projects: new Map(), daily: Array(7).fill(0n), total: 0n });
      }
      const client = groups.get(session.clientId),key = session.projectId || '';
      if (!client.projects.has(key)) client.projects.set(key, { id: session.projectId || null, name: projects.find((project) => project.id === session.projectId)?.name || i18nText("ui.no_project"), days: days.map((day) => ({ date: day.date, entries: [], total: 0n })), total: 0n });
      const project = client.projects.get(key),duration = to - from,startEditable = start === from,endEditable = session.end ? end === to : now >= day.from && now < day.to;
      project.days[index].entries.push({ session, date: day.date, duration, startEditable, endEditable,
        start: startEditable ? localDateTime(session.start, zone).split('T')[1] : '00:00',
        end: session.end && endEditable && end < day.to ? localDateTime(session.end, zone).split('T')[1] : to === day.to ? '24:00' : session.end ? localDateTime(session.end, zone).split('T')[1] : '',
        running: !session.end && endEditable });
      project.days[index].total += duration;project.total += duration;client.daily[index] += duration;client.total += duration;daily[index] += duration;
    }
  }
  return { days, clients: [...groups.values()].sort((a, b) => a.name.localeCompare(b.name,getLocale()) || a.id.localeCompare(b.id)).map((client) => ({ ...client, projects: [...client.projects.values()].sort((a, b) => a.name.localeCompare(b.name,getLocale()) || (a.id || '').localeCompare(b.id || '')) })), daily, total: daily.reduce((sum, value) => sum + value, 0n) };
}
export function compactClock(value) {return value ? value.slice(0, 5) : '';}
/** Unchanged display values retain the original instant, fractional seconds and DST occurrence. */
export function sessionTimeEdit(original, edits, zone) {
  const endpoint = (key) => {
    const edit = edits[key];if (!edit || edit.text === edit.original || edit.text === compactClock(edit.original)) return original[key];
    const text = edit.text.trim();
    if (!text) {if (key === 'end' && !original.end) return null;throw new Error(i18nText("ui.enter_a_time", { value0:i18nText('time.'+key) }));}
    if (key === 'end' && /^24:00(?::00(?:\.0{1,9})?)?$/.test(text)) return dayStart(addDays(edit.date, 1), zone);
    return localDateTimeToInstant(edit.date + 'T' + text, zone);
  };
  const start = endpoint('start'),end = endpoint('end');
  if (end && instantNanos(end) <= instantNanos(start)) throw new Error(i18nText("ui.end_must_be_after_start"));
  return { clientId: original.clientId, projectId: original.projectId || null, summary: original.summary || '', labelIds: original.labelIds || [], start, end };
}

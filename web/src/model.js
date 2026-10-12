// Dates and report totals are independent of the browser's local time zone.
export function dateInZone(instant, zone) {
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date(instant));
  const part = type => parts.find(p => p.type === type).value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}
export function addDays(date, count) {
  const value = new Date(`${date}T12:00:00Z`);
  value.setUTCDate(value.getUTCDate() + count);
  return value.toISOString().slice(0, 10);
}
export function monday(date) {
  return addDays(date, -((new Date(`${date}T12:00:00Z`).getUTCDay() + 6) % 7));
}
export function dayStart(date, zone) {
  // Binary search the first instant whose civil date is the requested date.
  // This also handles midnight offset changes and 23/25-hour DST days.
  const noon = Date.parse(`${date}T12:00:00Z`);
  if (!Number.isFinite(noon)) throw new Error('Choose a valid date.');
  let low = noon - 48 * 3600000, high = noon + 48 * 3600000;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (dateInZone(middle, zone) < date) low = middle + 1;
    else high = middle;
  }
  if (dateInZone(low, zone) !== date) throw new Error('This date does not exist in the selected time zone.');
  return new Date(low).toISOString();
}
export function weekRange(date, zone) {
  const first = monday(date);
  return {first, from: dayStart(first, zone), to: dayStart(addDays(first, 7), zone)};
}
export function durationNanos(duration) {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?$/.exec(duration);
  if (!match || !match.slice(1).some(Boolean)) throw new Error('Invalid report duration.');
  return (BigInt(match[1] || 0) * 3600n + BigInt(match[2] || 0) * 60n + BigInt(match[3] || 0)) * 1000000000n + BigInt((match[4] || '').padEnd(9, '0'));
}
export function durationText(duration) {
  const total = durationNanos(duration), seconds = total / 1000000000n;
  let value = `${seconds / 3600n}h ${(seconds / 60n) % 60n}m`;
  if (seconds % 60n || total % 1000000000n) {
    const fraction = (total % 1000000000n).toString().padStart(9, '0').replace(/0+$/, '');
    value += ` ${seconds % 60n}${fraction ? '.' + fraction : ''}s`;
  }
  return value;
}
export function instantText(instant, zone) {
  if (!instant) return 'In progress';
  return new Intl.DateTimeFormat(undefined, {timeZone: zone, month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'}).format(new Date(instant));
}

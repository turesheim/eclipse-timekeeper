import {addDays, dayStart, instantNanos, nanosInstant, localDateTime, localDateTimeToInstant, dateInZone} from './model.js';

export const CALENDAR_STEP = 15n * 60n * 1000000000n;
const compare = (a,b) => a < b ? -1 : a > b ? 1 : 0;

/** Shared wall-clock rows, with extra rows for repeated times and gaps on affected days. */
export function calendarWeek(first, zone) {
  const clock = new Intl.DateTimeFormat('en-GB',{timeZone:zone,hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
  const days = Array.from({length:7},(_,index) => {
    const date = addDays(first,index), slots = [];
    let start;
    try { start = instantNanos(dayStart(date,zone)); } catch { return {date,slots,skipped:true}; }
    let next=index+1, end;
    while (end === undefined && next <= index+3) {
      try { end=instantNanos(dayStart(addDays(first,next),zone)); } catch { next++; }
    }
    if (end === undefined) throw new Error('Could not resolve the next calendar day.');
    const occurrences=new Map();
    for (let from=start;from<end;from+=CALENDAR_STEP) {
      const label=clock.format(new Date(Number(from/1000000n))), occurrence=occurrences.get(label) || 0;
      occurrences.set(label,occurrence+1);
      slots.push({key:`${label}/${occurrence}`,label,occurrence,start:from,end:from+CALENDAR_STEP<end ? from+CALENDAR_STEP : end});
    }
    return {date,start,end,slots,skipped:false};
  });
  const rows=[];
  for(const day of days) {
    let cursor=0;
    for(const slot of day.slots) {
      const existing=rows.indexOf(slot.key,cursor);
      if(existing<0) rows.splice(cursor++,0,slot.key); else cursor=existing+1;
    }
  }
  for(const day of days) {
    day.byRow=new Map();
    for(const slot of day.slots) {slot.row=rows.indexOf(slot.key);day.byRow.set(slot.row,slot);}
  }
  return {first,zone,rows,days,hasTransitions:days.some(day=>day.slots.length!==96)};
}

/** A record may have several visible pieces, but always retains its original identity. */
export function calendarPieces(start, end, calendar) {
  const first=instantNanos(start),until=instantNanos(end),last=until===first ? first+1n : until,pieces=[];
  for(const day of calendar.days) {
    let piece=null;
    for(const slot of day.slots) {
      const from=first>slot.start ? first : slot.start,to=last<slot.end ? last : slot.end;
      if(to<=from) continue;
      const top=slot.row+Number(from-slot.start)/Number(slot.end-slot.start);
      const bottom=slot.row+Number(to-slot.start)/Number(slot.end-slot.start);
      if(piece && Math.abs(piece.bottom-top)<1e-9) {piece.bottom=bottom;piece.end=to;}
      else {piece={date:day.date,top,bottom,start:from,end:to};pieces.push(piece);}
    }
  }
  return pieces;
}

export function calendarAnchor(calendar, date, row) {
  const day=calendar.days.find(value=>value.date===date);
  if(!day || day.skipped) throw new Error('This date does not exist in the selected time zone.');
  if(row===calendar.rows.length) return day.end;
  const slot=day.byRow.get(row);
  if(!slot) throw new Error('This clock time does not occur on that day. Choose an unshaded time slot.');
  return slot.start;
}

export function shiftSession(session, delta, mode='move', asOf=null) {
  let start=instantNanos(session.start),end=session.end ? instantNanos(session.end) : null;
  if(mode==='move') {start+=delta;if(end!==null) end+=delta;}
  else if(mode==='start') start+=delta;
  else if(mode==='end') end=(end ?? instantNanos(asOf))+delta;
  else throw new Error('Unknown calendar operation.');
  if(end!==null && end<start) throw new Error('End must not be before start.');
  return {summary:session.summary,start:nanosInstant(start),end:end===null ? null : nanosInstant(end)};
}

export function pasteSession(session, target, zone) {
  const start=instantNanos(session.start),end=session.end ? instantNanos(session.end) : null;
  // Keep sub-quarter-hour precision as well as exact elapsed duration.
  const clock=localDateTime(session.start,zone).slice(11),parts=/^\d{2}:(\d{2}):(\d{2})/.exec(clock);
  const fraction=((start%1000000000n)+1000000000n)%1000000000n;
  const remainder=BigInt((Number(parts[1])%15)*60+Number(parts[2]))*1000000000n+fraction;
  return {summary:session.summary,start:nanosInstant(target+remainder),end:end===null ? null : nanosInstant(target+remainder+end-start)};
}

export function shiftSessionDays(session, days, zone) {
  const date=addDays(dateInZone(session.start,zone),days);
  const clock=localDateTime(session.start,zone).slice(11).replace(/(?:Z|[+-]\d{2}:\d{2}(?::\d{2})?)$/,'');
  const target=localDateTimeToInstant(`${date}T${clock}`,zone);
  return shiftSession(session,instantNanos(target)-instantNanos(session.start));
}

export function sameSession(a,b) {
  return ['id','version','ownerId','start','end','summary','state'].every(key=>(a[key] ?? null)===(b[key] ?? null));
}

/** Group touching intervals for the same task; gaps remain visibly unallocated. */
export function calendarTaskBlocks(activities, asOf) {
  const groups=new Map(),blocks=[];
  for(const value of activities) {
    const key=value.taskId || `activity:${value.id}`;
    if(!groups.has(key)) groups.set(key,[]);groups.get(key).push(value);
  }
  for(const values of groups.values()) {
    values.sort((a,b)=>compare(instantNanos(a.start),instantNanos(b.start)));
    let current=null;
    for(const value of values) {
      const start=instantNanos(value.start),end=instantNanos(value.end || asOf);
      if(!current || start>current.end) {current={taskId:value.taskId,start,end,activities:[value]};blocks.push(current);}
      else {current.end=end>current.end ? end : current.end;current.activities.push(value);}
    }
  }
  return blocks;
}

/** Place overlapping cards in separate lanes, using their rendered bounds. */
export function calendarLanes(pieces, minimum=0) {
  const sorted=pieces.map(value=>({...value,bottom:Math.max(value.bottom,value.top+minimum)})).sort((a,b)=>a.top-b.top || b.bottom-a.bottom);
  let component=[],ends=[];
  const finish=()=>{for(const value of component) value.lanes=ends.length;component=[];ends=[];};
  for(const piece of sorted) {
    if(component.length && ends.every(end=>end<=piece.top)) finish();
    let lane=ends.findIndex(end=>end<=piece.top);if(lane<0) lane=ends.length;
    ends[lane]=piece.bottom;piece.lane=lane;component.push(piece);
  }
  finish();return sorted;
}

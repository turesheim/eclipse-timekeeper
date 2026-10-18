import {addDays,dayStart,monday,instantNanos} from './model.js';

export function shiftMonth(month,offset) {
  if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new Error('Choose a valid month.');
  const date=new Date(month+'-01T12:00:00Z');date.setUTCMonth(date.getUTCMonth()+offset);
  const result=date.toISOString().slice(0,7);
  if(!/^\d{4}-\d{2}$/.test(result)) throw new Error('Choose a month between years 0001 and 9998.');
  return result;
}
function boundary(date,zone) {
  try {return instantNanos(dayStart(date,zone));}
  catch {
    // A civil date can be skipped entirely by an offset change (e.g. Apia).
    // Its zero-width interval ends at the next existing date.
    return instantNanos(dayStart(addDays(date,1),zone));
  }
}
export function monthCalendar(month,zone) {
  const next=shiftMonth(month,1),first=month+'-01',gridFirst=monday(first);
  const gridEnd=addDays(monday(addDays(next+'-01',-1)),7);
  const days=[];
  for(let date=gridFirst;date<gridEnd;date=addDays(date,1)) {
    days.push({date,inMonth:date.startsWith(month),from:boundary(date,zone),to:boundary(addDays(date,1),zone)});
  }
  return {month,zone,from:boundary(first,zone),to:boundary(next+'-01',zone),days};
}
function overlap(start,end,from,to) {
  const first=start>from ? start:from,last=end<to ? end:to;
  return last>first ? last-first:0n;
}
/** Sum recorded session durations; task activities never participate in these totals. */
export function monthSessions(calendar,sessions,asOf) {
  const now=typeof asOf==='bigint' ? asOf:instantNanos(asOf),clients=new Map();let total=0n;
  const days=calendar.days.map(day=>({...day,total:0n,clients:new Map()}));
  for(const session of sessions) {
    const start=instantNanos(session.start),end=session.end ? instantNanos(session.end):now;
    const duration=overlap(start,end,calendar.from,calendar.to);
    if(!duration) continue;
    total+=duration;clients.set(session.clientId,(clients.get(session.clientId)||0n)+duration);
    for(const day of days) if(day.inMonth) {
      const duration=overlap(start,end,day.from,day.to);
      if(duration) {
        day.total+=duration;
        const client=day.clients.get(session.clientId) || {clientId:session.clientId,duration:0n,running:false,sessionIds:[]};
        client.sessionIds.push(session.id);
        client.duration+=duration;client.running ||= !session.end && now>=day.from && now<day.to;
        day.clients.set(session.clientId,client);
      }
    }
  }
  return {total,clients,days};
}

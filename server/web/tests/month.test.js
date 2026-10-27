import {test} from 'node:test';
import assert from 'node:assert/strict';
import {monthCalendar,monthSessions,shiftMonth} from '../src/month-model.js';
import {isoWeek} from '../src/week-note-editor.js';
const hour=3600000000000n;
const session=(id,clientId,start,end)=>({id,clientId,start,end,title:id});

test('ISO week names use the week-based year across New Year',()=>{
  assert.equal(isoWeek('2026-10-27'),'2026-W44');
  assert.equal(isoWeek('2026-12-31'),'2026-W53');
  assert.equal(isoWeek('2027-01-01'),'2026-W53');
  assert.equal(isoWeek('2027-01-04'),'2027-W01');
});

test('month grid has Monday columns and vertical weeks across year/leap boundaries',()=> {
  const calendar=monthCalendar('2024-02','Europe/Oslo');
  assert.equal(calendar.days.length,35);assert.equal(calendar.days[0].date,'2024-01-29');assert.equal(calendar.days.at(-1).date,'2024-03-03');
  assert.equal(calendar.days.filter(day=>day.inMonth).length,29);
  assert.equal(shiftMonth('2024-12',1),'2025-01');assert.equal(shiftMonth('2025-01',-1),'2024-12');
  assert.throws(()=>monthCalendar('2025-13','Europe/Oslo'));
});
test('client totals clip sessions to local month and preserve exact DST duration',()=> {
  const calendar=monthCalendar('2025-10','Europe/Oslo');
  const result=monthSessions(calendar,[
    session('left','a','2025-09-30T21:00:00Z','2025-09-30T23:00:00Z'),
    session('fall','a','2025-10-25T22:00:00Z','2025-10-26T23:00:00Z'),
    session('right','b','2025-10-31T22:00:00Z','2025-11-01T02:00:00Z'),
    session('outside','b','2025-11-01T00:00:00Z','2025-11-01T01:00:00Z'),
  ],'2025-11-02T00:00:00Z');
  assert.equal(result.clients.get('a'),26n*hour);assert.equal(result.clients.get('b'),hour);
  assert.equal(result.total,27n*hour);assert.equal(result.days.find(day=>day.date==='2025-10-26').total,25n*hour);
  assert.equal(result.days.reduce((sum,day)=>sum+day.total,0n),result.total);
});
test('running sessions use authoritative as-of time and retain nanoseconds',()=> {
  const calendar=monthCalendar('2025-03','Europe/Oslo');
  const running=session('running','a','2025-03-29T23:00:00.000000001Z',null);
  const result=monthSessions(calendar,[running],'2025-03-30T22:00:00Z');
  assert.equal(result.total,23n*hour-1n);assert.equal(result.days.find(day=>day.date==='2025-03-30').total,result.total);
  assert.equal(monthSessions(calendar,[running],'2025-03-29T22:59:59Z').total,0n);
});
test('skipped local dates remain empty and do not lose month time',()=> {
  const calendar=monthCalendar('2011-12','Pacific/Apia');
  const result=monthSessions(calendar,[session('date-line','a','2011-12-29T10:00:00Z','2011-12-31T10:00:00Z')],'2012-01-01T00:00:00Z');
  assert.equal(result.total,48n*hour);assert.equal(result.days.find(day=>day.date==='2011-12-30').total,0n);
  assert.equal(result.days.reduce((sum,day)=>sum+day.total,0n),result.total);
});

test('daily client totals combine all same-client sessions and keep clients and local days separate',()=>{
  const calendar=monthCalendar('2025-10','Europe/Oslo');
  const result=monthSessions(calendar,[
    session('overnight','a','2025-10-25T21:00:00Z','2025-10-25T23:00:00Z'),
    session('fall','a','2025-10-25T22:00:00Z','2025-10-26T23:00:00Z'),
    session('extra','a','2025-10-26T12:00:00.000000001Z','2025-10-26T13:00:00Z'),
    session('other','b','2025-10-26T15:00:00Z','2025-10-26T17:00:00Z'),
  ],'2025-11-01T00:00:00Z');
  const day=result.days.find(value=>value.date==='2025-10-26');assert.equal(day.clients.size,2);
  assert.equal(day.clients.get('a').duration,27n*hour-1n);assert.equal(day.clients.get('b').duration,2n*hour);
  assert.deepEqual(day.clients.get('a').sessionIds,['overnight','fall','extra']);assert.deepEqual(day.clients.get('b').sessionIds,['other']);
  assert.deepEqual(result.days.find(value=>value.date==='2025-10-25').clients.get('a').sessionIds,['overnight']);
  assert.equal(result.days.find(value=>value.date==='2025-10-25').clients.get('a').duration,hour);
  assert.equal(day.total,[...day.clients.values()].reduce((sum,value)=>sum+value.duration,0n));
  assert.equal(result.days.reduce((sum,day)=>sum+day.total,0n),result.total);
  const running=monthSessions(calendar,[session('running','a','2025-10-25T21:00:00Z',null)],'2025-10-26T12:00:00Z');
  assert.equal(running.days.find(value=>value.date==='2025-10-25').clients.get('a').running,false);
  assert.equal(running.days.find(value=>value.date==='2025-10-26').clients.get('a').running,true);
});

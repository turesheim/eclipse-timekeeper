import {test} from 'node:test';
import assert from 'node:assert/strict';
import {calendarWeek,calendarPieces,calendarAnchor,calendarRange,shiftSession,shiftSessionDays,pasteSession,sameSession,calendarTaskBlocks,calendarLanes} from '../src/calendar-model.js';
import {instantNanos,nanosInstant} from '../src/model.js';

test('calendar shows seven local days with distinct repeated slots and noninteractive missing times',()=>{
  const fall=calendarWeek('2026-10-19','Europe/Oslo');
  assert.equal(fall.days.length,7);assert.equal(fall.rows.length,100);
  assert.equal(fall.days[0].slots.length,96);assert.equal(fall.days[6].slots.length,100);
  const repeated=fall.rows.indexOf('02:15/1');
  assert.equal(nanosInstant(calendarAnchor(fall,'2026-10-25',repeated)),'2026-10-25T01:15:00Z');
  assert.throws(()=>calendarAnchor(fall,'2026-10-19',repeated),/does not occur/);
  const spring=calendarWeek('2026-03-23','Europe/Oslo');
  assert.equal(spring.rows.length,96);assert.equal(spring.days[6].slots.length,92);
  assert.throws(()=>calendarAnchor(spring,'2026-03-29',spring.rows.indexOf('02:30/0')),/does not occur/);
  const skipped=calendarWeek('2011-12-26','Pacific/Apia');
  assert.equal(skipped.days[4].skipped,true);assert.equal(skipped.days[3].slots.length,96);
});
test('multi-day and running sessions retain one identity and split only their visual pieces',()=>{
  const week=calendarWeek('2026-10-19','Europe/Oslo');
  const pieces=calendarPieces('2026-10-24T21:30:00.123456789Z','2026-10-26T00:30:00.123456789Z',week);
  assert.deepEqual(pieces.map(value=>value.date),['2026-10-24','2026-10-25']);
  const sunday=pieces.find(value=>value.date==='2026-10-25');
  assert.equal(sunday.end-sunday.start,25n*3600000000000n);
  assert.equal(calendarPieces('2026-10-25T00:30:00Z','2026-10-25T01:30:00Z',week).length,1);
  const zero=calendarPieces('2026-10-25T00:30:00Z','2026-10-25T00:30:00Z',week);
  assert.equal(zero.length,1);
});
test('moving, resizing and copying keep exact precision, elapsed duration and independent activity links',()=>{
  const session={id:'source',version:3,ownerId:'owner',labelIds:['first','second'],summary:'Day',clientId:'client',projectId:'project',title:'A day',start:'2026-10-23T06:03:00.123456789Z',end:'2026-10-23T14:03:00.987654321Z',state:'COMPLETED'};
  const moved=shiftSessionDays(session,3,'Europe/Oslo');
  assert.equal(moved.start,'2026-10-26T07:03:00.123456789Z');
  assert.equal(instantNanos(moved.end)-instantNanos(moved.start),instantNanos(session.end)-instantNanos(session.start));
  const resized=shiftSession(session,900000000000n,'start');
  assert.equal(resized.start,'2026-10-23T06:18:00.123456789Z');assert.equal(resized.end,session.end);
  const copy=pasteSession(session,instantNanos('2026-10-26T08:00:00Z'),'Europe/Oslo');
  assert.deepEqual(copy,{labelIds:['first','second'],summary:'Day',clientId:'client',projectId:'project',title:'A day',start:'2026-10-26T08:03:00.123456789Z',end:'2026-10-26T16:03:00.987654321Z'});
  assert.equal(sameSession(session,{...session,clientId:'other'}),false);assert.equal(sameSession(session,{...session,projectId:'other'}),false);assert.equal(moved.projectId,session.projectId);assert.equal(resized.projectId,session.projectId);
  assert.equal(sameSession(session,{...session,title:'Peer title'}),false);
  assert.notEqual(copy.labelIds,session.labelIds);assert.deepEqual(moved.labelIds,session.labelIds);
  assert.equal(sameSession(session,{...session,labelIds:['second','first']}),true);
  assert.equal(sameSession(session,{...session,labelIds:[]}),false);
  assert.equal('id' in copy,false);assert.equal('activities' in copy,false);
  assert.equal(shiftSession({...session,end:null},900000000000n).end,null);
  assert.throws(()=>shiftSession(session,24n*3600000000000n,'start'),/End/);
  assert.equal(sameSession(session,{...session,summary:'Peer edit'}),false);
  assert.equal(sameSession(session,{...session,version:2}),false);
  assert.equal(sameSession(session,{...session}),true);
  assert.equal(nanosInstant(-1n),'1969-12-31T23:59:59.999999999Z');
});
test('calendar groups task activities without filling gaps and gives overlaps separate lanes',()=>{
  const activities=[
    {id:'a',taskId:'task',start:'2026-01-01T08:00:00Z',end:'2026-01-01T09:00:00Z'},
    {id:'b',taskId:'task',start:'2026-01-01T09:00:00Z',end:'2026-01-01T10:00:00Z'},
    {id:'c',taskId:'task',start:'2026-01-01T11:00:00Z',end:null},
    {id:'d',taskId:null,start:'2026-01-01T08:00:00Z',end:'2026-01-01T09:00:00Z'},
  ];
  const blocks=calendarTaskBlocks(activities,'2026-01-01T12:00:00Z');
  assert.deepEqual(blocks.map(value=>value.activities.map(activity=>activity.id)),[['a','b'],['c'],['d']]);
  const lanes=calendarLanes([{top:1,bottom:4,id:'a'},{top:2,bottom:3,id:'b'},{top:5,bottom:6,id:'c'}]);
  assert.deepEqual(lanes.map(value=>[value.id,value.lane,value.lanes]),[['a',0,2],['b',1,2],['c',0,1]]);
});

test('drag-created ranges normalize either direction and resolve real instants across DST',()=>{
  const week=calendarWeek('2025-10-20','Europe/Oslo');
  const at=(date,clock)=>({date,row:week.rows.indexOf(clock)});
  const first=at('2025-10-20','09:00/0'),last=at('2025-10-20','10:30/0');
  const expected={start:'2025-10-20T07:00:00Z',end:'2025-10-20T08:30:00Z'};
  assert.deepEqual(calendarRange(week,first,last),expected);
  assert.deepEqual(calendarRange(week,last,first),expected);
  assert.deepEqual(calendarRange(week,first,first),{start:'2025-10-20T07:00:00Z',end:'2025-10-20T07:15:00Z'});
  const overnight=calendarRange(week,at('2025-10-25','23:00/0'),at('2025-10-26','03:00/0'));
  assert.deepEqual(overnight,{start:'2025-10-25T21:00:00Z',end:'2025-10-26T02:00:00Z'});
  const repeated=calendarRange(week,at('2025-10-26','02:15/0'),at('2025-10-26','02:15/1'));
  assert.equal(instantNanos(repeated.end)-instantNanos(repeated.start),3600000000000n);
  assert.deepEqual(calendarRange(week,at('2025-10-20','23:45/0'),{date:'2025-10-20',row:week.rows.length}),
    {start:'2025-10-20T21:45:00Z',end:'2025-10-20T22:00:00Z'});
  assert.throws(()=>calendarRange(week,first,at('2025-10-20','02:15/1')),/does not occur/);
  const spring=calendarWeek('2026-03-23','Europe/Oslo');
  assert.throws(()=>calendarRange(spring,{date:'2026-03-29',row:spring.rows.indexOf('01:00/0')},
    {date:'2026-03-29',row:spring.rows.indexOf('02:30/0')}),/does not occur/);
  const skipped=calendarWeek('2011-12-26','Pacific/Apia');
  assert.throws(()=>calendarRange(skipped,{date:'2011-12-29',row:0},{date:'2011-12-30',row:0}),/date does not exist/);
});

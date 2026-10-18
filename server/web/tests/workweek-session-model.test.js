import {test} from 'node:test';
import assert from 'node:assert/strict';
import {workweekSessionGroups,sessionTimeEdit} from '../src/workweek-session-model.js';
const hour=3600000000000n;
const session=(id,start,end,extra={})=>({id,version:3,ownerId:'a',clientId:'a',projectId:'p',summary:'Notes',labelIds:['label'],start,end,...extra});
const group=sessions=>workweekSessionGroups({sessions,clients:[{id:'a',name:'Client A',color:'#176653'},{id:'b',name:'Client B'}],projects:[{id:'p',name:'Same name'},{id:'q',name:'Same name'}],first:'2025-10-20',zone:'Europe/Oslo',asOf:'2025-10-27T12:00:00Z'});
test('client/project rows combine daily intervals without merging session identities',()=>{
  const result=group([session('one','2025-10-20T06:00:00Z','2025-10-20T08:00:00Z'),session('two','2025-10-20T10:00:00Z','2025-10-20T11:00:00Z'),session('other-project','2025-10-21T06:00:00Z','2025-10-21T07:00:00Z',{projectId:'q'}),session('no-project','2025-10-21T06:00:00Z','2025-10-21T08:00:00Z',{clientId:'b',projectId:null})]);
  assert.equal(result.clients.length,2);assert.equal(result.clients[0].projects.length,2);assert.equal(result.clients[0].total,4n*hour);assert.equal(result.total,6n*hour);
  assert.deepEqual(result.clients[0].projects.find(value=>value.id==='p').days[0].entries.map(value=>value.session.id),['one','two']);
  assert.equal(result.clients[1].projects[0].name,'No project');
});
test('week/DST clipping marks only real endpoints editable and retains exact totals',()=>{
  const result=group([session('dst','2025-10-25T21:30:00.123456789Z','2025-10-27T01:30:00.123456789Z')]);
  const project=result.clients[0].projects[0],saturday=project.days[5].entries[0],sunday=project.days[6].entries[0];
  assert.equal(result.total,25n*hour+hour/2n-123456789n);assert.equal(saturday.start,'23:30:00.123456789');assert.equal(saturday.end,'24:00');
  assert.equal(saturday.startEditable,true);assert.equal(saturday.endEditable,false);assert.equal(sunday.startEditable,false);assert.equal(sunday.endEditable,false);
  assert.equal(sunday.duration,25n*hour);
});
test('running end is editable only on its current local day',()=>{
  const result=workweekSessionGroups({sessions:[session('running','2025-10-25T21:00:00Z',null)],clients:[],projects:[],first:'2025-10-20',zone:'Europe/Oslo',asOf:'2025-10-26T10:00:00Z'});
  const days=result.clients[0].projects[0].days;
  assert.equal(days[5].entries[0].endEditable,false);assert.equal(days[6].entries[0].endEditable,true);assert.equal(days[6].entries[0].end,'');assert.equal(days[6].entries[0].running,true);
});
test('inline changes preserve untouched nanoseconds, labels, project, client and summary',()=>{
  const original=session('one','2025-10-20T06:00:00.123456789Z','2025-10-20T14:00:00.987654321Z');
  const changed=sessionTimeEdit(original,{end:{date:'2025-10-20',text:'17:00',original:'16:00:00.987654321'}},'Europe/Oslo');
  assert.equal(changed.start,original.start);assert.equal(changed.end,'2025-10-20T15:00:00Z');assert.equal(changed.summary,original.summary);assert.deepEqual(changed.labelIds,original.labelIds);assert.equal(changed.projectId,'p');assert.equal(changed.clientId,'a');
  assert.equal(sessionTimeEdit(original,{start:{date:'2025-10-20',text:'08:00',original:'08:00:00.123456789'}},'Europe/Oslo').start,original.start);
});
test('midnight, nonexistent/repeated local times and invalid intervals are handled explicitly',()=>{
  const original=session('one','2025-10-25T21:00:00Z','2025-10-25T21:30:00Z');
  const change=text=>sessionTimeEdit(original,{end:{date:'2025-10-25',text,original:'23:30:00'}},'Europe/Oslo');
  assert.equal(change('24:00').end,'2025-10-25T22:00:00.000Z');assert.throws(()=>change('22:00'),/after start/);assert.throws(()=>change(''),/end time/);
  assert.throws(()=>sessionTimeEdit(original,{end:{date:'2025-10-26',text:'02:30',original:'03:00'}},'Europe/Oslo'),/occurs twice/);
  assert.equal(sessionTimeEdit(original,{end:{date:'2025-10-26',text:'02:30+01:00',original:'03:00'}},'Europe/Oslo').end,'2025-10-26T01:30:00Z');
  assert.throws(()=>sessionTimeEdit(original,{start:{date:'2025-03-30',text:'02:30',original:'01:00'}},'Europe/Oslo'),/does not exist/);
});

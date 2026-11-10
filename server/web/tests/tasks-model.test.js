import {test} from 'node:test';
import assert from 'node:assert/strict';
import {taskRows,taskMatches,taskMomentDate} from '../src/tasks-model.js';
import {calendarTasks} from '../src/tasks-api.js';

const data={profile:{data:{zoneId:'Europe/Oslo'}},projects:[{id:'p',clientId:'c',name:'Mylyn category'}],tasks:[
  {id:'local',summary:'Local task',projectId:null,status:'NOT_STARTED',scheduledDate:'2026-06-01'},
  {id:'imported',summary:'Imported task',projectId:'p',status:'IN_PROGRESS',metadata:{categories:['Review']},externalReferences:[{providerId:'mylyn',repositoryId:'repo',externalId:'42'}],due:{kind:'DATE',date:'2020-01-01'}},
  {id:'finished',summary:'Finished',status:'COMPLETED',urgent:true,due:{kind:'DATE',date:'2020-01-01'}}
]};
test('the task catalog combines local, linked and all dated/undated calendar tasks without changing source records',()=>{
  const input=structuredClone(data),feed=[{id:'remote',summary:'Published task',clientId:'c',status:'NEEDS-ACTION',categories:['Team']},{id:'done',summary:'Completed remote task',status:'COMPLETED',clientId:'c',due:{kind:'DATE',date:'1999-01-01'}}];
  const rows=taskRows(input,feed);assert.equal(rows.length,5);assert.equal(rows.find(row=>row.id==='imported').title,'42: Imported task');assert.deepEqual(rows.find(row=>row.id==='imported').categories,['Review','Mylyn category']);assert.deepEqual(input,data);
  assert.equal(rows.filter(row=>taskMatches(row,{kind:'smart',value:'all'},'2026-06-01')).length,5);
  assert.deepEqual(rows.filter(row=>taskMatches(row,{kind:'smart',value:'today'},'2026-06-01')).map(row=>row.id),['local']);
  assert.deepEqual(rows.filter(row=>taskMatches(row,{kind:'smart',value:'critical'},'2026-06-01')).map(row=>row.id),['imported']);
  assert.deepEqual(rows.filter(row=>taskMatches(row,{kind:'client',value:'c'},'2026-06-01')).map(row=>row.id),['imported','remote','done']);
  assert.equal(rows.filter(row=>taskMatches(row,{kind:'category',value:'Team'},'2026-06-01')).length,1);
});
test('smart categories use the account day and reject invented instants at DST gaps/overlaps or unknown zones',()=>{
  assert.deepEqual(taskMomentDate({kind:'DATE',date:'2026-06-01'},'America/Los_Angeles'),{date:'2026-06-01',unresolved:false});
  assert.equal(taskMomentDate({kind:'DATE_TIME',date:'2026-06-01',time:'00:30',timeZone:'Europe/Oslo'},'America/Los_Angeles').date,'2026-05-31');
  assert.equal(taskMomentDate({kind:'DATE_TIME',date:'2026-06-01',time:'00:30',timeZone:'UTC'},'America/Los_Angeles').date,'2026-05-31');
  for(const [date,time,zone]of [['2026-03-29','02:30','Europe/Oslo'],['2026-10-25','02:30','Europe/Oslo'],['2026-06-01','12:00','Provider/Unknown']])assert.deepEqual(taskMomentDate({kind:'DATE_TIME',date,time,timeZone:zone},'UTC'),{date:null,unresolved:true});
  const urgent=taskRows({...data,tasks:[{id:'urgent',summary:'Urgent',urgent:true}]})[0];assert.equal(taskMatches(urgent,{kind:'smart',value:'critical'},'2026-06-01'),true);
  assert.equal(taskRows({...data,focusClientIds:[],tasks:[],projects:[]},[{id:'outside',clientId:'c'}]).length,0);
});
test('calendar paging restarts on a changed source snapshot and never mixes old and new pages',async()=>{
  const calls=[];let count=0;const session={async request(path,options){calls.push([path,options.etag]);count++;if(count===2)throw Object.assign(new Error(),{code:'STALE_VERSION'});return {data:{items:[{id:count===1?'old':'fresh'}],revision:count===1?1:2,snapshot:count===1?'old':'fresh',nextCursor:count===1?'next':null,zoneId:'UTC',errors:{}}};}};
  assert.deepEqual((await calendarTasks(session,new AbortController().signal)).items,[{id:'fresh'}]);assert.equal(calls[1][1],'old');assert.equal(calls[2][1],null);
});

test('direct clients, emoji titles and completed filtering apply to both task sources',()=>{
  const rows=taskRows({...data,tasks:[{id:'direct',summary:'Review :smiley:',clientId:'client-only',projectId:null,status:'COMPLETED'}]},[{id:'calendar-finished',summary:'Published',status:'COMPLETED'},{id:'calendar-open',summary:'Open',status:'NOT_STARTED'}]);
  assert.equal(rows[0].clientId,'client-only');assert.equal(rows[0].title,'Review 😃');
  assert.equal(taskMatches(rows[0],{kind:'client',value:'client-only'},'2026-11-10'),true);
  assert.deepEqual(rows.filter(row=>taskMatches(row,{kind:'smart',value:'all'},'2026-11-10',{hideCompleted:true})).map(row=>row.id),['calendar-open']);
});

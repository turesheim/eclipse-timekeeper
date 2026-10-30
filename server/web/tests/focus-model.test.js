import {test} from 'node:test';
import assert from 'node:assert/strict';
import {focusedSnapshot,focusIncludes,focusedCurrentSession,focusIncludesActivity} from '../src/focus-model.js';
import {durationNanos} from '../src/model.js';

const fixture=()=>({profile:{data:{zoneId:'Europe/Oslo'}},dates:{first:'2025-10-20'},
  clients:[{id:'a'},{id:'b'}],projects:[{id:'pa',clientId:'a'},{id:'pb',clientId:'b'}],
  tasks:[{id:'ta',projectId:'pa'},{id:'tb',projectId:'pb'},{id:'unassigned'}],
  activities:[{id:'fall',taskId:'ta',start:'2025-10-25T22:00:00.000000001Z',end:null},
    {id:'other',projectId:'pb',start:'2025-10-26T10:00:00Z',end:'2025-10-26T11:00:00Z'},
    {id:'unassigned',start:'2025-10-26T10:00:00Z',end:'2025-10-26T11:00:00Z'}],
  workSessions:[{id:'sa',clientId:'a'},{id:'sb',clientId:'b'}],
  active:{data:{id:'other'}},activeWorkSession:{data:{id:'sb',clientId:'b'}},
  report:{generatedAt:'2025-10-26T23:00:00Z',total:'PT27H',days:[]},revision:7});

test('focus projects sessions, tasks, activities and exact totals without mutating the source or timer authority',()=>{
  const source=fixture(),before=structuredClone(source),s=focusedSnapshot(source,{clientIds:['a']});
  assert.deepEqual(s.clients.map(v=>v.id),['a']);assert.deepEqual(s.projects.map(v=>v.id),['pa']);
  assert.deepEqual(s.tasks.map(v=>v.id),['ta']);assert.deepEqual(s.activities.map(v=>v.id),['fall']);assert.deepEqual(s.workSessions.map(v=>v.id),['sa']);
  assert.equal(durationNanos(s.report.total),25n*3600000000000n-1n);
  assert.equal(s.report.days.reduce((sum,v)=>sum+durationNanos(v.total),0n),durationNanos(s.report.total));
  assert.equal(s.active,source.active);assert.equal(s.activeWorkSession,source.activeWorkSession);assert.deepEqual(source,before);
  assert.equal(focusIncludes(s,'b'),false);assert.equal(focusIncludes(s,'a'),true);
});
test('No focus returns every record; an empty or deleted-client focus returns none, including unassigned work',()=>{
  const source=fixture();assert.equal(focusedSnapshot(source,null),source);assert.ok(focusIncludes(source,null));
  for(const clientIds of [[],['deleted']]){const s=focusedSnapshot(source,{clientIds});assert.equal(s.activities.length,0);assert.equal(s.workSessions.length,0);assert.equal(durationNanos(s.report.total),0n);assert.equal(focusIncludes(s,'a'),false);}
});
test('the timer header follows focus for stopped sessions and retains running timer authority outside focus',()=>{
  assert.equal(focusedCurrentSession(null,null),null);
  const source=fixture(),area={clientIds:['a']};assert.equal(focusedCurrentSession(source,area).id,'sb');
  source.activeWorkSession.data=null;source.workSessions=[
    {id:'first',clientId:'a',start:'2025-10-26T08:00:00Z'},
    {id:'last',clientId:'a',start:'2025-10-26T08:00:00.000000001Z'}];
  source.workSessionReport={session:{id:'reported',clientId:'b'}};
  assert.equal(focusedCurrentSession(source,null).id,'reported');assert.equal(focusedCurrentSession(source,area).id,'last');
  assert.equal(focusedCurrentSession(source,{clientIds:[]}),null);
});
test('task moves follow the same project association as API reports and retain visible activity rows',()=>{
  const source=fixture();source.activities=[
    {id:'moved-in',taskId:'ta',projectId:'pb',start:'2025-10-26T08:00:00Z',end:'2025-10-26T09:00:00Z'},
    {id:'moved-out',taskId:'tb',projectId:'pa',start:'2025-10-26T08:00:00Z',end:'2025-10-26T09:00:00Z'}];
  const s=focusedSnapshot(source,{clientIds:['a']});assert.deepEqual(s.activities.map(value=>value.id),['moved-in']);
  assert.equal(durationNanos(s.report.total),3600000000000n);assert.equal(focusIncludesActivity(s,source.activities[0]),true);assert.equal(focusIncludesActivity(s,source.activities[1]),false);
});

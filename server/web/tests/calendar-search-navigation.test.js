import test from 'node:test';
import assert from 'node:assert/strict';
import {calendarSearchHit} from '../src/calendar-search-navigation.js';

const indexing=()=>Object.assign(new Error('Preparing'),{status:503,code:'NOTES_SEARCH_INDEXING'});

test('calendar navigation waits only for index readiness within one request budget',async context=>{
  const calls=[],reply={data:{calendarEvent:{id:'event'}}};let pending=0,now=0;
  context.mock.method(performance,'now',()=>now);
  const session={async request(path,options){calls.push({path,...options});now+=8;if(calls.length<3)throw indexing();return reply;}};
  assert.equal(await calendarSearchHit(session,'event',()=>true,()=>pending++,{timeout:1000,interval:1}),reply);
  assert.equal(pending,2);assert.equal(calls.length,3);
  assert.ok(calls.every(call=>call.path==='/notes/search/calendar/event'&&call.timeout>0&&call.timeout<=1000));
  assert.ok(calls[2].timeout<calls[0].timeout);
});

test('other failures are returned immediately without retrying',async()=>{
  for(const error of [{status:503,code:'UNAVAILABLE'},{status:401,code:'UNAUTHENTICATED'},{status:404,code:'NOT_FOUND'},{status:0,code:'UNAVAILABLE'}]){
    let calls=0,pending=0;
    await assert.rejects(calendarSearchHit({async request(){calls++;throw error;}},'event',()=>true,()=>pending++),value=>value===error);
    assert.equal(calls,1);assert.equal(pending,0);
  }
});

test('closing or superseding navigation stops further index reads',async()=>{
  let active=true,calls=0;
  assert.equal(await calendarSearchHit({async request(){calls++;throw indexing();}},'event',()=>active,()=>{active=false;},{interval:1}),null);
  assert.equal(calls,1);
  assert.equal(await calendarSearchHit({async request(){assert.fail('Cancelled navigation made a read');}},'event',()=>false),null);
});

test('an index that stays unavailable reaches a finite deadline',async context=>{
  const error=indexing();let calls=0,now=0;
  context.mock.method(performance,'now',()=>now);
  await assert.rejects(calendarSearchHit({async request(){calls++;now+=8;throw error;}},'event',()=>true,()=>{},{timeout:20,interval:1}),value=>value===error);
  assert.equal(calls,3);
});

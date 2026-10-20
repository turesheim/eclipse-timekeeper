import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Api} from '../src/api.js';
const reply = (body, owner = 'b3duZXItYQ', status = 200) => new Response(body === null ? null : JSON.stringify(body), {status, headers: {'X-Timekeeper-Owner': owner, ETag: '"opaque"'}});
test('pasted bearer tokens trim surrounding whitespace and reject malformed credentials before sending', async()=>{
  let requests=0;
  const fetcher=async(url,options)=>{requests++;assert.equal(options.headers.Authorization,'Bearer tkp_fixture-token');return reply({ownerId:'owner-a'});};
  await new Api(' \r\ntkp_fixture-token\t\n',fetcher).request('/me');assert.equal(requests,1);
  for(const token of ['tkp_fixture token','tkp_fixture\r\ntoken','tkp_'+ 'x'.repeat(4096)]) {
    await assert.rejects(new Api(token,fetcher).request('/me'),error=>error.code==='INVALID_TOKEN'&&!error.message.includes(token));
  }
  assert.equal(requests,1);
});
test('private binary downloads retain cookie/owner checks and reject late or foreign files', async()=>{
  const zip=(owner='b3duZXItYQ')=>new Response('synthetic archive',{headers:{'Content-Type':'application/zip','X-Timekeeper-Owner':owner}});
  const api=new Api(null,async(url,options)=>{assert.equal(options.headers.Accept,'application/zip');assert.equal(options.credentials,'same-origin');return zip();},{ownerId:'owner-a'});
  assert.equal(await (await api.request('/data/account/export',{binary:true})).data.text(),'synthetic archive');
  const foreign=new Api(null,async()=>zip('b3duZXItYg'),{ownerId:'owner-a'});await assert.rejects(foreign.request('/data/account/export',{binary:true}),error=>error.code==='OWNER_CHANGED');
  const invalid=new Api(null,async()=>reply({private:'must not be treated as ZIP'}));await assert.rejects(invalid.request('/data/account/export',{binary:true}),error=>error.code==='UNAVAILABLE');
  let finish;const late=new Api(null,()=>new Promise(resolve=>{finish=resolve;}));const pending=late.request('/data/account/export',{binary:true});late.close();finish(zip());await assert.rejects(pending,error=>error.code==='UNAUTHENTICATED');
  const denied=new Api(null,async()=>reply({code:'FORBIDDEN'},'b3duZXItYQ',403));await assert.rejects(denied.request('/data/account/export',{binary:true}),error=>error.code==='FORBIDDEN');
});
test('first empty page binds the session and rejects later owner changes', async () => {
  let index = 0;
  const api = new Api('never-display-token', async (url, options) => {
    assert.equal(options.redirect, 'error'); assert.equal(options.credentials, 'omit');
    if (index++ === 0) { assert.equal(options.headers['X-Timekeeper-Owner'], undefined); return reply({items: [], revision: 0, nextCursor: null}); }
    assert.equal(options.headers['X-Timekeeper-Owner'], 'b3duZXItYQ'); return reply({ownerId:'owner-b'}, 'b3duZXItYg');
  });
  await api.page('/projects'); assert.equal(api.owner, 'owner-a');
  await assert.rejects(api.request('/me'), error => error.code === 'OWNER_CHANGED'); assert.equal(api.owner, 'owner-a');
});
test('unsafe failures are reduced to stable messages and closed clients send nothing', async () => {
  const api = new Api('private-secret', async () => { throw new Error('private-secret foreign-body'); });
  await assert.rejects(api.request('/me'), error => error.code === 'UNAVAILABLE' && !error.message.includes('private-secret'));
  api.close(); await assert.rejects(api.request('/me'), error => error.code === 'UNAUTHENTICATED');
});
test('numeric versions never replace captured opaque conditional headers', async () => {
  const api = new Api('token', async (url, options) => { assert.equal(options.headers['If-Match'], '"captured-etag"'); assert.equal(options.headers['Idempotency-Key'], 'same-command'); return reply({ownerId:'owner-a', version:2}); });
  await api.request('/tasks/id', {method:'PUT', body:{summary:'draft'}, etag:'"captured-etag"', key:'same-command'});
});
test('invalid change progress triggers full resynchronization instead of a hot loop', async () => {
  const api = new Api('token', async () => reply({items:[], nextSequence:4, hasMore:true}));
  await assert.rejects(api.changes(4), error => error.code === 'RESYNC_REQUIRED');
});
test('pagination rejects repeated cursors and mixed-owner resources', async () => {
  const api = new Api('token', async () => reply({items:[], revision:1, nextCursor:'repeat'}));
  await assert.rejects(api.page('/tasks'), error => error.code === 'UNAVAILABLE');
  const foreign = new Api('token', async () => reply({items:[{ownerId:'owner-b'}], revision:1, nextCursor:null}));
  await assert.rejects(foreign.page('/tasks'), error => error.code === 'OWNER_CHANGED');
});
test('cookie requests use same-origin credentials and memory-only CSRF with stable owner binding', async () => {
  const api=new Api(null,async(url,options)=>{
    assert.equal(options.credentials,'same-origin');assert.equal(options.headers.Authorization,undefined);
    assert.equal(options.headers['X-Timekeeper-Owner'],'b3duZXItYQ');
    assert.equal(options.headers['X-CSRF-Token'],options.method==='GET' ? undefined:'csrf-fixture');return reply({ownerId:'owner-a'});
  },{ownerId:'owner-a',csrf:'csrf-fixture'});
  await api.request('/me');await api.request('/clients',{method:'POST',body:{name:'Private',color:'#123456'}});
});
test('a response arriving after logout cannot expose the previous account', async () => {
  let finish;const api=new Api(null,()=>new Promise(resolve=>{finish=resolve;}));
  const pending=api.request('/me');api.close();finish(reply({ownerId:'owner-a',private:'old draft'}));
  await assert.rejects(pending,error=>error.code==='UNAUTHENTICATED');
});
test('explicit bearer-to-cookie exchange accepts a Set-Cookie response without putting credentials in storage',async()=>{
  const api=new Api('existing-token',async(url,options)=>{assert.equal(url,'/api/v1/auth/token-login');assert.equal(options.credentials,'same-origin');assert.equal(options.headers.Authorization,'Bearer existing-token');return reply({ownerId:'owner-a',csrf:'csrf'});});
  await api.request('/auth/token-login',{method:'POST'});
});
test('an untouched UTC profile adopts local time conditionally and only once', async () => {
  let profile={ownerId:'owner-a',zoneId:'UTC',version:0},writes=0;
  const api=new Api('token',async(url,options)=>{
    assert.equal(url,'/api/v1/me');
    if(options.method==='PUT') {
      writes++;assert.equal(options.headers['If-Match'],'"opaque"');
      assert.deepEqual(JSON.parse(options.body),{zoneId:'Europe/Oslo'});
      profile={...profile,zoneId:'Europe/Oslo',version:1};
    }
    return reply(profile);
  });
  assert.equal((await api.initializeTimeZone('Europe/Oslo')).data.zoneId,'Europe/Oslo');
  assert.equal((await api.initializeTimeZone('America/Los_Angeles')).data.zoneId,'Europe/Oslo');
  assert.equal(writes,1);
});
test('explicit UTC and other configured zones are preserved on a different device', async () => {
  for(const profile of [{zoneId:'UTC',version:1},{zoneId:'Europe/Oslo',version:0}]) {
    const api=new Api('token',async(url,options)=>{assert.equal(options.method,'GET');return reply({ownerId:'owner-a',...profile});});
    assert.equal((await api.initializeTimeZone('America/Los_Angeles')).data.zoneId,profile.zoneId);
  }
});
test('a concurrent time zone choice wins over browser initialization', async () => {
  let reads=0,writes=0;
  const api=new Api('token',async(url,options)=>{
    if(options.method==='PUT') {writes++;return reply({code:'STALE_VERSION'},undefined,412);}
    return reply({ownerId:'owner-a',zoneId:reads++ ? 'Asia/Tokyo':'UTC',version:reads===1 ? 0:1});
  });
  assert.equal((await api.initializeTimeZone('Europe/Oslo')).data.zoneId,'Asia/Tokyo');assert.equal(writes,1);
});
test('read-only time zone initialization cannot prevent signing in', async () => {
  const api=new Api('token',async(url,options)=>options.method==='PUT' ? reply({code:'FORBIDDEN'},undefined,403):reply({ownerId:'owner-a',zoneId:'UTC',version:0}));
  assert.equal((await api.initializeTimeZone('Europe/Oslo')).data.zoneId,'UTC');
});
test('a lost time zone write reply is read back without repeating the write', async () => {
  let committed=false,writes=0;
  const api=new Api('token',async(url,options)=>{
    if(options.method==='PUT') {committed=true;writes++;throw new Error('connection lost');}
    return reply({ownerId:'owner-a',zoneId:committed ? 'Europe/Oslo':'UTC',version:committed ? 1:0});
  });
  assert.equal((await api.initializeTimeZone('Europe/Oslo')).data.zoneId,'Europe/Oslo');assert.equal(writes,1);
});

test('work-session Undo metadata is opt-in and validated after owner binding',async()=>{
  const id='5d905b2d-9783-4dc9-9b10-49c81f875c1f';
  const api=new Api('token',async(url,options)=>{
    assert.equal(options.headers['X-Timekeeper-Undo'],'true');const response=reply(null,undefined,204);
    response.headers.set('X-Timekeeper-Undo-Id',id);response.headers.set('X-Timekeeper-Undo-Seconds','600');return response;
  });
  assert.deepEqual((await api.request('/work-sessions/'+id,{method:'DELETE',undo:true})).undo,{id,seconds:600});
  const foreign=new Api('token',async()=>{const response=reply(null,'b3duZXItYg',204);response.headers.set('X-Timekeeper-Undo-Id',id);response.headers.set('X-Timekeeper-Undo-Seconds','600');return response;},{ownerId:'owner-a'});
  await assert.rejects(foreign.request('/work-sessions/'+id,{method:'DELETE',undo:true}),error=>error.code==='OWNER_CHANGED');
});

const snapshotDates=()=>({first:'2026-05-04',from:'2026-05-04T00:00:00Z',to:'2026-05-11T00:00:00Z'});
function snapshotReply(url,reportRevision=1){
  const path=new URL(url,'http://localhost').pathname;
  if(path==='/api/v1/me')return reply({ownerId:'owner-a',zoneId:'UTC',version:1});
  if(path.endsWith('/active'))return reply(null,undefined,204);
  if(path==='/api/v1/reports/activities')return reply({revision:reportRevision,total:'PT0S',days:[],generatedAt:'2026-05-04T12:00:00Z'});
  if(path.endsWith('/report'))return reply({revision:1,session:{id:'session-a'}});
  return reply({items:path==='/api/v1/work-sessions'?[{id:'session-a',start:'2026-05-04T08:00:00Z'}]:[],revision:1,nextCursor:null});
}
test('snapshot reads overlap after owner binding and still confirm revisions and the selected session',async()=>{
  let release;const gate=new Promise(resolve=>{release=resolve;}),paths=new Set();let ready=false;
  const api=new Api('token',async(url,options)=>{
    const path=new URL(url,'http://localhost').pathname;
    if(!ready&&path!=='/api/v1/me'){
      assert.equal(options.headers['X-Timekeeper-Owner'],'b3duZXItYQ');paths.add(path);
      if(paths.size===9){ready=true;release();}
      await new Promise((resolve,reject)=>{gate.then(resolve);options.signal.addEventListener('abort',()=>reject(new Error('aborted')),{once:true});});
    }
    return snapshotReply(url);
  });
  const snapshot=await api.snapshot(snapshotDates,'session-a',{timeout:500});
  assert.equal(paths.size,9);assert.equal(snapshot.revision,1);assert.equal(snapshot.workSessionReport.session.id,'session-a');
});
test('parallel snapshots retry mixed revisions and never publish inconsistent data',async()=>{
  let profiles=0;
  const api=new Api('token',async url=>{if(new URL(url,'http://localhost').pathname==='/api/v1/me')profiles++;return snapshotReply(url,profiles<3?2:1);});
  const snapshot=await api.snapshot(snapshotDates);assert.equal(snapshot.report.revision,1);assert.equal(profiles,4);
});
test('the snapshot deadline aborts outstanding reads together without signing out the session',async()=>{
  const signals=[];let stalled=true;
  const api=new Api('token',async(url,options)=>{
    if(stalled&&new URL(url,'http://localhost').pathname!=='/api/v1/me'){
      signals.push(options.signal);
      await new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new Error('aborted')),{once:true}));
    }
    return snapshotReply(url);
  });
  await assert.rejects(api.snapshot(snapshotDates,null,{timeout:30}),error=>error.code==='UNAVAILABLE');
  assert.equal(signals.length,9);assert.ok(signals.every(signal=>signal.aborted));stalled=false;
  assert.equal((await api.snapshot(snapshotDates)).revision,1);
});

import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Api} from '../src/api.js';
const reply = (body, owner = 'b3duZXItYQ', status = 200) => new Response(body === null ? null : JSON.stringify(body), {status, headers: {'X-Timekeeper-Owner': owner, ETag: '"opaque"'}});
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

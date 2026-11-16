import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DocumentAutosave} from '../src/document-autosave.js';

test('autosave drains edits made during a delayed write before navigation completes',async()=>{
  let draft='First',saved='',release;const writes=[];
  const gate=new Promise(resolve=>release=resolve),autosave=new DocumentAutosave({dirty:()=>draft!==saved,ready:()=>true,save:async()=>{const value=draft;writes.push(value);if(writes.length===1)await gate;saved=value;return true;}});
  const leave=autosave.flush();await Promise.resolve();draft='Latest edit';autosave.changed();const concurrent=autosave.flush();assert.equal(concurrent,leave);release();
  assert.equal(await leave,true);assert.equal(saved,'Latest edit');assert.deepEqual(writes,['First','Latest edit']);
});
test('continuous typing still saves at the periodic deadline and unchanged documents issue no writes',async context=>{
  context.mock.timers.enable({apis:['setTimeout']});let draft='',saved='',writes=0;
  const autosave=new DocumentAutosave({dirty:()=>draft!==saved,ready:()=>true,save:async()=>{saved=draft;writes++;return true;}});
  for(let i=0;i<10;i++){draft+='x';autosave.changed();context.mock.timers.tick(500);}
  await Promise.resolve();assert.equal(writes,1);assert.equal(saved,draft);await autosave.flush();assert.equal(writes,1);autosave.reset();
});
test('failed and stale saves retain the draft and prevent navigation without repeated writes',async()=>{
  let draft='Private draft',ready=true,writes=0;
  const autosave=new DocumentAutosave({dirty:()=>!!draft,ready:()=>ready,save:async()=>{writes++;ready=false;return false;}});
  assert.equal(await autosave.flush(),false);assert.equal(await autosave.flush(),false);assert.equal(draft,'Private draft');assert.equal(writes,1);autosave.reset();
});
test('reset fences an old save while a new account can save its own document',async()=>{
  let generation=0,dirty=true,release;const gate=new Promise(resolve=>release=resolve);
  const autosave=new DocumentAutosave({dirty:()=>dirty,ready:()=>true,save:async()=>{const owner=generation;if(!owner)await gate;if(owner!==generation)return false;dirty=false;return true;}});
  const previous=autosave.flush();await Promise.resolve();autosave.reset();generation=1;assert.equal(await autosave.flush(),true);release();assert.equal(await previous,false);assert.equal(dirty,false);
});

test('metadata updates preserve typing and drain it with the new validator before navigation',async()=>{
  let draft='First',saved='',version=0,release,entered;const writes=[];
  const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>entered=resolve);
  const autosave=new DocumentAutosave({dirty:()=>draft!==saved,ready:()=>true,save:async()=>{writes.push({text:draft,version});saved=draft;++version;return true;}});
  const metadata=autosave.exclusive(async()=>{assert.equal(saved,'First');entered();await gate;++version;return true;});
  await started;draft='Typed during metadata';autosave.changed();const navigation=autosave.flush();assert.equal(navigation,metadata);release();
  assert.equal(await navigation,true);assert.equal(saved,draft);assert.deepEqual(writes,[{text:'First',version:0},{text:'Typed during metadata',version:2}]);
});

test('a metadata operation waits for an in-flight prose save and its newer draft',async()=>{
  let draft='First',saved='',release,entered;const seen=[];
  const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>entered=resolve);
  const autosave=new DocumentAutosave({dirty:()=>draft!==saved,ready:()=>true,save:async()=>{const text=draft;if(!saved){entered();await gate;}saved=text;return true;}});
  const prose=autosave.flush();await started;
  const metadata=autosave.exclusive(async()=>{seen.push(saved);return true;});draft='Latest';release();
  assert.equal(await prose,true);assert.equal(await metadata,true);assert.deepEqual(seen,['Latest']);
});

test('an uncertain metadata operation retains the new draft and stops navigation and writes',async()=>{
  let draft='',ready=true,release,entered,writes=0;
  const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>entered=resolve);
  const autosave=new DocumentAutosave({dirty:()=>!!draft,ready:()=>ready,save:async()=>{writes++;return true;}});
  const metadata=autosave.exclusive(async()=>{entered();await gate;ready=false;return false;});await started;
  draft='Keep this draft';autosave.changed();const navigation=autosave.flush();release();
  assert.equal(await metadata,false);assert.equal(await navigation,false);assert.equal(await autosave.flush(),false);assert.equal(writes,0);assert.equal(draft,'Keep this draft');autosave.reset();
});

test('reset fences pending metadata while the next account can save independently',async()=>{
  let draft='',saved='',release,entered;const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>entered=resolve);
  const autosave=new DocumentAutosave({dirty:()=>draft!==saved,ready:()=>true,save:async()=>{saved=draft;return true;}});
  const old=autosave.exclusive(async()=>{entered();await gate;return true;});await started;
  autosave.reset();draft='New account draft';assert.equal(await autosave.flush(),true);release();
  assert.equal(await old,false);assert.equal(saved,'New account draft');
});

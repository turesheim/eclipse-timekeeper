import test from 'node:test';
import assert from 'node:assert/strict';
import {vaultEditorResources} from '../src/vault-editor-resources.js';

test('task source reading tolerates metadata invalidation during the collection lookup',async()=>{
  let release,arrive,reads=0;const gate=new Promise(resolve=>{release=resolve;}),started=new Promise(resolve=>{arrive=resolve;});
  const entry={id:'entry',kind:'DAY',sourceType:'DAY_NOTE',sourceKey:'2026-11-02'},session={request:async path=>{
    if(path==='/notes'){if(++reads===1){arrive();await gate;}return {data:{settings:{journalFolder:'journal'}}};}
    if(path.startsWith('/notes/resolve?'))return {data:entry};
    assert.equal(path,'/notes/entries/entry/content');return {data:new Blob(['\ufeff- [ ] Exact\r\n']),etag:'"content-current"'};
  }};
  const resources=vaultEditorResources(()=>session,()=>({sourceType:'DAY_NOTE',sourceKey:entry.sourceKey}),undefined,{canCreate:()=>true}),pending=resources.readTaskSource();await started;resources.invalidate();release();const result=await pending;
  assert.equal(reads,2);assert.equal(result.text,'\ufeff- [ ] Exact\r\n');assert.equal(result.etag,'"content-current"');assert.equal(result.session,session);
});

test('task source retries cannot cross an account boundary',async()=>{
  let release,arrive,reads=0;const gate=new Promise(resolve=>{release=resolve;}),started=new Promise(resolve=>{arrive=resolve;});
  let current={request:async path=>{reads++;assert.equal(path,'/notes');arrive();await gate;return {data:{settings:{journalFolder:'journal'}}};}};
  const resources=vaultEditorResources(()=>current,()=>({sourceType:'DAY_NOTE',sourceKey:'2026-11-02'}),undefined,{canCreate:()=>true}),pending=resources.readTaskSource();await started;current={request:()=>{throw new Error('Must not read the replacement account');}};release();await assert.rejects(pending);assert.equal(reads,1);
});

test('a read-only task source action never flushes or reads canonical content',async()=>{
  const resources=vaultEditorResources(()=>({request:()=>{throw new Error('Must not read');}}),()=>'',undefined,{canCreate:()=>false,beforeCreate:()=>{throw new Error('Must not flush');}});await assert.rejects(resources.readTaskSource());
});

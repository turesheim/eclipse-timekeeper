import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {fileSha256,HASH_CHUNK_BYTES} from '../src/file-sha256.js';
test('incremental browser hashing matches SHA-256 across block and file-chunk boundaries',async()=>{
  for(const size of [0,1,55,56,63,64,65,HASH_CHUNK_BYTES-1,HASH_CHUNK_BYTES,HASH_CHUNK_BYTES+1,3*HASH_CHUNK_BYTES+19]){
    const bytes=Uint8Array.from({length:size},(_,index)=>(index*17+3)%256),slices=[],progress=[];
    const file={size,slice(start,end){assert.ok(end-start<=HASH_CHUNK_BYTES);slices.push([start,end]);return new Blob([bytes.subarray(start,end)]);},arrayBuffer(){assert.fail('Must never read the entire file');}};
    assert.equal(await fileSha256(file,{onProgress:value=>progress.push(value)}),createHash('sha256').update(bytes).digest('hex'));
    assert.equal(slices.length,Math.ceil(size/HASH_CHUNK_BYTES));assert.deepEqual(progress,slices.map(([,end])=>end));
  }
});
test('cancellation prevents reading another chunk or returning a digest',async()=>{
  const abort=new AbortController();abort.abort();let reads=0;
  const file={size:HASH_CHUNK_BYTES*3,slice(){reads++;return new Blob([new Uint8Array(HASH_CHUNK_BYTES)]);}};
  await assert.rejects(fileSha256(file,{signal:abort.signal}),{name:'AbortError'});assert.equal(reads,0);
  const active=new AbortController();await assert.rejects(fileSha256(file,{signal:active.signal,onProgress:()=>active.abort()}),{name:'AbortError'});assert.equal(reads,1);
});
test('invalid file sizes are rejected without reading',async()=>{
  for(const size of [-1,NaN,Infinity,1.5,Number.MAX_SAFE_INTEGER+1])await assert.rejects(fileSha256({size,slice(){assert.fail();}}),TypeError);
});

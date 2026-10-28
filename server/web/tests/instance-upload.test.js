import {test} from 'node:test';
import assert from 'node:assert/strict';
import {uploadCiphertext} from '../src/instance-upload.js';
test('resume sends only remaining exact ciphertext ranges and confirms each durable acknowledgement',async()=>{
  const file=new Blob([new Uint8Array([1,2,3,4,5,6,7,8])]),calls=[],progress=[];let received=3;
  const state=()=>({bytes:8,sha256:'fixture',received,chunkBytes:3,operation:{state:'UPLOADING'}});
  const api={async request(path,options={}){calls.push(path);if(options.rawBody){assert.deepEqual([...options.rawBody],[...new Uint8Array(await file.slice(received,received+3).arrayBuffer())]);received+=options.rawBody.length;}return {data:state()};}};
  await uploadCiphertext(api,'id',file,'fixture',{onProgress:value=>progress.push(value)});
  assert.deepEqual(calls,['/admin/instance/uploads/id','/admin/instance/uploads/id/content?offset=3','/admin/instance/uploads/id/content?offset=6']);assert.deepEqual(progress,[3,6,8]);
});
test('foreign files, invalid server offsets and oversized chunk limits never send ciphertext',async()=>{
  for(const extra of [{bytes:9},{sha256:'different'},{received:-1},{received:9},{received:1.5},{chunkBytes:1048577},{chunkBytes:0}]){
    let calls=0;const api={async request(){calls++;return {data:{bytes:8,sha256:'fixture',received:0,chunkBytes:3,operation:{state:'UPLOADING'},...extra}};}};
    await assert.rejects(uploadCiphertext(api,'id',new Blob([new Uint8Array(8)]),'fixture'));assert.equal(calls,1);
  }
});
test('lost acknowledgement stops without retry; reselecting the file resumes from server progress',async()=>{
  let received=0,writes=0;const file=new Blob([new Uint8Array(6)]);
  const api={async request(path,options={}){if(options.rawBody){received+=options.rawBody.length;if(++writes===1)throw new Error('lost response');}return {data:{bytes:6,sha256:'fixture',received,chunkBytes:3,operation:{state:'UPLOADING'}}};}};
  await assert.rejects(uploadCiphertext(api,'id',file,'fixture'));assert.equal(writes,1);
  assert.equal((await uploadCiphertext(api,'id',file,'fixture')).received,6);assert.equal(writes,2);
});

import {test} from 'node:test';
import assert from 'node:assert/strict';
import {browserOptions,credentialJSON,decodeBinary,encodeBinary,PasskeyCeremony,passkeysSupported,canceledPasskey} from '../src/webauthn.js';

const deferred=()=>{let resolve;const promise=new Promise(done=>{resolve=done;});return {promise,resolve};};
const environment=get=>({isSecureContext:true,PublicKeyCredential:class {},navigator:{credentials:{get,create:get}}});
const credential=()=>({toJSON:()=>({id:'opaque',type:'public-key',response:{signature:'signed'}})});
test('standard binary fields round-trip without changing JSON options or losing bytes',()=>{
  const bytes=Uint8Array.from([0,1,127,128,254,255]);assert.deepEqual(decodeBinary(encodeBinary(bytes)),bytes);
  const json={challenge:encodeBinary(bytes),user:{id:'AQI',name:'unchanged'},excludeCredentials:[{type:'public-key',id:'AwQ'}]};
  const options=browserOptions(json,true,{});assert.deepEqual(options.challenge,bytes);assert.deepEqual([...options.user.id],[1,2]);assert.deepEqual([...options.excludeCredentials[0].id],[3,4]);assert.equal(json.user.id,'AQI');
  const fallback=credentialJSON({id:'id',rawId:bytes.buffer,type:'public-key',response:{clientDataJSON:bytes.buffer,authenticatorData:bytes.buffer,signature:bytes.buffer,userHandle:null},getClientExtensionResults:()=>({})});
  assert.equal(fallback.response.userHandle,null);assert.equal(fallback.response.signature,encodeBinary(bytes));
});
test('late options after cancellation cannot prompt or cancel a newer ceremony',async()=>{
  const first=deferred(),second=deferred(),canceled=[],finished=[];let prompts=0,requests=0;
  const flow=new PasskeyCeremony(async(path,body)=>path==='options' ? (++requests===1 ? first.promise:second.promise) : (finished.push(body.ceremony),{owner:'new'}),
    {environment:environment(async()=>{prompts++;return credential();}),cancel:async nonce=>{canceled.push(nonce);}});
  const old=flow.run('options','finish',{});const rejected=assert.rejects(old,error=>canceledPasskey(error));
  const current=flow.run('options','finish',{});
  first.resolve({ceremony:'old',publicKey:{challenge:'AQI'}});await rejected;
  second.resolve({ceremony:'new',publicKey:{challenge:'AQI'}});assert.deepEqual(await current,{owner:'new'});
  assert.equal(prompts,1);assert.deepEqual(finished,['new']);assert.deepEqual(canceled,['old','new']);
});
test('canceling a native prompt aborts it and never sends a finish request',async()=>{
  const ready=deferred(),canceled=[];let finishes=0;
  const flow=new PasskeyCeremony(async path=>path==='options' ? {ceremony:'prompt',publicKey:{challenge:'AQI'}} : (++finishes,{}),
    {environment:environment(({signal})=>new Promise((resolve,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('Canceled','AbortError')));ready.resolve();})),cancel:async nonce=>{canceled.push(nonce);}});
  const pending=flow.run('options','finish',{});const rejected=assert.rejects(pending,error=>canceledPasskey(error));await ready.promise;flow.cancel();await rejected;
  assert.equal(finishes,0);assert.deepEqual(canceled,['prompt']);
});
test('a stale account view cannot commit a native result and insecure contexts cannot prompt',async()=>{
  let valid=true,finishes=0;const native=deferred();
  const flow=new PasskeyCeremony(async path=>path==='options' ? {ceremony:'stale',publicKey:{challenge:'AQI'}} : (++finishes,{}),
    {environment:environment(()=>native.promise),cancel:async()=>{}});
  const pending=flow.run('options','finish',{}, {valid:()=>valid});const rejected=assert.rejects(pending,error=>canceledPasskey(error));
  await new Promise(resolve=>setImmediate(resolve));valid=false;native.resolve(credential());await rejected;assert.equal(finishes,0);
  assert.equal(passkeysSupported({...environment(()=>{}),isSecureContext:false}),false);
});

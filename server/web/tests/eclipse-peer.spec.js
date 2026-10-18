import {test,expect} from '@playwright/test';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {once} from 'node:events';
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {createHash,randomBytes,randomUUID} from 'node:crypto';

test('installed Eclipse browser peer creates, reports and cleans up time through Activities',async({request,browserName},testInfo)=>{
  test.skip(browserName!=='chromium'||new URL(process.env.TIMEKEEPER_WEB_URL).protocol!=='http:','The installed Eclipse peer uses Chromium and its HTTP fixture.');
  const registryPath=process.env.TIMEKEEPER_WEB_REGISTRY,original=await readFile(registryPath,'utf8');
  const token=randomBytes(32).toString('base64url'),prefix='Peer '+randomUUID(),ownerId='peer-'+randomUUID();
  const registry=JSON.parse(original);registry.push({ownerId,sha256:createHash('sha256').update(token).digest('hex'),permissions:['read','write']});
  let peer,lines,stderr='';
  async function api(method,path,data,headers={}) {
    const response=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
    expect(response.ok(),`${method} ${path}: ${response.status()}`).toBe(true);return response;
  }
  async function command(input) {
    peer.stdin.write(JSON.stringify(input)+'\n');const reply=await lines.next();
    expect(reply.done,stderr).toBe(false);expect(JSON.parse(reply.value),stderr).toEqual({ok:true});
  }
  try {
    await writeFile(registryPath,JSON.stringify(registry),{mode:0o600});
    await api('POST','/clients',{name:prefix+' client',color:'#176653'});
    peer=spawn(process.execPath,[fileURLToPath(new URL('./eclipse-peer.js',import.meta.url))],{env:{...process.env,TIMEKEEPER_WEB_TOKEN:token,
      TIMEKEEPER_WEB_FAILURE_IMAGE:testInfo.outputPath('peer-failure.png'),TIMEKEEPER_WEB_FAILURE_TEXT:testInfo.outputPath('peer-failure.txt'),TIMEKEEPER_WEB_FAILURE_RECORDS:testInfo.outputPath('peer-failure-records.json')},stdio:['pipe','pipe','pipe']});
    peer.stderr.setEncoding('utf8');peer.stderr.on('data',value=>{stderr+=value;});
    lines=createInterface({input:peer.stdout})[Symbol.asyncIterator]();
    await command({action:'create',prefix,zone:'Europe/Oslo',date:'2025-10-20',start:'2025-10-20T06:00:00.123456789Z',end:'2025-10-20T06:00:00.123456790Z'});
    const snapshot=await(await api('GET','/data/export')).json();
    expect(snapshot.activities).toHaveLength(1);expect(snapshot.workSessions).toHaveLength(0);
    expect(snapshot.activities[0]).toMatchObject({summary:prefix+' precise interval',start:'2025-10-20T06:00:00.123456789Z',end:'2025-10-20T06:00:00.123456790Z',taskId:snapshot.tasks[0].id,labelIds:[snapshot.labels.find(label=>label.name===prefix+' label').id]});
    const days=Array.from({length:7},(_,index)=>({date:`2025-10-${20+index}`,total:index ? 'PT0S':'PT0.000000001S'}));
    await command({action:'report',zone:'Europe/Oslo',total:'PT0.000000001S',days:JSON.stringify(days)});
    await command({action:'cleanup',zone:'UTC'});
    const cleaned=await(await api('GET','/data/export')).json();
    for(const collection of ['activities','tasks','projects'])expect(cleaned[collection]).toEqual([]);
    expect(cleaned.labels.some(label=>label.name===prefix+' label')).toBe(false);expect(cleaned.zoneId).toBe('UTC');
    peer.stdin.end('{"action":"quit"}\n');const [code]=await once(peer,'exit');expect(code,stderr).toBe(0);
  } finally {
    if(peer&&peer.exitCode===null)peer.kill();
    await lines?.return();await writeFile(registryPath,original,{mode:0o600});
  }
});

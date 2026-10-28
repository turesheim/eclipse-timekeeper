// Test driver for the actual desktop renderer; synchronization stays upstream.
import {readFileSync} from 'node:fs';
let socket;
try {
  const input=JSON.parse(readFileSync(0,'utf8'));
  let target;
  for(let attempt=0;attempt<120;attempt++){
    try{const targets=await(await fetch('http://127.0.0.1:9222/json/list')).json();target=targets.find(value=>value.type==='page'&&value.url.startsWith('app://obsidian.md/'));if(target)break;}catch{}
    await new Promise(resolve=>setTimeout(resolve,500));
  }
  if(!target)throw new Error();
  socket=new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
  let next=0;const pending=new Map();
  socket.onmessage=event=>{const value=JSON.parse(event.data);if(value.id)pending.get(value.id)?.(value);};
  const evaluate=expression=>new Promise((resolve,reject)=>{
    const id=++next;
    const timer=setTimeout(()=>{pending.delete(id);reject(new Error());},input.timeout??90000);
    pending.set(id,value=>{clearTimeout(timer);pending.delete(id);if(value.error||value.result?.exceptionDetails)reject(new Error());else resolve(value.result?.result?.value??null);});
    socket.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));
  });
  let ready=false;
  for(let attempt=0;attempt<120;attempt++){
    ready=await evaluate('typeof app!=="undefined" && !!app.vault && !!app.plugins');if(ready)break;
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  if(!ready)throw new Error();
  await evaluate('(async()=>{await new Promise(resolve=>app.workspace.onLayoutReady(resolve));return true;})()');
  const value=await evaluate(input.expression);socket.close();
  process.stdout.write(JSON.stringify(value)+'\n');
}catch{
  // Neither expressions nor exception diagnostics may expose fixture credentials.
  socket?.close();
  process.stderr.write('Disposable desktop client operation failed\n');process.exitCode=1;
  setTimeout(()=>process.exit(1),1000).unref();
}

/** One short-lived worker at a time: completion/cancellation releases all source. */
const queue=[];let active=null;
const failure=()=>new Error('PlantUML preview unavailable');
function next(){
  if(active||!queue.length)return;const job=active=queue.shift();let worker,timer;
  const finish=(error,svg)=>{if(active!==job)return;clearTimeout(timer);worker?.terminate();job.signal?.removeEventListener('abort',job.abort);active=null;job.source='';error?job.reject(error):job.resolve(svg);queueMicrotask(next);};
  job.abort=()=>finish(new DOMException('Canceled','AbortError'));job.signal?.addEventListener('abort',job.abort,{once:true});
  if(job.signal?.aborted){job.abort();return;}
  try{
    worker=new Worker(new URL('./assets/plantuml/worker.js',import.meta.url),{type:'module'});timer=setTimeout(()=>finish(failure()),15000);
    worker.onerror=()=>finish(failure());worker.onmessage=event=>{
      if(event.data?.ready){clearTimeout(timer);timer=setTimeout(()=>finish(failure()),5000);worker.postMessage({source:job.source,dark:job.dark});return;}
      typeof event.data?.svg==='string'&&event.data.svg.length<=2_000_000?finish(null,event.data.svg):finish(failure());
    };
  }catch{finish(failure());}
}
export function renderPlantUml(source,{dark=false,signal}={}){
  if(typeof source!=='string'||new TextEncoder().encode(source).length>10000||queue.length>=32)return Promise.reject(failure());
  return new Promise((resolve,reject)=>{
    const job={source,dark,signal,resolve,reject,abort:null};
    const abort=()=>{const index=queue.indexOf(job);if(index<0)return;queue.splice(index,1);job.source='';signal?.removeEventListener('abort',abort);reject(new DOMException('Canceled','AbortError'));};
    job.abort=abort;if(signal?.aborted){reject(new DOMException('Canceled','AbortError'));return;}signal?.addEventListener('abort',abort,{once:true});
    job.resolve=svg=>{signal?.removeEventListener('abort',abort);resolve(svg);};job.reject=error=>{signal?.removeEventListener('abort',abort);reject(error);};queue.push(job);next();
  });
}

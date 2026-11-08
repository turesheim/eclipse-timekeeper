import {ApiError} from './api.js';

/** A source refresh can advance independently of the native task revision. */
export async function calendarTasks(session,signal){
  for(let attempt=0;attempt<3;attempt++){
    let cursor=null,snapshot=null,revision=null;const items=[],seen=new Set();
    try{
      do{
        const reply=await session.request('/calendar-tasks?'+new URLSearchParams({limit:200,...(cursor?{cursor}:{})}),{etag:snapshot,signal});
        const page=reply.data;
        if(!Array.isArray(page.items)||!Number.isSafeInteger(page.revision)||typeof page.snapshot!=='string'||snapshot&&snapshot!==page.snapshot||revision!==null&&revision!==page.revision)throw new ApiError('BUSY');
        snapshot=page.snapshot;revision=page.revision;items.push(...page.items);cursor=page.nextCursor;
        if(cursor!==null&&(typeof cursor!=='string'||!cursor||seen.has(cursor)))throw new ApiError('UNAVAILABLE');seen.add(cursor);
        if(cursor===null)return {items,errors:page.errors||{},zoneId:page.zoneId};
      }while(true);
    }catch(error){if(signal.aborted||attempt===2||!['BUSY','CURSOR_EXPIRED','STALE_VERSION'].includes(error.code))throw error;}
  }
}

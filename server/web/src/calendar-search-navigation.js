import {ApiError} from './api.js';

/** Wait for a saved note's derived descriptor without retrying operational failures. */
export async function calendarSearchHit(session,id,current,pending,{timeout=15000,interval=200}={}){
  const deadline=performance.now()+timeout;let indexing=new ApiError('NOTES_SEARCH_INDEXING',503);
  while(await current()){
    const remaining=deadline-performance.now();if(remaining<=0)throw indexing;
    try{return await session.request('/notes/search/calendar/'+id,{timeout:remaining});}
    catch(error){
      if(error.status!==503||error.code!=='NOTES_SEARCH_INDEXING')throw error;
      indexing=error;if(!await current())return null;
      pending?.();const delay=Math.min(interval,deadline-performance.now());if(delay<=0)throw error;
      await new Promise(resolve=>setTimeout(resolve,delay));
    }
  }
  return null;
}

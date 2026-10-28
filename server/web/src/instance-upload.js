import {ApiError} from './api.js';
/** Resume only the selected ciphertext, from the server's durable offset. */
export async function uploadCiphertext(api,id,file,sha256,{signal,onProgress=()=>{}}={}) {
  const path='/admin/instance/uploads/'+id;
  let {data:state}=await api.request(path,{signal});
  if(state.bytes!==file.size||state.sha256!==sha256||!Number.isSafeInteger(state.received)||state.received<0||state.received>file.size||!Number.isSafeInteger(state.chunkBytes)||state.chunkBytes<1||state.chunkBytes>1048576)throw new ApiError('INSTANCE_UPLOAD_FILE_MISMATCH',422);
  if(state.operation.state!=='UPLOADING')return state;
  onProgress(state.received);
  while(state.received<file.size){
    if(signal?.aborted)throw new DOMException('Cancelled','AbortError');
    const offset=state.received,end=Math.min(file.size,offset+state.chunkBytes);
    const rawBody=new Uint8Array(await file.slice(offset,end).arrayBuffer());
    const {data:next}=await api.request(path+'/content?offset='+offset,{method:'PUT',rawBody,signal,timeout:60000});
    if(next.received!==end||next.bytes!==file.size||next.sha256!==sha256)throw new ApiError('INSTANCE_UPLOAD_PROGRESS_INVALID',409);
    state=next;onProgress(state.received);
  }
  return state;
}

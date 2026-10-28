import {sha256} from './assets/noble-hashes/sha256.js';
export const HASH_CHUNK_BYTES=1024*1024;
/** Hash a File without materializing the archive in browser memory. */
export async function fileSha256(file,{signal,onProgress=()=>{}}={}) {
  if(!Number.isSafeInteger(file?.size)||file.size<0||typeof file.slice!=='function')throw new TypeError('Invalid file');
  const hash=sha256.create();
  const cancelled=()=>{if(signal?.aborted)throw new DOMException('Cancelled','AbortError');};
  try {
    cancelled();
    for(let offset=0;offset<file.size;offset+=HASH_CHUNK_BYTES){
      cancelled();const bytes=new Uint8Array(await file.slice(offset,Math.min(file.size,offset+HASH_CHUNK_BYTES)).arrayBuffer());
      cancelled();hash.update(bytes);onProgress(Math.min(file.size,offset+bytes.length));
    }
    cancelled();return Array.from(hash.digest(),byte=>byte.toString(16).padStart(2,'0')).join('');
  } finally {hash.destroy();}
}

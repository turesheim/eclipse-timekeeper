import {fileSha256} from './file-sha256.js';
// Workers receive a File only; account credentials never cross this boundary.
self.onmessage=async({data})=>{
  try {const sha256=await fileSha256(data.file,{onProgress:received=>self.postMessage({received})});self.postMessage({sha256});}
  catch {self.postMessage({failed:true});}
};

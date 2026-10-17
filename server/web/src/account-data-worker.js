self.onmessage=async event=>{
  try {
    const file=event.data.file;
    if(!file || file.size<1 || file.size>256*1024*1024) throw new Error();
    const digest=await crypto.subtle.digest('SHA-256',await file.arrayBuffer());
    self.postMessage({sha256:[...new Uint8Array(digest)].map(value=>value.toString(16).padStart(2,'0')).join('')});
  }catch {self.postMessage({error:'The archive could not be read. Choose a Timekeeper ZIP or archive.json up to 256 MiB.'});}
};

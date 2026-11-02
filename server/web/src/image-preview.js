import {relativeNotePath,rasterType} from './notes-links.js';

/** Authenticated raster bytes only; never expose credentials in an image URL. */
export function imagePreview({alt,load,active=()=>true,onError}){
  const dom=document.createElement('img');dom.alt=alt;dom.loading='lazy';let disposed=false,url;
  void (async()=>{try{
    const blob=await load();if(disposed||!active())return;
    const type=rasterType(new Uint8Array(await blob.slice(0,12).arrayBuffer()));
    if(!type)throw new Error('Unsupported image');
    if(disposed||!active())return;
    url=URL.createObjectURL(blob.slice(0,blob.size,type));dom.src=url;
  }catch(error){if(!disposed&&active())onError?.(error,dom);}})();
  return {dom,ignoreMutation:()=>true,destroy(){disposed=true;if(url)URL.revokeObjectURL(url);dom.removeAttribute('src');}};
}
export const journalAssetPath=reference=>typeof reference==='string'&&reference.startsWith('assets/')&&!!relativeNotePath('note.md',reference)?.startsWith('assets/');
export function journalImageResources(session){
  return {allowsAsset:journalAssetPath,allowsLink:()=>false,image:(reference,alt)=>{
    const current=session();
    return imagePreview({alt,active:()=>!!current&&current===session(),load:async()=>{
      if(!current||!journalAssetPath(reference))throw new Error('Invalid image');
      // Canonical uploads and synced binaries share the vault content endpoint,
      // whose octet-stream response retains the API client's strict media checks.
      const info=(await current.request('/notes')).data;
      const path=relativeNotePath(info.settings.assetsFolder+'/note.md',reference.slice(7));
      const entry=(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;
      return (await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'})).data;
    }});
  }};
}

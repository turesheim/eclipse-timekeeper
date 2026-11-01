import {rasterType} from './notes-links.js';

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
export const journalAssetPath=reference=>typeof reference==='string'&&/^assets\/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(reference);
export function journalImageResources(session){
  return {allowsAsset:journalAssetPath,allowsLink:()=>false,image:(reference,alt)=>{
    const current=session();
    return imagePreview({alt,active:()=>!!current&&current===session(),load:async()=>{
      if(!current||!journalAssetPath(reference))throw new Error('Invalid image');
      return (await current.request('/journal-assets/'+reference.slice(7),{binary:'application/octet-stream'})).data;
    }});
  }};
}

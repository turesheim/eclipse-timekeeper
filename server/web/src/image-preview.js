import {relativeNotePath,rasterType} from './notes-links.js';

const previews=new Set(),versions=new WeakMap();let epoch=0;
/** Update references to the same owned attachment without changing Markdown. */
export function updateImagePreviews(session,id,blob){
  let owned=versions.get(session);if(!owned){owned=new Map();versions.set(session,owned);}owned.set(id,++epoch);
  for(const preview of previews)if(preview.identity?.session===session&&preview.identity.id===id)void preview.present(blob);
}

/** Authenticated raster bytes only; never expose credentials in an image URL. */
export function imagePreview({alt,load,active=()=>true,onError}){
  const dom=document.createElement('img');dom.alt=alt;dom.loading='lazy';let disposed=false,url,generation=0;
  const preview={identity:null,present:blob=>present(blob,++generation)};previews.add(preview);
  const present=async(blob,cycle)=>{try{
    const type=rasterType(new Uint8Array(await blob.slice(0,12).arrayBuffer()));
    if(!type)throw new Error('Unsupported image');
    if(disposed||!active()||cycle!==generation)return;
    const previous=url;url=URL.createObjectURL(blob.slice(0,blob.size,type));dom.src=url;dom.alt=alt;dom.removeAttribute('title');if(previous)URL.revokeObjectURL(previous);
  }catch(error){if(!disposed&&active()&&cycle===generation)onError?.(error,dom);}};
  const read=async()=>{const cycle=++generation,started=epoch;try{
    const loaded=await load();if(disposed||!active()||cycle!==generation)return;
    const blob=loaded.blob||loaded;preview.identity=loaded.session&&loaded.id?{session:loaded.session,id:loaded.id}:null;
    // A slow initial GET may have captured the previous image before replacement.
    if(preview.identity&&(versions.get(loaded.session)?.get(loaded.id)||0)>started)return read();
    await present(blob,cycle);
  }catch(error){if(!disposed&&active()&&cycle===generation)onError?.(error,dom);}};void read();
  return {dom,ignoreMutation:()=>true,destroy(){disposed=true;previews.delete(preview);++generation;if(url)URL.revokeObjectURL(url);dom.removeAttribute('src');}};
}
export const journalAssetPath=reference=>typeof reference==='string'&&reference.startsWith('assets/')&&!!relativeNotePath('note.md',reference)?.startsWith('assets/');
export function journalImageResources(session){
  return {canonicalAsset:async reference=>{if(!journalAssetPath(reference))throw new Error('Invalid image');const info=(await session().request('/notes')).data;return info.settings.assetsFolder+'/'+reference.slice(7);},allowsAsset:journalAssetPath,allowsLink:()=>false,image:(reference,alt)=>{
    const current=session();
    return imagePreview({alt,active:()=>!!current&&current===session(),load:async()=>{
      if(!current||!journalAssetPath(reference))throw new Error('Invalid image');
      // Canonical uploads and synced binaries share the vault content endpoint,
      // whose octet-stream response retains the API client's strict media checks.
      const info=(await current.request('/notes')).data;
      const path=relativeNotePath(info.settings.assetsFolder+'/note.md',reference.slice(7));
      const entry=(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;
      return {blob:(await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'})).data,session:current,id:entry.id};
    }});
  }};
}

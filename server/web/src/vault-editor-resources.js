import {wikiReference,wikiCandidates,wikiCreationPath} from './wiki-links.js';
import {imagePreview} from './image-preview.js';
import {relativeNotePath} from './notes-links.js';
import {t} from './i18n.js';
import {noteTitle} from './note-title.js';
import {hashtagKey,tagNavigation} from './hashtags.js';

export const vaultNoteNavigation='timekeeper:vault-note-navigation';
const fold=value=>value.normalize('NFC').toUpperCase().toLowerCase().normalize('NFC');
const relativeReference=(origin,target)=>{
  const from=origin.split('/').slice(0,-1),to=target.split('/');while(from.length&&to.length&&fold(from[0])===fold(to[0])){from.shift();to.shift();}
  return '../'.repeat(from.length)+to.map(encodeURIComponent).join('/');
};
// Syntax admission never loads a URL. Actual traversal is checked against the
// resolved note origin before an authenticated collection lookup.
const relativeSyntax=reference=>!!relativeNotePath('x/'.repeat(32)+'note.md',reference,{legacyAssets:false});
/** All lookups use the current API session. No credentials enter resource URLs. */
export function vaultEditorResources(session,origin=()=>'',open,{canCreate=()=>false,beforeCreate=async()=>true}={}){
  let cachedSession,cache,allCache,info,infoRequest,generation=0,opening=false;
  const invalidate=()=>{cache=null;allCache=null;info=null;infoRequest=null;generation++;};
  const currentSession=()=>{const current=session();if(!current)throw new Error(t('notes.invalid_link'));if(current!==cachedSession){cachedSession=current;invalidate();}return current;};
  // Share only in-flight settings reads: each later operation still sees current roots.
  const collectionInfo=()=>{
    const current=currentSession(),cycle=generation;
    if(!infoRequest){const pending=current.request('/notes').then(reply=>{if(current!==session()||cycle!==generation)throw new Error(t('notes.invalid_link'));return reply.data;});
      infoRequest=pending;void pending.finally(()=>{if(infoRequest===pending)infoRequest=null;}).catch(()=>{});}
    return infoRequest;
  };
  const catalog=async(query='')=>{
    const current=currentSession(),cycle=generation;
    let cached=query===''?allCache:cache;
    if(!cached||cached.query!==query||Date.now()-cached.at>5000){const pending={at:Date.now(),query};pending.promise=(async()=>{
      const collection=await collectionInfo(),rows=[];let offset=0;
      do{const page=(await current.request('/notes/entries?'+new URLSearchParams({query,offset,limit:collection.limits.pageSize}),{etag:collection.snapshot})).data;rows.push(...page.items);offset=page.nextOffset;}while(offset!==undefined);
      if(current!==session()||cycle!==generation)throw new Error(t('notes.invalid_link'));info=collection;return rows;
    })();cached=pending;if(query==='')allCache=pending;else cache=pending;
      pending.promise.catch(()=>{if(cache===pending)cache=null;if(allCache===pending)allCache=null;});}
    return cached.promise;
  };
  const originPath=(entries,value,settings=info?.settings)=>{
    if(typeof value==='string')return value;
    const found=entries.find(entry=>(!value?.sourceType||entry.sourceType===value.sourceType)&&(!value?.sourceId||entry.sourceId===value.sourceId)&&(!value?.sourceKey||entry.sourceKey===value.sourceKey));
    if(found)return found.path;
    if(value?.sourceType==='DAY_NOTE'&&value.sourceKey)return settings.journalFolder+'/'+value.sourceKey+'.md';
    if(value?.sourceType==='WEEK_NOTE'&&value.sourceKey)return settings.journalFolder+'/'+value.sourceKey+'.md';
    if(value?.sourceType==='EVENT_NOTE'&&value.sourceKey)return settings.eventsFolder+'/'+value.sourceKey;
    return '';
  };
  const resolveWiki=async reference=>{
    const ref=wikiReference(reference);if(!ref)throw new Error(t('notes.invalid_link'));
    const value=origin(),filename=(ref.path||(typeof value==='string'?value:'')).split('/').at(-1).replace(/\.md$/i,'');
    // Path/name matches stay cheap and take priority. Only an unresolved bare
    // link needs title metadata; share that complete snapshot between links.
    const entries=await catalog(filename);let matches=wikiCandidates(entries,reference,originPath(entries,value),{titles:false});
    if(!matches.length&&ref.path&&!ref.path.includes('/')){
      const all=await catalog();matches=wikiCandidates(all,reference,originPath(all,value));
    }
    if(matches.length!==1){const error=new Error(t(matches.length?'notes.ambiguous_wikilink':'notes.invalid_link'));if(!matches.length)error.code='NOTE_LINK_MISSING';throw error;}return matches[0];
  };
  const resolveRelative=async reference=>{
    const value=origin(),current=currentSession(),collection=typeof value==='string'?null:await collectionInfo();
    let path=originPath([],value,collection?.settings);if(!path)path=originPath(await catalog(),value,collection?.settings);
    const ordinary=relativeNotePath(path,reference,{legacyAssets:false});if(!path||!ordinary)throw new Error(t('notes.invalid_link'));
    const native=typeof value==='object'&&['DAY_NOTE','WEEK_NOTE','EVENT_NOTE'].includes(value?.sourceType);
    const legacy=native&&reference.startsWith('assets/')?relativeNotePath(collection.settings.assetsFolder+'/note.md',reference.slice(7),{legacyAssets:false}):null;
    const find=async path=>{try{return(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;}catch(error){if(error.status===404)return null;throw error;}};
    const candidates=(await Promise.all([...new Set([ordinary,legacy].filter(Boolean))].map(find))).filter(Boolean),unique=[...new Map(candidates.map(entry=>[entry.id,entry])).values()];
    if(unique.length!==1)throw new Error(t(unique.length?'notes.ambiguous_link':'notes.invalid_link'));return unique[0];
  };
  const navigate=async(entry,heading,source,popup=false)=>{
    if(open&&!popup)return open(entry,heading);(source||document).dispatchEvent(new CustomEvent(vaultNoteNavigation,{bubbles:true,cancelable:true,detail:{entry,heading,popup,originElement:source,guards:[],leave:[]}}));
  };
  const ensureParents=async(path,current,active)=>{
    const parts=path.split('/').slice(0,-1);
    for(let count=1;count<=parts.length;count++){
      if(!active()||!canCreate())return false;
      const folder=parts.slice(0,count).join('/'),query='/notes/resolve?'+new URLSearchParams({path:folder});let entry;
      try{entry=(await current.request(query)).data;}
      catch(error){
        if(error.status!==404)throw error;if(!active()||!canCreate())return false;
        try{entry=(await current.request('/notes/folders',{method:'POST',body:{path:folder}})).data;}
        catch(failure){if(![405,409].includes(failure.status))throw failure;entry=(await current.request(query)).data;}
      }
      if(entry.kind!=='FOLDER')throw new Error(t('notes.invalid_link'));
    }
    return active()&&canCreate();
  };
  const previewImage=(load,alt)=>{const current=session();if(current)currentSession();const cycle=generation;return imagePreview({alt,active:()=>!!current&&current===session()&&cycle===generation,load:async()=>{const entry=await load();if(current!==session()||cycle!==generation)throw new Error(t('notes.invalid_link'));return(await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'})).data;},onError:(error,dom)=>{dom.alt=alt+' · '+error.message;dom.title=error.message;}});};
  return {
    searchTag:(value,source)=>{const tag=hashtagKey(value);if(!tag||!session()||source&&!source.isConnected)return;(source||document).dispatchEvent(new CustomEvent(tagNavigation,{bubbles:true,cancelable:true,detail:{tag,guards:[],leave:[]}}));},
    resolveOrigin:async()=>{const current=currentSession(),value=origin(),collection=await collectionInfo();let path=originPath([],value,collection.settings);if(!path)path=originPath(await catalog(),value,collection.settings);if(!path)throw new Error(t('notes.invalid_link'));return(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;},
    invalidate,resolveWiki,allowsAsset:relativeSyntax,allowsLink:relativeSyntax,
    image:(reference,alt)=>previewImage(()=>resolveRelative(reference),alt),
    canonicalAsset:async reference=>(await resolveRelative(reference)).path,
    assetReference:async entry=>{const value=origin(),collection=typeof value==='string'?null:await collectionInfo();let path=originPath([],value,collection?.settings);if(!path)path=originPath(await catalog(),value,collection?.settings);return relativeReference(path,entry.path);},
    previewWiki:async reference=>{const current=session(),entry=await resolveWiki(reference);if(!['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind))return entry.path;const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});if(current!==session())throw new Error(t('notes.invalid_link'));return noteTitle(entry,entry.path)+(entry.title&&entry.kind!=='EVENT'?'\n'+entry.path:'')+'\n'+(await reply.data.slice(0,1500).text()).slice(0,600);},
    complete:async(query,options={})=>(await catalog()).filter(entry=>!entry.deleted&&entry.kind!=='FOLDER'&&(options.assetsOnly?['ASSET','BINARY'].includes(entry.kind):!!wikiReference(entry.path))&&[entry.path,noteTitle(entry),entry.heading||''].some(value=>fold(value).includes(fold(query)))).slice(0,30),
    openWiki:async(reference,source)=>{
      if(opening)return;opening=true;const current=session(),active=()=>!!current&&current===session()&&(!source||source.isConnected);let entry,popup=false;
      try{
        try{entry=await resolveWiki(reference);}
        catch(error){
          if(error.code!=='NOTE_LINK_MISSING'||!active()||!canCreate())throw error;
          const value=origin(),path=wikiCreationPath(reference,originPath([],value,info?.settings));
          if(!path)throw error;
          if(!confirm(t('notes.create_linked_confirm',{path}))||!await beforeCreate()||!active()||!canCreate()||!await ensureParents(path,current,active))return;
          try{entry=(await current.request('/notes/files?'+new URLSearchParams({path}),{method:'PUT',noneMatch:'*',rawBody:new Uint8Array()})).data;}
          catch(failure){if(![409,412].includes(failure.status))throw failure;invalidate();entry=await resolveWiki(reference);}
          if(!active())return;invalidate();popup=true;document.dispatchEvent(new CustomEvent('timekeeper:notes-changed'));
        }
        if(!active())return;if(source){source.dataset.resolved='true';source.title=entry.path;source.removeAttribute('aria-label');}
        return navigate(entry,wikiReference(reference)?.heading||'',source,popup);
      }finally{opening=false;}
    },
    openLink:async(reference,source)=>{const current=session(),entry=await resolveRelative(reference);if(current!==session()||source&&!source.isConnected)return;if(['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind)){let heading='';try{heading=decodeURIComponent(reference.split('#').slice(1).join('#'));}catch{}return navigate(entry,heading,source);}const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});if(current!==session())return;const url=URL.createObjectURL(reply.data),link=document.createElement('a');link.href=url;link.download=entry.path.split('/').at(-1);link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);},
    wikiImage:(reference,alt)=>previewImage(()=>resolveWiki(reference),alt)
  };
}

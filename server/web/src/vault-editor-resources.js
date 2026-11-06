import {wikiReference,wikiCandidates} from './wiki-links.js';
import {imagePreview} from './image-preview.js';
import {relativeNotePath} from './notes-links.js';
import {t} from './i18n.js';
import {noteTitle} from './note-title.js';

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
export function vaultEditorResources(session,origin=()=>'',open){
  let cachedSession,cache,info;
  const catalog=async()=>{
    const current=session();if(!current)throw new Error(t('notes.invalid_link'));
    if(current!==cachedSession){cachedSession=current;cache=null;info=null;}
    if(!cache||Date.now()-cache.at>5000){const promise=(async()=>{
      const collection=(await current.request('/notes')).data,rows=[];let offset=0;
      do{const page=(await current.request('/notes/entries?'+new URLSearchParams({query:'',offset,limit:collection.limits.pageSize}),{etag:collection.snapshot})).data;rows.push(...page.items);offset=page.nextOffset;}while(offset!==undefined);
      if(current!==session())throw new Error(t('notes.invalid_link'));info=collection;return rows;
    })();cache={at:Date.now(),promise};promise.catch(()=>{cache=null;});}
    return cache.promise;
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
    const value=origin(),entries=await catalog(),matches=wikiCandidates(entries,reference,originPath(entries,value));
    if(matches.length!==1)throw new Error(t(matches.length?'notes.ambiguous_link':'notes.invalid_link'));return matches[0];
  };
  const resolveRelative=async reference=>{
    const value=origin(),current=session(),collection=(await current.request('/notes')).data;
    let path=originPath([],value,collection.settings);if(!path)path=originPath(await catalog(),value,collection.settings);
    const ordinary=relativeNotePath(path,reference,{legacyAssets:false});if(!path||!ordinary)throw new Error(t('notes.invalid_link'));
    const native=typeof value==='object'&&['DAY_NOTE','WEEK_NOTE','EVENT_NOTE'].includes(value?.sourceType);
    const legacy=native&&reference.startsWith('assets/')?relativeNotePath(collection.settings.assetsFolder+'/note.md',reference.slice(7),{legacyAssets:false}):null;
    const find=async path=>{try{return(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;}catch(error){if(error.status===404)return null;throw error;}};
    const candidates=(await Promise.all([...new Set([ordinary,legacy].filter(Boolean))].map(find))).filter(Boolean),unique=[...new Map(candidates.map(entry=>[entry.id,entry])).values()];
    if(unique.length!==1)throw new Error(t(unique.length?'notes.ambiguous_link':'notes.invalid_link'));return unique[0];
  };
  const navigate=async(entry,heading,source)=>{
    if(open)return open(entry,heading);(source||document).dispatchEvent(new CustomEvent(vaultNoteNavigation,{bubbles:true,cancelable:true,detail:{entry,heading,guards:[],leave:[]}}));
  };
  const previewImage=(load,alt)=>{const current=session();return imagePreview({alt,active:()=>current===session(),load:async()=>{const entry=await load();if(current!==session())throw new Error(t('notes.invalid_link'));return(await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'})).data;},onError:(error,dom)=>{dom.alt=alt+' · '+error.message;dom.title=error.message;}});};
  return {
    invalidate:()=>{cache=null;info=null;},resolveWiki,allowsAsset:relativeSyntax,allowsLink:relativeSyntax,
    image:(reference,alt)=>previewImage(()=>resolveRelative(reference),alt),
    canonicalAsset:async reference=>(await resolveRelative(reference)).path,
    assetReference:async entry=>{const value=origin(),collection=(await session().request('/notes')).data;let path=originPath([],value,collection.settings);if(!path)path=originPath(await catalog(),value,collection.settings);return relativeReference(path,entry.path);},
    previewWiki:async reference=>{const current=session(),entry=await resolveWiki(reference);if(!['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind))return entry.path;const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});if(current!==session())throw new Error(t('notes.invalid_link'));return noteTitle(entry,entry.path)+(entry.title&&entry.kind!=='EVENT'?'\n'+entry.path:'')+'\n'+(await reply.data.slice(0,1500).text()).slice(0,600);},
    complete:async(query,options={})=>(await catalog()).filter(entry=>!entry.deleted&&entry.kind!=='FOLDER'&&(options.assetsOnly?['ASSET','BINARY'].includes(entry.kind):!!wikiReference(entry.path))&&[entry.path,noteTitle(entry)].some(value=>value.toLocaleLowerCase().includes(query.toLocaleLowerCase()))).slice(0,30),
    openWiki:async(reference,source)=>{const current=session(),entry=await resolveWiki(reference);if(current!==session()||source&&!source.isConnected)return;return navigate(entry,wikiReference(reference)?.heading||'',source);},
    openLink:async(reference,source)=>{const current=session(),entry=await resolveRelative(reference);if(current!==session()||source&&!source.isConnected)return;if(['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind)){let heading='';try{heading=decodeURIComponent(reference.split('#').slice(1).join('#'));}catch{}return navigate(entry,heading,source);}const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});if(current!==session())return;const url=URL.createObjectURL(reply.data),link=document.createElement('a');link.href=url;link.download=entry.path.split('/').at(-1);link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);},
    wikiImage:(reference,alt)=>previewImage(()=>resolveWiki(reference),alt)
  };
}

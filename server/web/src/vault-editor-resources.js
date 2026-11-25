import {wikiReference,wikiCandidates,wikiCreationPath} from './wiki-links.js';
import {imagePreview} from './image-preview.js';
import {drawioPngInfo} from './drawio-png.js';
import {relativeNotePath} from './notes-links.js';
import {t} from './i18n.js';
import {noteTitle} from './note-title.js';
import {emojiText} from './emoji.js';
import {hashtagKey,tagNavigation} from './hashtags.js';
import {splitMarkdown} from './markdown-document.js';

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
export function vaultEditorResources(session,origin=()=>'',open,{canCreate=()=>false,beforeCreate=async()=>true,hasBannerSource=()=>true,bannerFrontmatter=()=>null,afterBannerChange=async()=>{},serializeBanner,bannerCommitted,bannerMatches,bannerFailed,assetResolver,dailyBannerContext=()=>origin()?.sourceType==='DAY_NOTE'}={}){
  let cachedSession,cache,allCache,info,infoRequest,generation=0,opening=false;
  const clearCatalog=()=>{cache=null;allCache=null;info=null;infoRequest=null;};
  const invalidate=()=>{clearCatalog();generation++;};
  const catalogChanged=()=>Object.assign(new Error(t('notes.invalid_link')),{code:'NOTE_CATALOG_CHANGED'});
  const currentSession=()=>{const current=session();if(!current)throw new Error(t('notes.invalid_link'));if(current!==cachedSession){cachedSession=current;invalidate();}return current;};
  // Share only in-flight settings reads: each later operation still sees current roots.
  const collectionInfo=()=>{
    const current=currentSession(),cycle=generation;
    if(!infoRequest){const pending=current.request('/notes').then(reply=>{if(current!==session())throw new Error(t('notes.invalid_link'));if(cycle!==generation)throw catalogChanged();return reply.data;});
      infoRequest=pending;void pending.finally(()=>{if(infoRequest===pending)infoRequest=null;}).catch(()=>{});}
    return infoRequest;
  };
  const catalogSnapshot=async(query='')=>{
    const current=currentSession(),cycle=generation;
    let cached=query===''?allCache:cache;
    if(!cached||cached.query!==query||Date.now()-cached.at>5000){const pending={at:Date.now(),query};pending.promise=(async()=>{
      const collection=await collectionInfo(),rows=[];let offset=0;
      do{const page=(await current.request('/notes/entries?'+new URLSearchParams({query,offset,limit:collection.limits.pageSize}),{etag:collection.snapshot})).data;rows.push(...page.items);offset=page.nextOffset;}while(offset!==undefined);
      if(current!==session())throw new Error(t('notes.invalid_link'));if(cycle!==generation)throw catalogChanged();info=collection;return rows;
    })();cached=pending;if(query==='')allCache=pending;else cache=pending;
      pending.promise.catch(()=>{if(cache===pending)cache=null;if(allCache===pending)allCache=null;});}
    return cached.promise;
  };
  const catalog=async(query='')=>{
    const current=currentSession(),source=JSON.stringify(origin()),active=()=>current===session()&&source===JSON.stringify(origin());
    for(let attempt=0;;attempt++){
      try{const rows=await catalogSnapshot(query);if(!active())throw new Error(t('notes.invalid_link'));return rows;}
      catch(error){
        if(!active()||attempt===2||error.status!==412&&error.code!=='NOTE_CATALOG_CHANGED')throw error;
        // Background metadata can invalidate a lookup without changing its owner
        // or note. Retry a fresh snapshot; do not invalidate unrelated previews.
        clearCatalog();
      }
    }
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
  const previewImage=(load,alt)=>{const current=session();if(current)currentSession();const cycle=generation;return imagePreview({alt,active:()=>!!current&&current===session()&&cycle===generation,load:async()=>{const entry=await load();if(current!==session()||cycle!==generation)throw new Error(t('notes.invalid_link'));return {blob:(await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'})).data,session:current,id:entry.id};},onError:(error,dom)=>{dom.alt=alt+' · '+error.message;dom.title=error.message;}});};
  return {
    assetIdentity:()=>({session:session(),origin:JSON.stringify(origin())}),canReplaceAsset:canCreate,
    listDiagrams:async({signal}={})=>{
      const current=currentSession(),source=JSON.stringify(origin());
      const rows=(await catalog('.png')).filter(entry=>['ASSET','BINARY'].includes(entry.kind)&&!entry.deleted&&/\.png$/i.test(entry.path));
      const diagrams=[];
      for(let offset=0;offset<rows.length;offset+=4){
        signal?.throwIfAborted();
        if(current!==session()||source!==JSON.stringify(origin()))throw new Error(t('notes.invalid_link'));
        await Promise.all(rows.slice(offset,offset+4).map(async entry=>{
          try{
            const {data:blob}=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream',signal});
            if(blob.size>5_000_000)return;
            const info=drawioPngInfo(new Uint8Array(await blob.arrayBuffer()));
            if(info?.editable)diagrams.push({entry,description:info.description});
          }catch(error){
            if(error.status!==404)throw error;
          }
        }));
      }
      if(current!==session()||source!==JSON.stringify(origin()))throw new Error(t('notes.invalid_link'));
      return diagrams.sort((a,b)=>(a.description||a.entry.path).localeCompare(b.description||b.entry.path));
    },
    readAsset:async(reference,{signal}={})=>{
      const current=currentSession(),source=JSON.stringify(origin()),active=()=>current===session()&&source===JSON.stringify(origin());
      const entry=reference.kind==='wiki'?await resolveWiki(reference.reference):await (assetResolver||resolveRelative)(reference.reference,current);if(!active()||entry.deleted||!['ASSET','BINARY'].includes(entry.kind))throw new Error(t('notes.invalid_link'));
      const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream',signal});if(!active())throw new Error(t('notes.invalid_link'));
      return {session:current,source,entry,blob:reply.data,etag:reply.etag};
    },
    replaceAsset:async(context,blob,{signal}={})=>{
      const current=currentSession();if(!canCreate()||current!==context.session||context.source!==JSON.stringify(origin()))throw new Error(t('notes.invalid_link'));
      return current.request('/notes/entries/'+context.entry.id+'/content',{method:'PUT',etag:context.etag,rawBody:blob,signal});
    },
    readTaskSource:async({signal}={})=>{
      const current=currentSession(),source=JSON.stringify(origin()),active=()=>current===session()&&source===JSON.stringify(origin());
      if(!canCreate()||!await beforeCreate()||!active())throw new Error(t('notes.stale'));
      for(let attempt=0;;attempt++)try{
      const collection=await collectionInfo(),value=origin();let path=originPath([],value,collection.settings);if(!path)path=originPath(await catalog(),value,collection.settings);
      if(!path||!active()||!canCreate())throw new Error(t('notes.stale'));
      const entry=(await current.request('/notes/resolve?'+new URLSearchParams({path}),{signal})).data;
      if(entry.deleted||!['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind)||typeof value==='object'&&(entry.sourceType!==value.sourceType||entry.sourceKey!==value.sourceKey))throw new Error(t('notes.stale'));
      const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream',signal});
      const text=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(await reply.data.arrayBuffer());if(!active()||!canCreate())throw new Error(t('notes.stale'));
      return {session:current,entry,text,etag:reply.etag};
      }catch(error){if(!active()||attempt===2||error.code!=='NOTE_CATALOG_CHANGED')throw error;clearCatalog();}
    },
    dailyBannerContext,serializeBanner,bannerCommitted,bannerMatches,bannerFailed,canCaptureBanner:canCreate,
    announceBanner:()=>document.dispatchEvent(new CustomEvent('timekeeper:notes-changed')),
    bannerVersion:()=>{if(session())currentSession();return generation;},
    bannerPreview:async(date,{signal}={})=>{const current=currentSession(),source=JSON.stringify(origin());const reply=await current.request('/daily-banners/'+date+'/preview',{signal});if(current!==session()||source!==JSON.stringify(origin()))throw new Error(t('notes.invalid_link'));return reply.data;},
    bannerIdentity:()=>({session:session(),origin:JSON.stringify(origin())}),
    bannerChanged:afterBannerChange,
    readBanner:async({flush=true}={})=>{
      if(!canCreate()||flush&&!await beforeCreate())throw new Error(t('notes.invalid_link'));
      const current=currentSession(),source=JSON.stringify(origin()),collection=await collectionInfo();let path=originPath([],origin(),collection.settings);
      if(!path)path=originPath(await catalog(),origin(),collection.settings);if(!path)throw new Error(t('notes.invalid_link'));
      let entry,reply,text='';const native=typeof origin()==='object'&&origin()?.sourceType==='DAY_NOTE',matches=entry=>!native||entry.sourceType===origin().sourceType&&entry.sourceKey===origin().sourceKey;
      try{entry=(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;}catch(error){if(error.status!==404)throw error;}
      if(native&&hasBannerSource()&&!entry)throw new Error(t('notes.stale'));
      if(entry){if(entry.deleted||!matches(entry))throw new Error(t('notes.invalid_link'));reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});text=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(await reply.data.arrayBuffer());}
      const date=entry?.kind==='DAY'?entry.sourceKey:typeof origin()==='object'&&origin()?.sourceType==='DAY_NOTE'?origin().sourceKey:null;
      if(!date||!/^\d{4}-\d\d-\d\d$/.test(date)||current!==session()||source!==JSON.stringify(origin()))throw new Error(t('notes.invalid_link'));
      return {session:current,source,path,id:entry?.id,date,text,etag:reply?.etag||null,assetsFolder:collection.settings.assetsFolder};
    },
    refreshBanner:async context=>{
      const current=currentSession();if(!context.id||current!==context.session||context.source!==JSON.stringify(origin()))throw new Error(t('notes.stale'));
      const reply=await current.request('/notes/entries/'+context.id+'/content',{binary:'application/octet-stream'});
      if(current!==session()||context.source!==JSON.stringify(origin()))throw new Error(t('notes.invalid_link'));
      return {...context,text:new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(await reply.data.arrayBuffer()),etag:reply.etag};
    },
    writeBanner:async(context,text,{notify=true}={})=>{
      const current=currentSession(),active=()=>canCreate()&&current===context.session&&context.source===JSON.stringify(origin());if(!active())throw new Error(t('notes.invalid_link'));
      if(!context.etag&&!await ensureParents(context.path,current,active))throw new Error(t('notes.invalid_link'));
      const reply=await current.request(context.etag?'/notes/entries/'+context.id+'/content':'/notes/files?'+new URLSearchParams({path:context.path}),{method:'PUT',...(context.etag?{etag:context.etag}:{noneMatch:'*'}),rawBody:new TextEncoder().encode(text)});
      if(active()){invalidate();if(notify)document.dispatchEvent(new CustomEvent('timekeeper:notes-changed'));}return reply;
    },
    uploadBanner:async(context,bytes,extension)=>{
      const current=currentSession(),active=()=>canCreate()&&current===context.session&&context.source===JSON.stringify(origin());if(!active()||!['png','jpg','gif','webp'].includes(extension)||bytes.length>5_000_000)throw new Error(t('markdown.upload_failed'));
      const path=context.assetsFolder+'/'+crypto.randomUUID()+'.'+extension;if(!await ensureParents(path,current,active))throw new Error(t('notes.invalid_link'));
      const reply=await current.request('/notes/files?'+new URLSearchParams({path}),{method:'PUT',noneMatch:'*',rawBody:bytes});if(!active())throw new Error(t('notes.invalid_link'));invalidate();return reply.data.path;
    },
    searchTag:(value,source)=>{const tag=hashtagKey(value);if(!tag||!session()||source&&!source.isConnected)return;(source||document).dispatchEvent(new CustomEvent(tagNavigation,{bubbles:true,cancelable:true,detail:{tag,guards:[],leave:[]}}));},
    resolveOrigin:async()=>{const current=currentSession(),value=origin(),collection=await collectionInfo();let path=originPath([],value,collection.settings);if(!path)path=originPath(await catalog(),value,collection.settings);if(!path)throw new Error(t('notes.invalid_link'));return(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;},
    invalidate,resolveWiki,allowsAsset:relativeSyntax,allowsLink:relativeSyntax,
    bannerFrontmatter:async()=>{
      const projected=bannerFrontmatter();if(typeof projected==='string')return projected;
      const value=origin();if(typeof value!=='object'||!['DAY_NOTE','EVENT_NOTE'].includes(value?.sourceType))return null;
      // Collection reads initialize default folders. An absent native note has
      // no file header, so merely viewing its empty editor needs no vault read.
      if(!hasBannerSource())return '';
      const current=currentSession(),cycle=generation,collection=await collectionInfo(),path=originPath([],value,collection.settings);if(!path)return '';
      const entry=(await current.request('/notes/resolve?'+new URLSearchParams({path}))).data;
      if(current!==session()||cycle!==generation)return '';
      const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});
      if(current!==session()||cycle!==generation)return '';
      return splitMarkdown(await reply.data.text()).frontmatter;
    },
    bannerImage:(reference,alt)=>previewImage(async()=>{
      try{return(await currentSession().request('/notes/resolve?'+new URLSearchParams({path:reference}))).data;}
      catch(error){if(error.status!==404)throw error;return resolveWiki(reference);}
    },alt),
    image:(reference,alt)=>previewImage(()=>resolveRelative(reference),alt),
    canonicalAsset:async reference=>(await resolveRelative(reference)).path,
    assetReference:async entry=>{const value=origin(),collection=typeof value==='string'?null:await collectionInfo();let path=originPath([],value,collection?.settings);if(!path)path=originPath(await catalog(),value,collection?.settings);return relativeReference(path,entry.path);},
    previewWiki:async reference=>{const current=session(),entry=await resolveWiki(reference);if(!['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind))return entry.path;const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});if(current!==session())throw new Error(t('notes.invalid_link'));return noteTitle(entry,entry.path)+(entry.title&&entry.kind!=='EVENT'?'\n'+entry.path:'')+'\n'+(await reply.data.slice(0,1500).text()).slice(0,600);},
    complete:async(query,options={})=>(await catalog()).filter(entry=>!entry.deleted&&entry.kind!=='FOLDER'&&(options.assetsOnly?['ASSET','BINARY'].includes(entry.kind):!!wikiReference(entry.path))&&[entry.path,noteTitle(entry),emojiText(entry.heading||'')].some(value=>fold(value).includes(fold(query)))).slice(0,30),
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

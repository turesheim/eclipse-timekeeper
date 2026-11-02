import {wikiReference,wikiCandidates} from './wiki-links.js';
import {imagePreview} from './image-preview.js';
import {t} from './i18n.js';

export const vaultNoteNavigation='timekeeper:vault-note-navigation';
/** All lookups use the current API session. No credentials enter resource URLs. */
export function vaultEditorResources(session,origin=()=>'',open){
  let cachedSession,cache;
  const catalog=async()=>{
    const current=session();if(!current)throw new Error(t('notes.invalid_link'));
    if(current!==cachedSession){cachedSession=current;cache=null;}
    // One request chain per completion/lookup burst; refresh after five seconds.
    if(!cache||Date.now()-cache.at>5000){const promise=(async()=>{
      const info=(await current.request('/notes')).data,rows=[];let offset=0;
      do{const page=(await current.request('/notes/entries?'+new URLSearchParams({query:'',offset,limit:info.limits.pageSize}),{etag:info.snapshot})).data;rows.push(...page.items);offset=page.nextOffset;}while(offset!==undefined);
      if(current!==session())throw new Error(t('notes.invalid_link'));return rows;
    })();cache={at:Date.now(),promise};promise.catch(()=>{cache=null;});}
    return cache.promise;
  };
  const resolveWiki=async reference=>{
    const matches=wikiCandidates(await catalog(),reference,origin());
    if(matches.length!==1)throw new Error(t(matches.length?'notes.ambiguous_link':'notes.invalid_link'));
    return matches[0];
  };
  return {resolveWiki,
    previewWiki:async reference=>{const current=session(),entry=await resolveWiki(reference);if(!['MARKDOWN','DAY','WEEK','EVENT'].includes(entry.kind))return entry.path;const reply=await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'});if(current!==session())throw new Error(t('notes.invalid_link'));return entry.path+'\n'+(await reply.data.slice(0,1500).text()).slice(0,600);},
    complete:async query=>(await catalog()).filter(e=>!e.deleted&&e.kind!=='FOLDER'&&e.path.toLocaleLowerCase().includes(query.toLocaleLowerCase())).slice(0,30),
    openWiki:async reference=>{const entry=await resolveWiki(reference),heading=wikiReference(reference)?.heading||'';if(open)return open(entry,heading);document.dispatchEvent(new CustomEvent(vaultNoteNavigation,{detail:{entry,heading}}));},
    wikiImage:(reference,alt)=>{const current=session();return imagePreview({alt,active:()=>current===session(),load:async()=>{const entry=await resolveWiki(reference);return(await current.request('/notes/entries/'+entry.id+'/content',{binary:'application/octet-stream'})).data;},onError:(error,dom)=>{dom.alt=alt+' · '+error.message;dom.title=error.message;}});}
  };
}

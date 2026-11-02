/** Obsidian references remain vault-local; schemes and traversal are never URLs. */
export function wikiReference(value,embed=false){
  if(typeof value!=='string'||!value||value.length>1024||/[\r\n\[\]\\\u0000-\u001f]/.test(value))return null;
  const [target,...aliases]=value.split('|'),hash=target.indexOf('#'),path=(hash<0?target:target.slice(0,hash)).trim(),heading=hash<0?'':target.slice(hash+1).trim();
  if(path.startsWith('/')||/^[a-z][a-z0-9+.-]*:/i.test(path)||path.split('/').some(p=>p==='..')||(!path&&!heading))return null;
  const alias=aliases.join('|'),size=embed?/^(\d{1,4})(?:x(\d{1,4}))?$/.exec(alias):null;
  return {path,heading,label:alias&&!size?alias:heading||path.split('/').at(-1).replace(/\.md$/i,''),width:size?Math.min(2000,Number(size[1])):null,height:size?.[2]?Math.min(2000,Number(size[2])):null};
}
export function wikiCandidates(entries,reference,origin=''){
  const ref=wikiReference(reference);if(!ref)return [];
  const files=entries.filter(entry=>!entry.deleted&&entry.kind!=='FOLDER');
  if(!ref.path)return files.filter(entry=>entry.path===origin);
  const stem=path=>path.normalize('NFC').replace(/\.md$/i,'').toUpperCase().toLowerCase().normalize('NFC');
  const target=stem(ref.path),relative=stem((origin.includes('/')?origin.slice(0,origin.lastIndexOf('/')+1):'')+ref.path);
  const exact=files.filter(entry=>stem(entry.path)===target||stem(entry.path)===relative);
  return exact.length?exact:files.filter(entry=>stem(entry.path.split('/').at(-1))===target);
}
export const headingAnchor=text=>text.trim().toLocaleLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu,'').replace(/\s+/g,'-');

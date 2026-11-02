/** Reads snapshots only. Applying a snapshot changes the draft, never its save guard. */
export function noteRevisionResources(session,identity,{bodyOnly=false,latest}={}){
  const query=()=>{const value=identity();if(!value?.sourceType||!value?.sourceId)return null;return new URLSearchParams(value);};
  return {
    revisions:async()=>{const parameters=query();if(!parameters)return [];try{return(await session().request('/notes/revisions?'+parameters)).data.items;}catch(error){if(error.status===404)return [];throw error;}},
    revision:async id=>{const parameters=query();if(!parameters)throw new Error('Note unavailable');const value=(await session().request('/notes/revisions/'+encodeURIComponent(id)+'?'+parameters)).data;return {...value,text:bodyOnly?value.body:value.markdown};},
    latest
  };
}
export function lineComparison(current,previous){
  const before=previous.split(/\r\n|\n|\r/),after=current.split(/\r\n|\n|\r/);let prefix=0,suffix=0;
  while(prefix<before.length&&prefix<after.length&&before[prefix]===after[prefix])prefix++;
  while(suffix<before.length-prefix&&suffix<after.length-prefix&&before[before.length-1-suffix]===after[after.length-1-suffix])suffix++;
  const lines=[...before.slice(0,prefix).map(text=>'  '+text),...before.slice(prefix,before.length-suffix).map(text=>'- '+text),...after.slice(prefix,after.length-suffix).map(text=>'+ '+text),...after.slice(after.length-suffix).map(text=>'  '+text)];
  return lines.join('\n');
}

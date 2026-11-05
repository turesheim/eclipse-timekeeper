/** Reads snapshots only. Applying a snapshot changes the draft, never its save guard. */
export function noteRevisionResources(session,identity,{bodyOnly=false,latest}={}){
  const query=()=>{const value=identity();if(!value?.sourceType||!value?.sourceId)return null;return new URLSearchParams(value);};
  return {
    revisions:async()=>{const parameters=query();if(!parameters)return [];try{return(await session().request('/notes/revisions?'+parameters)).data.items;}catch(error){if(error.status===404)return [];throw error;}},
    revision:async id=>{const parameters=query();if(!parameters)throw new Error('Note unavailable');const reply=await session().request('/notes/revisions/'+encodeURIComponent(id)+'?'+parameters),value=reply.data;return {...value,etag:reply.etag,text:bodyOnly?value.body:value.markdown};},
    deleteRevision:async(id,etag)=>{const parameters=query();if(!parameters)throw new Error('Note unavailable');return session().request('/notes/revisions/'+encodeURIComponent(id)+'?'+parameters,{method:'DELETE',etag});},
    latest
  };
}

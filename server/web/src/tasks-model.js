import {dateInZone,localDateTimeToInstant,taskPresentation} from './model.js';

/** Preserve civil dates; never invent an instant for an unknown zone or ambiguous wall time. */
export function taskMomentDate(moment,zone){
  if(!moment)return {date:null,unresolved:false};
  // Comparing civil days in the same zone requires no invented UTC instant.
  if(moment.kind==='DATE'||moment.timeZone===zone)return {date:moment.date,unresolved:false};
  try{
    if(moment.timeZone==='UTC')return {date:dateInZone(moment.date+'T'+moment.time+'Z',zone),unresolved:false};
    return {date:dateInZone(localDateTimeToInstant(moment.date+'T'+moment.time,moment.timeZone),zone),unresolved:false};
  }
  catch{return {date:null,unresolved:true};}
}
export function taskRows(data,calendars=[]){
  const zone=data.profile.data.zoneId,projects=new Map(data.projects.map(value=>[value.id,value]));
  const make=(record,source)=>{
    const project=source==='task'?projects.get(record.projectId):null,clientId=source==='task'?project?.clientId||null:record.clientId;
    const start=taskMomentDate(record.start,zone),due=taskMomentDate(record.due,zone),presentation=taskPresentation(record);
    return {key:source+':'+record.id,id:record.id,source,record,clientId,projectName:project?.name||'',title:presentation.title,url:presentation.url,
      status:record.status==='NEEDS-ACTION'?'NOT_STARTED':record.status==='IN-PROCESS'?'IN_PROGRESS':record.status||'NOT_STARTED',
      categories:[...new Set([...(record.metadata?.categories||record.categories||[]),...(project?[project.name]:[])])],
      scheduledDate:record.scheduledDate||null,startDate:start.date,dueDate:due.date,unresolvedTiming:start.unresolved||due.unresolved,urgent:!!record.urgent,
      recurring:source==='calendar'?record.recurring:!!record.recurrence?.series};
  };
  return [...data.tasks.map(value=>make(value,'task')),...calendars.filter(value=>!data.focusClientIds||data.focusClientIds.includes(value.clientId)).map(value=>make(value,'calendar'))];
}
export function taskMatches(row,selection,today){
  const unfinished=!['COMPLETED','CANCELLED'].includes(row.status);
  if(selection.kind==='client')return row.clientId===selection.value;
  if(selection.kind==='category')return row.categories.includes(selection.value);
  if(selection.value==='today')return unfinished&&[row.scheduledDate,row.startDate,row.dueDate].includes(today);
  if(selection.value==='critical')return unfinished&&(row.urgent||!!row.dueDate&&row.dueDate<=today);
  return true;
}

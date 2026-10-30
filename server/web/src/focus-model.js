import {addDays,activityDayNanos,durationNanos} from './model.js';

// Focus is a presentation filter. Commands always retain the unfiltered snapshot.
export function focusIncludes(data,clientId){return !data.focusClientIds || data.focusClientIds.includes(clientId);}
const duration=nanos=>`PT${nanos/1000000000n}.${(nanos%1000000000n).toString().padStart(9,'0')}S`;
export function focusedSnapshot(source,area){
  if(!area)return source;
  const clientIds=new Set(area.clientIds),clients=source.clients.filter(value=>clientIds.has(value.id));
  const projects=source.projects.filter(value=>clientIds.has(value.clientId)),projectIds=new Set(projects.map(value=>value.id));
  const tasks=source.tasks.filter(value=>projectIds.has(value.projectId)),taskIds=new Set(tasks.map(value=>value.id));
  const activities=source.activities.filter(value=>value.projectId?projectIds.has(value.projectId):taskIds.has(value.taskId));
  const days=Array.from({length:7},(_,index)=>{
    const date=addDays(source.dates.first,index),totals=new Map();
    for(const activity of activities){const n=activityDayNanos(activity,date,source.profile.data.zoneId,source.report.generatedAt);if(n)totals.set(activity.taskId||null,(totals.get(activity.taskId||null)||0n)+n);}
    return {date,total:duration([...totals.values()].reduce((sum,n)=>sum+n,0n)),tasks:[...totals].map(([taskId,n])=>({taskId,total:duration(n)}))};
  });
  const total=days.reduce((sum,day)=>sum+durationNanos(day.total),0n);
  return {...source,allClients:source.clients,focusClientIds:clients.map(value=>value.id),clients,projects,tasks,activities,
    workSessions:source.workSessions.filter(value=>clientIds.has(value.clientId)),report:{...source.report,total:duration(total),days}};
}

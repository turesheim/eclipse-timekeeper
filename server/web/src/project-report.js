import {Api,ApiError} from './api.js';
import {t} from './i18n.js';
import {instantNanos,instantText,nanosText} from './model.js';
import {node} from './account-ui.js';

const $=id=>document.getElementById(id),projectId=new URLSearchParams(location.search).get('projectId');
let session=null,owner=null,generation=0,loading=false;
function clear() {
  $('report-content').hidden=true;$('report-print').disabled=true;$('report-sessions').replaceChildren();
  for(const id of ['report-context','report-summary','report-total','report-as-of'])$(id).textContent='';
  $('report-title').textContent=t('ui.work_sessions');document.title=t('project_report.title');
}
function failure(error) {
  clear();$('report-status').textContent='';$('report-error').textContent=error.status===401?t('project_report.sign_in'):error.status===404?t('project_report.not_found'):error.message;
  $('report-sign-in').hidden=error.status!==401;
}
async function load() {
  if(loading)return;loading=true;const current=++generation;clear();$('report-error').textContent='';$('report-sign-in').hidden=true;
  $('report-status').textContent=t('project_report.loading');$('report-refresh').disabled=true;
  try {
    if(!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(projectId||''))throw new Error(t('project_report.invalid'));
    session?.close();session=new Api(null,undefined,owner===null?{}:{ownerId:owner});
    const api=session,{data:account}=await api.request('/auth/session');owner=account.ownerId;
    const [project,profile,clients,sessions]=await Promise.all([
      api.request('/projects/'+projectId),api.request('/me'),api.page('/clients'),api.page('/work-sessions',{projectId})
    ]);
    if(current!==generation||api!==session)return;
    const zone=profile.data.zoneId,asOf=new Date().toISOString(),clientName=id=>clients.items.find(client=>client.id===id)?.name||t('ui.unassigned');
    const values=[...sessions.items].sort((a,b)=>{const left=instantNanos(a.start),right=instantNanos(b.start);return left<right?1:left>right?-1:a.id.localeCompare(b.id);});
    let total=0n;const rows=[];
    for(const value of values) {
      const elapsed=instantNanos(value.end||asOf)-instantNanos(value.start),duration=elapsed>0n?elapsed:0n;total+=duration;
      const row=node('tr');row.dataset.sessionId=value.id;
      for(const text of [instantText(value.start,zone),value.end?instantText(value.end,zone):t('ui.running'),value.summary||'—',clientName(value.clientId),nanosText(duration)])row.append(node('td',text));
      rows.push(row);
    }
    $('report-title').textContent=project.data.name;document.title=project.data.name+' · '+t('project_report.title');
    $('report-context').textContent=clientName(project.data.clientId)+' · '+zone;
    $('report-sessions').replaceChildren(...rows);$('report-total').textContent=nanosText(total);
    $('report-summary').textContent=t('project_report.summary',{count:values.length,duration:nanosText(total)});
    $('report-as-of').textContent=t('project_report.as_of',{time:instantText(asOf,zone)});
    $('report-empty').hidden=values.length>0;$('report-table').hidden=!values.length;$('report-content').hidden=false;
    $('report-status').textContent='';$('report-print').disabled=false;
  } catch(error) {if(current===generation)failure(error instanceof ApiError?error:new Error(error.message||t('project_report.unavailable')));}
  finally {if(current===generation){loading=false;$('report-refresh').disabled=false;}}
}
$('report-refresh').addEventListener('click',()=>void load());$('report-print').addEventListener('click',()=>window.print());
// Recheck the pinned owner on return to this tab, including back/forward cache.
window.addEventListener('pageshow',event=>{if(event.persisted)void load();});
document.addEventListener('visibilitychange',()=>{
  if(document.hidden){++generation;session?.close();loading=false;clear();$('report-status').textContent=t('project_report.loading');}
  else void load();
});
void load();

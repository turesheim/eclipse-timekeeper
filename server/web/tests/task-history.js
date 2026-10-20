import {expect} from '@playwright/test';
import {dateInZone} from '../src/model.js';

const fixtures=new Set();
async function api(request,method,path,data,headers={}) {
  const reply=await request.fetch('/api/v1'+path,{method,data,headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`,...headers}});
  expect(reply.ok()).toBe(true);return reply;
}
export function taskHistoryRow(page,taskId) {
  return page.locator('#activities tr[data-kind=task]').filter({has:page.locator(`.task-title[data-task-id="${taskId}"]`)});
}
export async function openTaskHistory(page,task) {
  const request=page.request;
  const history=await (await api(request,'GET',`/activities?from=1990-01-01T00:00:00Z&to=2100-01-01T00:00:00Z&taskId=${task.id}`)).json();
  let activity=history.items[0];
  if(!activity) {
    // A task appears in Activities once it has recorded time. Keep this fixture
    // outside the report ranges under test and remove it after each test.
    activity=await (await api(request,'POST','/activities',{taskId:task.id,start:'1999-12-29T12:00:00Z',end:'1999-12-29T12:00:01Z',summary:'Earlier task history',labelIds:[]})).json();
    fixtures.add(activity.id);
  }
  const profile=await (await api(request,'GET','/me')).json();
  await page.locator('[data-tab=activities]').click();
  await page.getByLabel('Week containing',{exact:true}).fill(dateInZone(Date.parse(activity.start),profile.zoneId));
  const row=taskHistoryRow(page,task.id);await expect(row).toBeVisible();return row;
}
export async function clearTaskHistory(request) {
  for(const id of fixtures) {
    const reply=await request.get('/api/v1/activities/'+id,{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`}});
    if(reply.status()!==404) {
      expect(reply.ok()).toBe(true);
      await api(request,'DELETE','/activities/'+id,undefined,{'If-Match':reply.headers().etag});
    }
    fixtures.delete(id);
  }
}

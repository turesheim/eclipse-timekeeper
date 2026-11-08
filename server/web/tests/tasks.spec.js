import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {dateInZone} from '../src/model.js';
import {captureScreenshot} from './screenshots.js';

async function login(page,user){await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');}
async function apiFor(page){const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();return async(method,path,data,headers={})=>{const result=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...(method==='POST'?{'Idempotency-Key':randomUUID()}:{}),...headers}});expect(result.ok(),`${method} ${path}: ${result.status()}`).toBe(true);return result;};}
async function setup(page,request,{locale='en-GB',remoteCount=6}={}){
  const user={username:'tasks-'+randomUUID().slice(0,8),password:'Private task browser account password'};
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{...user,email:user.username+'@example.invalid',displayName:user.username,administrator:false}})).status()).toBe(201);
  await login(page,user);let api=await apiFor(page);const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'Europe/Oslo',locale},{'If-Match':profile.headers().etag});
  const today=dateInZone(Date.now(),'Europe/Oslo'),clientA=await(await api('POST','/clients',{name:'Client A',color:'#176653'})).json(),clientB=await(await api('POST','/clients',{name:'Client B',color:'#6750a4'})).json();
  const project=await(await api('POST','/projects',{name:'Mylyn category',clientId:clientA.id,icon:'code'})).json();
  const native={};for(const [key,summary,projectId]of [['local','Local task',null],['linked','Imported review',project.id],['done','Finished task',null],['urgent','Urgent task',null]])native[key]=await(await api('POST','/tasks',{summary,projectId,parentTaskId:null,url:null})).json();
  const change=async(key,part,data)=>{const current=await api('GET','/tasks/'+native[key].id);await api('PUT','/tasks/'+native[key].id+'/'+part,data,{'If-Match':current.headers().etag});};
  await change('local','planning',{status:'NOT_STARTED',scheduledDate:today,completedAt:null});
  await change('linked','external-references',{providerId:'mylyn',repositoryId:'https://tracker.example.invalid',externalId:'M-42',externalUrl:'https://tracker.example.invalid/browse/M-42'});
  await change('linked','metadata',{tags:[],categories:['Review'],links:[]});await change('linked','timing',{start:null,due:{kind:'DATE',date:'1999-01-01'}});
  await change('done','planning',{status:'COMPLETED',scheduledDate:today,completedAt:new Date().toISOString()});await change('urgent','importance',{priority:'NORMAL',flagged:false,urgent:true});
  const special=['UID:undated\nSUMMARY:Calendar undated\nCATEGORIES:Team','UID:past\nSUMMARY:Calendar old\nDUE;VALUE=DATE:19990101','UID:future\nSUMMARY:Calendar future\nDUE;VALUE=DATE:20990101','UID:today\nSUMMARY:Calendar today\nDUE;VALUE=DATE:'+today.replaceAll('-',''),'UID:done\nSUMMARY:Calendar completed\nSTATUS:COMPLETED','UID:cancelled\nSUMMARY:Calendar cancelled\nSTATUS:CANCELLED'];
  const values=Array.from({length:remoteCount},(_,index)=>special[index]||`UID:remote-${index}\nSUMMARY:Remote task ${index}\nCATEGORIES:Team`),body='BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Dagsverk//Tasks browser//EN\r\n'+values.map(value=>'BEGIN:VTODO\r\nDTSTAMP:20260101T000000Z\r\n'+value.replaceAll('\n','\r\n')+'\r\nEND:VTODO\r\n').join('')+'END:VCALENDAR\r\n';
  const feed=await(await request.post(process.env.TIMEKEEPER_WEB_CALENDAR_FIXTURE+'/feeds',{data:{body}})).json();const subscription=await(await api('POST','/calendar-subscriptions',{name:'Task calendar',clientId:clientB.id,visible:true,includeEvents:false,includeTodos:true,url:feed.url})).json();
  await expect.poll(async()=>((await(await api('GET','/calendar-tasks')).json()).items.length)).toBe(Math.min(remoteCount,200));
  await page.reload();await expect(page.locator('html')).toHaveAttribute('lang',locale);await expect(page.locator('#workspace')).toBeVisible();api=await apiFor(page);await page.locator('[data-tab=tasks]').click();await expect(page.locator('#tasks-list')).toHaveAttribute('aria-busy','false');
  return {api,user,native,clientA,clientB,project,subscription,feed,today};
}
const filter=(page,name)=>page.locator('#tasks-navigation').getByRole('button',{name,exact:true});
const row=(page,title)=>page.locator('#tasks-list').getByRole('button',{name:'Open task '+title,exact:true});

test('Tasks browses all sources by client/category, today and urgency and creates and edits local tasks',async({page,request})=>{
  const {api,native}=await setup(page,request);await expect(page.locator('#tasks-list > li')).toHaveCount(10);await expect(row(page,'M-42: Imported review')).toBeVisible();await expect(row(page,'Calendar future')).toBeVisible();
  await filter(page,'Today').click();await expect(page.locator('#tasks-list > li')).toHaveCount(2);await expect(row(page,'Local task')).toBeVisible();await expect(row(page,'Calendar today')).toBeVisible();
  await filter(page,'Time critical').click();await expect(page.locator('#tasks-list > li')).toHaveCount(4);await expect(row(page,'Urgent task')).toBeVisible();await expect(row(page,'Finished task')).toHaveCount(0);await expect(row(page,'Calendar completed')).toHaveCount(0);
  await filter(page,'Client A').click();await expect(page.locator('#tasks-list > li')).toHaveCount(1);await expect(row(page,'M-42: Imported review')).toBeVisible();
  for(const category of ['Review','Mylyn category']){await filter(page,category).click();await expect(page.locator('#tasks-list > li')).toHaveCount(1);}
  await filter(page,'Team').click();await expect(row(page,'Calendar undated')).toBeVisible();await filter(page,'All').click();await row(page,'Calendar old').click();await expect(page.locator('#task-details')).toContainText('1999-01-01');await expect(page.locator('#task-details').getByRole('button',{name:'Edit task',exact:true})).toBeHidden();await page.keyboard.press('Escape');
  await page.locator('#tasks-panel').getByRole('button',{name:'New task',exact:true}).click();await page.locator('#field-summary').fill('Created in Tasks');await page.locator('#save').click();await expect(page.locator('#editor')).toBeHidden();await expect(row(page,'Created in Tasks')).toBeVisible();
  await row(page,'Local task').click();await page.locator('#task-details').getByRole('button',{name:'Edit task',exact:true}).click();await page.locator('#field-summary').fill('Edited in Tasks');await page.locator('#save').click();await expect(row(page,'Edited in Tasks')).toBeVisible();expect((await(await api('GET','/tasks/'+native.local.id)).json()).summary).toBe('Edited in Tasks');
  expect((await(await api('GET','/tasks?limit=500')).json()).items).toHaveLength(5);
});

test('all calendar pages remain available and the task list renders additional rows on demand',async({page,request})=>{
  const {api}=await setup(page,request,{remoteCount:221});await expect(page.locator('.tasks-results > [role=status]')).toHaveText('225 tasks');await expect(page.locator('#tasks-list > li')).toHaveCount(50);
  for(let count=100;count<=200;count+=50){await page.locator('#tasks-panel').getByRole('button',{name:'Show more tasks',exact:true}).click();await expect(page.locator('#tasks-list > li')).toHaveCount(count);}
  await page.locator('#tasks-panel').getByRole('button',{name:'Show more tasks',exact:true}).click();await expect(page.locator('#tasks-list > li')).toHaveCount(225);await expect(row(page,'Remote task 220')).toBeVisible();await expect(page.locator('#tasks-panel').getByRole('button',{name:'Show more tasks',exact:true})).toBeHidden();
  expect((await(await api('GET','/tasks?limit=500')).json()).items).toHaveLength(4);
});

test('read-only tasks remain browsable and sign-out clears every source before another account signs in',async({page,request,browser})=>{
  const {api}=await setup(page,request),token=await(await api('POST','/auth/tokens',{name:'Read-only task browser',permissions:['read'],expiresAt:null})).json();
  const user={username:'empty-tasks-'+randomUUID().slice(0,8),password:'Separate empty task account password'};
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{...user,email:user.username+'@example.invalid',displayName:user.username,administrator:false}})).status()).toBe(201);
  const context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true,locale:'en-GB'});
  try{
    const reader=await context.newPage();let writes=0;reader.on('request',request=>{if(!['GET','HEAD'].includes(request.method())&&/^\/api\/v1\/(tasks|calendar-tasks)(\/|$)/.test(new URL(request.url()).pathname))writes++;});
    await reader.goto('/web/');await reader.locator('#auth-mode').selectOption('token');await reader.locator('#token').fill(token.token);await reader.locator('#login-form button[type=submit]').click();await expect(reader.locator('#workspace')).toBeVisible();await reader.locator('[data-tab=tasks]').click();await expect(reader.locator('#tasks-list > li')).toHaveCount(10);
    await expect(reader.locator('#tasks-panel').getByRole('button',{name:'New task',exact:true})).toBeDisabled();await row(reader,'Local task').click();await expect(reader.locator('#task-details').getByRole('button',{name:'Edit task',exact:true})).toBeDisabled();await reader.keyboard.press('Escape');await filter(reader,'Time critical').click();await expect(reader.locator('#tasks-list > li')).toHaveCount(4);expect(writes).toBe(0);
    await reader.getByRole('button',{name:'Account menu',exact:true}).click();await reader.getByRole('button',{name:'Sign out',exact:true}).click();await expect(reader.locator('#login')).toBeVisible();await expect(reader.locator('#tasks-panel')).not.toContainText(/Local task|Imported review|Client A|Task calendar/);
    await login(reader,user);await reader.locator('[data-tab=tasks]').click();await expect(reader.locator('#tasks-list > li')).toHaveCount(0);await expect(reader.locator('#tasks-panel')).not.toContainText(/Local task|Imported review|Client A|Task calendar/);
  }finally{await context.close();}
});

for(const locale of ['en-GB','nb-NO'])test(`Tasks supports keyboard/mobile browsing and all themes in ${locale}`,async({page,request})=>{
  await setup(page,request,{locale});
  for(const width of [1440,320]){
    await page.setViewportSize({width,height:900});
    for(const theme of ['timekeeper','timekeeper-dark','solarized-light','solarized-dark','nord','dracula']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=['timekeeper','solarized-light'].includes(theme)?'light':'dark';},theme);
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      if(width===320)await page.locator('#tasks-panel > .tasks-heading > button').click();
      await expect(filter(page,locale==='nb-NO'?'Tidskritiske':'Time critical')).toBeVisible();
      expect((await new AxeBuilder({page}).include('#tasks-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
      await captureScreenshot(page,{path:`test-results/tasks-${locale}-${width}-${theme}.png`});
      if(width===320){await filter(page,locale==='nb-NO'?'Alle':'All').focus();await page.keyboard.press('Escape');await expect(page.locator('#tasks-panel > .tasks-heading > button')).toBeFocused();}
    }
  }
});

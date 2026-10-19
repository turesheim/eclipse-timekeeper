import {openTaskManagement} from './navigation.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
const token=process.env.TIMEKEEPER_WEB_TOKEN;
async function api(request,method,path,data,headers={}) {
  const response=await request.fetch(`/api/v1${path}`,{method,data,headers:{Authorization:`Bearer ${token}`,...headers}});
  expect(response.ok(),`${method} ${path}: ${response.status()}`).toBeTruthy();return response;
}
async function save(page) {await page.locator('#save').click();await expect(page.locator('#editor')).not.toBeVisible();await expect(page.locator('#status')).toHaveText('Up to date');}
test('client and project lists and choices sort names after create, rename and reload', async({page,request})=>{
  const prefix='Sort '+randomUUID().slice(0,6), clients=[], projects=[];
  const names=['Zulu','alpha 10','Beta','alpha 2'];
  try {
    for(const suffix of names) clients.push(await (await api(request,'POST','/clients',{name:prefix+' '+suffix,color:'#176653'})).json());
    for(const suffix of names) projects.push(await (await api(request,'POST','/projects',{name:prefix+' '+suffix,clientId:clients[0].id})).json());
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    const expected=['alpha 2','alpha 10','Beta','Zulu'].map(value=>prefix+' '+value);
    for(const [tab,id] of [['Clients','clients'],['Projects','projects']]) {
      await page.getByRole('button',{name:tab,exact:true}).click();
      await expect(page.locator(`#${id} tbody tr`).filter({hasText:prefix}).locator('td:first-child')).toHaveText(expected);
    }
    await page.getByRole('button',{name:'New project',exact:true}).click();
    await expect(page.getByLabel('Client',{exact:true}).locator('option').filter({hasText:prefix})).toHaveText(expected);await page.locator('#cancel').click();
    await openTaskManagement(page);await page.getByRole('button',{name:'New task',exact:true}).click();
    await expect(page.getByLabel('Project',{exact:true}).locator('option').filter({hasText:prefix})).toHaveText(expected);await page.locator('#cancel').click();
    for(const [collection,record] of [['clients',clients[0]],['projects',projects[0]]]) {
      const loaded=await api(request,'GET',`/${collection}/${record.id}`);
      await api(request,'PUT',`/${collection}/${record.id}`,{name:prefix+' Aardvark',...(collection==='clients' ? {color:record.color} : {clientId:record.clientId})},{'If-Match':loaded.headers().etag});
    }
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
    for(const [tab,id] of [['Clients','clients'],['Projects','projects']]) {
      await page.getByRole('button',{name:tab,exact:true}).click();
      await expect(page.locator(`#${id} tbody tr`).filter({hasText:prefix}).locator('td:first-child')).toHaveText(['Aardvark','alpha 2','alpha 10','Beta'].map(value=>prefix+' '+value));
    }
  } finally {
    for(const [collection,records] of [['projects',projects],['clients',clients]]) for(const record of records) {
      const loaded=await api(request,'GET',`/${collection}/${record.id}`);await api(request,'DELETE',`/${collection}/${record.id}`,undefined,{'If-Match':loaded.headers().etag});
    }
  }
});
test('clients maintain names and colors, own projects and color work-session summaries after reload',async({page,request})=>{
  const prefix='Client '+randomUUID().slice(0,6);let client,session;const projects=[];
  try {
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Clients',exact:true}).click();await page.getByRole('button',{name:'New client',exact:true}).click();
    await page.getByLabel('Name',{exact:true}).fill(prefix);await page.getByLabel('Color',{exact:true}).fill('#ffee00');await save(page);
    client=(await (await api(request,'GET','/clients?q='+encodeURIComponent(prefix))).json()).items[0];
    for(let i=0;i<2;i++) {
      await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('button',{name:'New project',exact:true}).click();
      await page.getByLabel('Name',{exact:true}).fill(prefix+' project '+i);await page.getByLabel('Client',{exact:true}).selectOption(client.id);await save(page);
    }
    projects.push(...(await (await api(request,'GET','/projects?clientId='+client.id)).json()).items);expect(projects).toHaveLength(2);
    for(const project of projects) await expect(page.locator(`#projects tbody[data-client-id="${client.id}"] [data-project-id="${project.id}"]`)).toHaveText(new RegExp(project.name));
    await page.getByRole('button',{name:'Record work session',exact:true}).click();await page.getByLabel('Summary',{exact:true}).fill(prefix+' planning');await page.getByLabel('Client',{exact:true}).selectOption(client.id);
    await expect(page.getByLabel('Start (local time)',{exact:true})).toHaveAttribute('type','datetime-local');await expect(page.getByLabel('End (local time, blank while running)',{exact:true})).toHaveAttribute('type','datetime-local');
    await expect(page.getByLabel('Enter exact times',{exact:true})).toHaveCount(0);
    await page.screenshot({path:'test-results/work-session-date-picker.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});
    const pickerBox=await page.getByLabel('Start (local time)',{exact:true}).boundingBox();expect(pickerBox.x).toBeGreaterThanOrEqual(0);expect(pickerBox.x+pickerBox.width).toBeLessThanOrEqual(390);
    await page.screenshot({path:'test-results/work-session-date-picker-narrow.png',fullPage:true});await page.setViewportSize({width:1280,height:720});
    await page.getByLabel('Summary',{exact:true}).fill('A separate description');await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-20T08:00');await page.getByLabel('End (local time, blank while running)',{exact:true}).fill('2025-10-20T16:00');await save(page);
    session=(await (await api(request,'GET','/work-sessions?clientId='+client.id)).json()).items[0];expect(session.summary).toBe('A separate description');
    await page.getByRole('button',{name:'Calendar',exact:true}).click();await page.getByLabel('Calendar date',{exact:true}).fill('2025-10-20');
    const bar=page.locator(`.calendar-session[data-session-id="${session.id}"]`).first();await expect(bar).toHaveCSS('background-color','rgb(255, 238, 0)');await expect(bar).toHaveAttribute('title',new RegExp(prefix));
    await page.getByRole('button',{name:'Clients',exact:true}).click();let row=page.locator('#clients tr').filter({hasText:prefix});await row.getByRole('button',{name:'Edit',exact:true}).click();
    await page.getByLabel('Name',{exact:true}).fill(prefix+' renamed');await page.getByLabel('Color',{exact:true}).fill('#102030');await save(page);
    await page.getByRole('button',{name:'Calendar',exact:true}).click();await expect(bar).toHaveCSS('background-color','rgb(16, 32, 48)');await expect(bar).toHaveCSS('color','rgb(255, 255, 255)');
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Calendar',exact:true}).click();await page.getByLabel('Calendar date',{exact:true}).fill('2025-10-20');await expect(bar).toHaveCSS('background-color','rgb(16, 32, 48)');await expect(bar).toHaveAttribute('title',new RegExp(prefix+' renamed'));
    await page.screenshot({path:'test-results/client-calendar.png',fullPage:true});
    await page.getByRole('button',{name:'Clients',exact:true}).click();row=page.locator('#clients tr').filter({hasText:prefix});await row.getByRole('button',{name:'Delete',exact:true}).click();await page.locator('#save').click();await expect(page.locator('#editor-error')).toContainText('conflicts');await page.locator('#cancel').click();
    for(const [collection,values] of [['work-sessions',[session]],['projects',projects]]) for(const value of values) {const current=await api(request,'GET',`/${collection}/${value.id}`);await api(request,'DELETE',`/${collection}/${value.id}`,undefined,{'If-Match':current.headers().etag});}
    session=null;projects.length=0;
    await page.getByRole('button',{name:/^Refresh: /}).click();await expect(row).toBeVisible();await row.getByRole('button',{name:'Delete',exact:true}).click();await save(page);await expect(row).toHaveCount(0);client=null;
  } finally {
    for(const [collection,values] of [['work-sessions',session ? [session] : []],['projects',projects],['clients',client ? [client] : []]]) for(const value of values) {const current=await api(request,'GET',`/${collection}/${value.id}`);await api(request,'DELETE',`/${collection}/${value.id}`,undefined,{'If-Match':current.headers().etag});}
  }
});

test('optional work-session projects select their client and survive start, stop, manual edits and reload',async({page,request})=>{
  const prefix='Session project '+randomUUID().slice(0,6),clients=[],sessions=[];let project;
  try {
    for(const name of ['Delivery','Direct']) clients.push(await (await api(request,'POST','/clients',{name:prefix+' '+name,color:'#176653'})).json());
    project=await (await api(request,'POST','/projects',{name:prefix+' project',clientId:clients[0].id})).json();
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Start work session',exact:true}).click();await page.getByLabel('Summary',{exact:true}).fill(prefix+' running');
    await page.getByLabel('Client',{exact:true}).selectOption(clients[1].id);await page.getByLabel('Project (optional)',{exact:true}).selectOption(project.id);
    await expect(page.getByLabel('Client',{exact:true})).toHaveValue(clients[0].id);await save(page);
    const started=await (await api(request,'GET','/work-sessions/active')).json();sessions.push(started);expect(started.projectId).toBe(project.id);expect(started.clientId).toBe(clients[0].id);
    await expect(page.locator('#work-session-current')).toContainText(project.name);
    await page.getByRole('button',{name:'Stop work session',exact:true}).click();await expect(page.locator('#work-session-state')).toHaveText('Stopped');
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Edit work session',exact:true}).click();
    await expect(page.getByLabel('Project (optional)',{exact:true})).toHaveValue(project.id);
    await page.getByLabel('Client',{exact:true}).selectOption(clients[1].id);await expect(page.getByLabel('Project (optional)',{exact:true})).toHaveValue('');await save(page);
    const direct=await (await api(request,'GET','/work-sessions/'+started.id)).json();expect(direct.projectId).toBeNull();expect(direct.clientId).toBe(clients[1].id);
    await page.getByRole('button',{name:'Record work session',exact:true}).click();await page.getByLabel('Summary',{exact:true}).fill(prefix+' manual');
    await page.getByLabel('Project (optional)',{exact:true}).selectOption(project.id);await page.getByLabel('Start (local time)',{exact:true}).fill('2025-10-20T08:00');await page.getByLabel('End (local time, blank while running)',{exact:true}).fill('2025-10-20T16:00');
    await page.setViewportSize({width:390,height:844});await page.getByLabel('Project (optional)',{exact:true}).scrollIntoViewIfNeeded();
    const projectBox=await page.getByLabel('Project (optional)',{exact:true}).boundingBox();expect(projectBox.x).toBeGreaterThanOrEqual(0);expect(projectBox.x+projectBox.width).toBeLessThanOrEqual(390);
    await page.screenshot({path:'test-results/work-session-project-narrow.png',fullPage:true});await save(page);
    const all=(await (await api(request,'GET','/work-sessions?projectId='+project.id)).json()).items;sessions.push(...all);expect(all).toHaveLength(1);expect(all[0].summary).toBe(prefix+' manual');expect(all[0].clientId).toBe(clients[0].id);
  } finally {
    for(const [collection,records] of [['work-sessions',sessions],['projects',project ? [project]:[]],['clients',clients]]) for(const record of records) {
      const current=await api(request,'GET',`/${collection}/${record.id}`);await api(request,'DELETE',`/${collection}/${record.id}`,undefined,{'If-Match':current.headers().etag});
    }
  }
});

test('Projects groups clients with keyboard collapsible headings and retains groups through refresh and reassignment',async({page,request})=>{
  const prefix='Grouped '+randomUUID().slice(0,6),clients=[],projects=[];
  try {
    for(const suffix of ['Beta','Alpha'])clients.push(await (await api(request,'POST','/clients',{name:prefix+' '+suffix,color:'#6750a4'})).json());
    for(const [index,name] of [[0,'Shared project'],[0,'Another project'],[1,'Shared project']])projects.push(await (await api(request,'POST','/projects',{name,clientId:clients[index].id})).json());
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Projects',exact:true}).click();
    const beta=page.locator(`#projects tbody[data-client-id="${clients[0].id}"]`),alpha=page.locator(`#projects tbody[data-client-id="${clients[1].id}"]`);
    await expect(beta.locator('[data-project-id]')).toHaveCount(2);await expect(alpha.locator('[data-project-id]')).toHaveCount(1);
    const toggle=beta.getByRole('button',{name:'Collapse '+clients[0].name,exact:true});await toggle.focus();await page.keyboard.press('Enter');
    await expect(beta.getByRole('button',{name:'Expand '+clients[0].name,exact:true})).toBeFocused();await expect(beta.locator('[data-project-id]').first()).not.toBeVisible();await expect(alpha.locator('[data-project-id]')).toBeVisible();
    await page.getByRole('button',{name:/^Refresh: /}).click();await expect(beta.getByRole('button',{name:'Expand '+clients[0].name,exact:true})).toHaveAttribute('aria-expanded','false');
    await beta.getByRole('button',{name:'Expand '+clients[0].name,exact:true}).press('Space');await expect(beta.locator('[data-project-id]').first()).toBeVisible();
    await beta.locator(`[data-project-id="${projects[0].id}"]`).getByRole('button',{name:'Edit',exact:true}).click();await page.getByLabel('Client',{exact:true}).selectOption(clients[1].id);await save(page);
    await expect(beta.locator('[data-project-id]')).toHaveCount(1);await expect(alpha.locator('[data-project-id]')).toHaveCount(2);
    await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.locator('#projects').screenshot({path:'test-results/projects-grouped-mobile.png'});
    await alpha.getByRole('button',{name:'Collapse '+clients[1].name,exact:true}).click();await expect(alpha.locator('[data-project-id]').first()).not.toBeVisible();
  } finally {
    for(const [collection,records] of [['projects',projects],['clients',clients]])for(const record of records){const current=await api(request,'GET',`/${collection}/${record.id}`);await api(request,'DELETE',`/${collection}/${record.id}`,undefined,{'If-Match':current.headers().etag});}
  }
});

test('project icon picker searches the full catalog with bounded rendering and preserves filtered selections',async({page,request})=>{
  const prefix='Picker '+randomUUID().slice(0,6);
  const client=await(await api(request,'POST','/clients',{name:prefix,color:'#176653'})).json();let project;
  try {
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('button',{name:'New project',exact:true}).click();
    await page.getByLabel('Name',{exact:true}).fill(prefix);await page.getByLabel('Client',{exact:true}).selectOption(client.id);
    const picker=page.locator('.project-icon-picker'),choices=picker.locator('.project-icon-choices'),search=page.getByRole('searchbox',{name:'Search project icons',exact:true});
    await expect(choices.locator('input')).toHaveCount(61);
    const code=await page.getByRole('radio',{name:'Code',exact:true}).elementHandle();
    await page.getByRole('button',{name:'More icons',exact:true}).click();await expect(choices.locator('input')).toHaveCount(121);
    expect(await code.evaluate(node=>node.isConnected)).toBe(true);
    await search.fill('yin-yang');await expect(page.getByRole('radio',{name:'Yin Yang',exact:true})).toBeVisible();await expect(choices.locator('input')).toHaveCount(2);
    await page.getByRole('radio',{name:'Yin Yang',exact:true}).check();
    await search.fill('rocket');await expect(page.getByRole('radio',{name:'Rocket',exact:true})).toBeVisible();await expect(picker.locator('[name=icon]:checked')).toHaveValue('yin-yang');
    await search.fill('no such icon qzqzqz');await expect(picker.getByRole('status')).toContainText('No matching icons.');await expect(picker.getByRole('status')).toContainText('Selected:');await expect(choices.locator('input')).toHaveCount(1);
    await save(page);project=(await(await api(request,'GET','/projects?clientId='+client.id)).json()).items[0];expect(project.icon).toBe('yin-yang');
    await page.locator(`#projects [data-project-id="${project.id}"]`).getByRole('button',{name:'Edit',exact:true}).click();
    await expect(picker.locator('[name=icon]:checked')).toHaveValue('yin-yang');await expect(choices.locator('input')).toHaveCount(61);
    await search.fill('balance harmony');await expect(page.getByRole('radio',{name:'Yin Yang',exact:true})).toBeChecked();
    await search.fill('  YÍN_yang  ');await expect(page.getByRole('radio',{name:'Yin Yang',exact:true})).toBeChecked();
    await page.getByRole('radio',{name:'None',exact:true}).check();await save(page);expect((await(await api(request,'GET','/projects/'+project.id)).json()).icon).toBeNull();
  } finally {
    if(project){const current=await api(request,'GET','/projects/'+project.id);await api(request,'DELETE','/projects/'+project.id,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/clients/'+client.id);await api(request,'DELETE','/clients/'+client.id,undefined,{'If-Match':current.headers().etag});
  }
});

test('Bootstrap project icons persist after selection and reload',async({page,request})=>{
  const client=await(await api(request,'POST','/clients',{name:'Bootstrap icons '+randomUUID().slice(0,6),color:'#176653'})).json();let project;
  try {
    project=await(await api(request,'POST','/projects',{name:'Bootstrap project',clientId:client.id,icon:'phone'})).json();
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Projects',exact:true}).click();
    const row=page.locator(`#projects [data-project-id="${project.id}"]`);
    await expect(row.locator('[data-project-icon="phone"]')).toHaveClass(/bi-phone/);
    await row.getByRole('button',{name:'Edit',exact:true}).click();
    await page.getByRole('searchbox',{name:'Search project icons',exact:true}).fill('airplane-engines');
    await page.getByRole('radio',{name:'Airplane Engines',exact:true}).check();await save(page);
    expect((await(await api(request,'GET','/projects/'+project.id)).json()).icon).toBe('airplane-engines');
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Projects',exact:true}).click();
    await expect(row.locator('[data-project-icon="airplane-engines"]')).toHaveClass(/bi-airplane-engines/);
  } finally {
    if(project){const current=await api(request,'GET','/projects/'+project.id);await api(request,'DELETE','/projects/'+project.id,undefined,{'If-Match':current.headers().etag});}
    const current=await api(request,'GET','/clients/'+client.id);await api(request,'DELETE','/clients/'+client.id,undefined,{'If-Match':current.headers().etag});
  }
});

test('optional project icons persist and Track shows distinct icons to the right of daily durations',async({page,request})=>{
  const prefix='Icon '+randomUUID().slice(0,6),records=[],previous=await (await api(request,'GET','/me')).json();
  async function create(collection,data){const value=await (await api(request,'POST','/'+collection,data)).json();records.unshift([collection,value.id]);return value;}
  try {
    const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:'Europe/Oslo'},{'If-Match':profile.headers().etag});
    const client=await create('clients',{name:prefix+' client',color:'#176653'});
    await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.getByLabel('Access token').fill(token);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
    await page.getByRole('button',{name:'Projects',exact:true}).click();await page.getByRole('button',{name:'New project',exact:true}).click();
    await page.getByLabel('Name',{exact:true}).fill(prefix+' Website');await page.getByLabel('Client',{exact:true}).selectOption(client.id);
    await expect(page.getByRole('radio',{name:'None',exact:true})).toBeChecked();await page.getByRole('radio',{name:'Code',exact:true}).check();
    const search=page.getByRole('searchbox',{name:'Search project icons',exact:true});await search.fill('gavel');await page.getByRole('radio',{name:'Hammer',exact:true}).check();await search.fill('rocket');expect(await page.locator('[name=icon]:checked').inputValue()).toBe('hammer');await search.fill('');await page.getByRole('radio',{name:'Code',exact:true}).check();
    expect((await new AxeBuilder({page}).include('#editor').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await page.setViewportSize({width:1280,height:900});await page.locator('#editor').screenshot({path:'test-results/project-icon-picker.png'});await save(page);await page.setViewportSize({width:1280,height:720});
    const project=(await (await api(request,'GET','/projects?clientId='+client.id)).json()).items[0];records.unshift(['projects',project.id]);expect(project.icon).toBe('code');
    const other=await create('projects',{name:prefix+' Backend',clientId:client.id,icon:'code'}),globe=await create('projects',{name:prefix+' Research',clientId:client.id,icon:'globe'});
    for(const [index,projectId] of [project.id,project.id,other.id,globe.id,null].entries())await create('work-sessions',{clientId:client.id,projectId,summary:prefix+' session '+index,start:`2025-10-20T${String(6+index).padStart(2,'0')}:00:00Z`,end:`2025-10-20T${String(7+index).padStart(2,'0')}:00:00Z`});
    await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');await page.getByRole('button',{name:'Projects',exact:true}).click();
    const row=page.locator(`#projects [data-project-id="${project.id}"]`);await expect(row.locator('[data-project-icon=code]')).toBeVisible();await row.getByRole('button',{name:'Edit',exact:true}).click();await expect(page.getByRole('radio',{name:'Code',exact:true})).toBeChecked();
    await page.getByLabel('Name',{exact:true}).fill(prefix+' Website renamed');await save(page);
    await page.getByRole('button',{name:'Workweek',exact:true}).click();await page.getByLabel('Week containing',{exact:true}).fill('2025-10-20');await expect(page.locator(`#week-sessions [data-week-project="${project.id}"] [data-project-icon=code]`)).toBeVisible();
    await page.getByRole('button',{name:'Track',exact:true}).click();await page.getByLabel('Month',{exact:true}).fill('2025-10');
    const entry=page.locator(`[data-track-date="2025-10-20"] [data-track-daily-client="${client.id}"]`),icons=entry.locator('.track-project-icons [data-project-icon]');
    await expect(entry.locator('.track-client-day-time')).toHaveText('5h 0m');await expect(icons).toHaveCount(2);await expect(entry.locator('[data-project-icon=code]')).toHaveAttribute('title',new RegExp(other.name));await expect(entry).toHaveAttribute('title',new RegExp(globe.name));
    async function iconsFollow(duration){const clock=await duration.boundingBox();for(const icon of await icons.all()){const box=await icon.boundingBox();expect(box.x).toBeGreaterThanOrEqual(clock.x+clock.width-.5);}}
    await iconsFollow(entry.locator('.track-client-day-time'));await page.screenshot({path:'test-results/track-project-icons.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});await expect(entry.locator('.track-client-day-time-short')).toHaveText('5h');await iconsFollow(entry.locator('.track-client-day-time-short'));expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect((await new AxeBuilder({page}).include('#track-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
    await page.screenshot({path:'test-results/track-project-icons-mobile.png',fullPage:true});
    await page.getByRole('button',{name:'Projects',exact:true}).click();await row.getByRole('button',{name:'Edit',exact:true}).click();await page.getByRole('radio',{name:'None',exact:true}).check();await save(page);await expect(row.locator('[data-project-icon]')).toHaveCount(0);
    expect((await (await api(request,'GET','/projects/'+project.id)).json()).icon).toBeNull();
    const current=await api(request,'GET','/projects/'+other.id);await api(request,'PUT','/projects/'+other.id,{name:other.name,icon:null},{'If-Match':current.headers().etag});
    await page.getByRole('button',{name:'Track',exact:true}).click();await expect(icons).toHaveCount(1);await expect(entry.locator('[data-project-icon=globe]')).toBeVisible();await expect(entry.locator('.track-client-day-time-short')).toHaveText('5h');
  } finally {
    for(const [collection,id] of records){const current=await api(request,'GET',`/${collection}/${id}`);await api(request,'DELETE',`/${collection}/${id}`,undefined,{'If-Match':current.headers().etag});}
    const profile=await api(request,'GET','/me');await api(request,'PUT','/me',{zoneId:previous.zoneId},{'If-Match':profile.headers().etag});
  }
});

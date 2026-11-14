import {saveMarkdown} from './editor-interactions.js';
import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {noteEntry} from './note-entry.js';
import {tooltipFor} from './tooltips.js';
import {captureScreenshot} from './screenshots.js';

test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});

async function setup(page,request){
  const username='recent-'+randomUUID().slice(0,8),password='Private recent notes password';
  const created=await request.post('/api/v1/admin/users',{headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}});expect(created.status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const client=browserRequest(page),identity=await(await client.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await client.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),`${method} ${path}: ${reply.status()}`).toBe(true);return reply;};
  return api;
}

test('Notes right sidebar keeps Recent notes below the contents and restores its own popup focus',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const put=async(path,body)=>await(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const current=await put('Notes/Current.md','# Current\n\nCurrent body.\n'),other=await put('Notes/Other.md','# Other\n\nOther body.\n');
  await page.reload();await expect(page.locator('#today-recent-notes [data-recent-note]')).toHaveCount(2);
  await page.evaluate(()=>{window.sharedRecentSection=document.querySelector('#today-recent-notes').closest('section');});await page.locator('[data-tab=notes]').click();
  const recent=page.locator('#notes-recent-notes'),section=page.getByRole('region',{name:'Recent notes',exact:true});await expect(section).toBeVisible();await expect(recent.locator('[data-recent-note]')).toHaveCount(2);
  expect(await section.evaluate(node=>node===window.sharedRecentSection)).toBe(true);expect(await section.evaluate(node=>node.parentElement.parentElement.id==='notes-contents'&&node.parentElement.previousElementSibling.id==='notes-outline')).toBe(true);await expect(recent.locator(`[data-recent-note="${other.id}"] .today-recent-note-label`)).toHaveText('Other');
  const navigation=page.locator('#notes-navigation');await noteEntry(navigation,'Notes').click();await noteEntry(navigation,current.path).click();
  const pick=recent.locator(`[data-recent-note="${other.id}"]`);await pick.focus();await pick.press('Enter');const popup=page.locator('#note-popup-editor');await expect(popup).toBeVisible();await expect(page.locator('#notes-document-title .notes-title-input')).toHaveValue('Current');
  let refreshed=false;await page.route('**/api/v1/notes/recent?*',async route=>{await route.continue();refreshed=true;});await page.evaluate(()=>document.dispatchEvent(new Event('timekeeper:notes-changed')));await expect.poll(()=>refreshed).toBe(true);await expect(recent).toHaveAttribute('aria-busy','false');
  await popup.getByRole('button',{name:'Close',exact:true}).click();await expect(pick).toBeFocused();
  refreshed=false;await page.locator('#notes-panel').getByRole('button',{name:'Refresh',exact:true}).click();await expect.poll(()=>refreshed).toBe(true);await expect(recent).toHaveAttribute('aria-busy','false');
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#notes-body').fill('# Current\n\nPrivate unsaved draft.\n');await pick.click();await expect(popup).toBeVisible();expect(await(await api('GET','/notes/entries/'+current.id+'/content')).text()).toContain('Private unsaved draft.');
  await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(popup).toBeHidden();await expect(page.locator('#notes-document-title .notes-title-input')).toHaveValue('Other');
  await page.locator('#notes-main-actions-toggle-right-sidebar').click();await expect(section).toBeHidden();await page.locator('#notes-main-actions-toggle-right-sidebar').click();await expect(section).toBeVisible();
});

test('Today and Notes stack Recent notes below the outline with one scrollbar per right sidebar',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC'},{'If-Match':profile.headers().etag});
  const text=Array.from({length:12},(_,index)=>'#'.repeat(2+index%4)+' '+(index<2?'Section '+index:'A long heading that wraps across lines in a narrow sidebar: section '+index)+'\n\nText.\n').join('\n'),date=new Date().toISOString().slice(0,10),daily=await api('GET','/day-notes/'+date+'/content');
  await api('PUT','/day-notes/'+date+'/content',{body:text},{'If-Match':daily.headers().etag});
  for(let index=0;index<23;index++)await api('PUT','/notes/files?'+new URLSearchParams({path:'Notes/Sidebar'+String(index).padStart(2,'0')+'.md'}),Buffer.from(index===0?text:'# Sidebar note '+index+'\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.reload();await page.locator('[data-tab=notes]').click();const recent=page.locator('#notes-recent-notes');await expect(recent.locator('[data-recent-note]')).toHaveCount(20);await recent.getByRole('button',{name:'More notes',exact:true}).click();await expect(recent.locator('[data-recent-note]')).toHaveCount(23);
  const navigation=page.locator('#notes-navigation');await noteEntry(navigation,'Notes').click();await noteEntry(navigation,'Notes/Sidebar00.md').click();
  const requests=[];page.on('request',request=>{if(request.url().includes('/notes/recent?'))requests.push(request.url());});
  for(const view of ['today','notes']){
    await page.setViewportSize({width:1280,height:844});await page.locator('[data-tab='+view+']').click();const right=page.locator('#'+view+'-contents'),list=page.locator('#'+view+'-recent-notes'),outline=page.locator('#'+view+'-outline'),toggle=page.locator('#'+view+'-main-actions-toggle-right-sidebar');
    await expect(list.locator('[data-recent-note]')).toHaveCount(23);await expect(outline.locator('ol button')).toHaveCount(12);expect(requests).toEqual([]);
    await expect(page.locator(view==='today'?'.today-sidebar .recent-notes':'#notes-sidebar .recent-notes')).toHaveCount(0);
    for(const width of [1280,390,320]){
      await page.setViewportSize({width,height:844});if(width===390)await expect(toggle).toHaveAttribute('aria-expanded','false');if(await toggle.getAttribute('aria-expanded')==='false')await toggle.click();
      for(const theme of ['timekeeper','timekeeper-dark']){
        await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);
        await expect(right).toBeVisible();const rows=await outline.locator('ol button').evaluateAll(buttons=>buttons.map(button=>{const {top,bottom,height}=button.getBoundingClientRect();return {top,bottom,height,clipped:button.scrollWidth>button.clientWidth+1};}));expect(rows[0].height).toBeGreaterThanOrEqual(24);expect(rows[1].top-rows[0].top).toBeLessThanOrEqual(24.1);expect(rows.some(row=>row.height>24)).toBe(true);for(const [index,row]of rows.entries()){expect(row.clipped).toBe(false);if(index)expect(Math.abs(row.top-rows[index-1].bottom)).toBeLessThanOrEqual(.1);}await expect.poll(()=>right.evaluate(root=>[root,...root.querySelectorAll('*')].filter(node=>node.clientHeight>0&&node.scrollHeight>node.clientHeight+1&&['auto','scroll'].includes(getComputedStyle(node).overflowY)).map(node=>node.id))).toEqual([view+'-contents']);
        const outlineBox=await outline.boundingBox(),recentBox=await list.boundingBox();expect(recentBox.y).toBeGreaterThanOrEqual(outlineBox.y+outlineBox.height);
        const last=list.locator('[data-recent-note]').last();await last.focus();await expect(last).toBeInViewport();expect(await right.evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
        await outline.locator('button').first().focus();await expect(outline.locator('button').first()).toBeInViewport();
        expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
        expect((await new AxeBuilder({page}).include('#'+view+'-contents').analyze()).violations).toEqual([]);
        await list.locator('[data-recent-note]').first().scrollIntoViewIfNeeded();await captureScreenshot(page,{path:`test-results/${view}-recent-${theme}-${width}.png`,fullPage:false});
      }
      await toggle.click();await expect(list).toBeHidden();await toggle.click();await expect(list).toBeVisible();
    }
  }
  await recent.locator('[data-recent-note]').last().focus();await page.keyboard.press('Enter');const popup=page.locator('#note-popup-editor');await expect(popup).toBeVisible();await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(popup).toBeHidden();await expect(page.locator('#notes-contents')).toBeHidden();await expect(page.locator('#notes-formatted .ProseMirror')).toBeFocused();
});

test('a loading note popup keeps keyboard actions outside its unloaded editor',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const entry=await(await api('PUT','/notes/files?path=Notes%2FLoading.md',Buffer.from('# Loading note\n\nOriginal body.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.reload();let held=false,release;const pending=new Promise(resolve=>{release=resolve;});
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',async route=>{if(route.request().method()==='GET'){held=true;await pending;}await route.continue();});
  try{
    await page.locator(`#today-recent-notes [data-recent-note="${entry.id}"]`).click();const popup=page.locator('#note-popup-editor'),close=popup.getByRole('button',{name:'Close',exact:true});await expect(popup).toBeVisible();await expect.poll(()=>held).toBe(true);
    await close.focus();await expect(close).toBeFocused();await page.keyboard.press('Tab');expect(await popup.locator('.journal-document-content').evaluate(node=>node.contains(document.activeElement))).toBe(false);
    release();await expect(popup.locator('#note-popup-formatted .ProseMirror')).toBeVisible();await popup.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#note-popup-body').fill('# Chosen source\n\nLoaded edit.\n');await saveMarkdown(popup,{close:true});
    expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('# Chosen source\n\nLoaded edit.\n');
  }finally{release();await page.unrouteAll({behavior:'wait'});}
});

test('Recent notes uses account days, includes journals and protects drafts when opening notes',async({page,request})=>{
  const api=await setup(page,request);
  const profile=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC',locale:'en-GB'},{'If-Match':profile.headers().etag});
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const now=Date.now(),today=new Date(now).toISOString().slice(0,10),ago=days=>new Date(now-days*86400000).toISOString();
  const upload=async(path,modifiedAt=ago(0))=>(await api('PUT','/notes/files?'+new URLSearchParams({path,modifiedAt}),Buffer.from('Body of '+path),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const first=await upload('Notes/Current00.md');for(let n=1;n<21;n++)await upload(`Notes/Current${String(n).padStart(2,'0')}.md`);
  await upload('Notes/Five days ago.md',ago(5));await upload('Notes/Too old.md',ago(10));const todayJournal=await upload('journal/'+today+'.md');await upload('journal/Private.md');
  await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  const recent=page.locator('#today-recent-notes');await expect(recent.locator('[data-recent-note]')).toHaveCount(20);
  expect(await recent.evaluate(node=>node.parentElement.parentElement.id)).toBe('today-contents');
  await recent.getByRole('button',{name:'More notes',exact:true}).click();await expect(recent.locator('[data-recent-note]')).toHaveCount(23);await expect(recent).not.toContainText('Too old');await expect(recent).toContainText('Private');await expect(recent.locator(`[data-recent-note="${todayJournal.id}"]`)).toHaveCount(0);await expect(recent.locator('.hint')).toHaveCount(0);
  await page.locator('#today-date').fill('2020-01-02');await expect(page.locator('#today-title')).toContainText('2020');await expect(recent.locator('[data-recent-note]')).toHaveCount(23);
  expect((await new AxeBuilder({page}).include('.today-sidebar').analyze()).violations).toEqual([]);
  await page.locator('#today-journal').getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#today-journal-body').fill('Keep my unsaved Today draft');
  await recent.locator(`[data-recent-note="${first.id}"]`).click();await expect(page.locator('#note-popup-title')).toHaveText('Current00');await expect(page.locator('#today-panel')).toBeVisible();await page.locator('#note-popup-editor').getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(page.locator('#notes-document-title')).toHaveText('Current00');expect((await(await api('GET','/day-entries/2020-01-02')).json()).body).toBe('Keep my unsaved Today draft');
  await page.evaluate(()=>location.hash='#account/notes');const settings=page.locator('#account-content section').filter({has:page.getByRole('heading',{name:'Notes settings',exact:true})});
  const days=settings.getByLabel('Recent notes: number of days',{exact:true});await expect(days).toHaveValue('7');await days.fill('1');await settings.getByRole('button',{name:'Save',exact:true}).click();await expect(settings).toContainText('Notes settings saved.');
  await page.getByRole('link',{name:'Back to time tracking',exact:true}).click();await page.locator('[data-tab=today]').click();await expect(recent.locator('[data-recent-note]')).toHaveCount(20);await recent.getByRole('button',{name:'More notes',exact:true}).click();await expect(recent.locator('[data-recent-note]')).toHaveCount(23);await expect(recent).not.toContainText('Five days ago');
  for(const width of [1440,390,320]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
});

test('Recent notes identifies ordinary, event and daily or weekly journal notes accessibly in both languages',async({page,request})=>{
  const api=await setup(page,request),profile=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC',locale:'en-GB'},{'If-Match':profile.headers().etag});
  await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const upload=async(path,text)=>(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const ordinary=await upload('Notes/document.md','# Project review\n\nOrdinary body\n');
  const day=await upload('journal/2020-01-02.md','Daily journal body\n'),week=await upload('journal/2020-W01.md','Weekly journal body\n');
  const subscription=randomUUID(),eventKey='ab'.repeat(32),eventPath=`events/event-${subscription}-${eventKey}.md`;
  const event=await upload(eventPath,'---\ntimekeeper-event: '+JSON.stringify({subscriptionId:subscription,eventKey,event:{uid:'recent-types',recurrenceId:'',title:'Team planning'}})+'\n---\n\nEvent body\n');
  await page.reload();const recent=page.locator('#today-recent-notes');await expect(recent.locator('[data-recent-note]')).toHaveCount(4);
  const cases=[[ordinary,'file-earmark-text','Ordinary note','Vanlig notat','document'],[event,'calendar-event','Event note','Hendelsesnotat','Team planning'],[day,'journal-text','Journal note','Dagboknotat','2020-01-02'],[week,'journal-text','Journal note','Dagboknotat','2020-W01']];
  for(const [entry,glyph,label,,title] of cases){
    const button=recent.locator(`[data-recent-note="${entry.id}"]`);await expect(button).toHaveAccessibleName(new RegExp('^'+label+': '));await expect(button.locator(`.icon.bi-${glyph}`)).toHaveAttribute('aria-hidden','true');
    await expect(button.locator('.today-recent-note-label')).toHaveText(title.split('/').at(-1));await button.click();await expect(page.locator('#note-popup-title')).toHaveText(title);await page.locator('#note-popup-editor').getByRole('button',{name:'Open in workspace',exact:true}).click();if(entry.kind==='DAY'){await expect(page.locator('#today-date')).toHaveValue(entry.sourceKey);await expect(page.locator('#today-journal-formatted')).toContainText('Daily journal body');}else await expect(page.locator('#notes-document-title')).toHaveText(title);await page.locator('[data-tab=today]').click();
  }
  for(const [width,height] of [[1440,1000],[320,900]]){
    await page.setViewportSize({width,height});const sidebar=page.locator('#today-main-actions-toggle-right-sidebar');if(width===320)await expect(sidebar).toHaveAttribute('aria-expanded','false');if(await sidebar.getAttribute('aria-expanded')==='false')await sidebar.click();await page.evaluate(()=>document.fonts.load('1em bootstrap-icons'));
    for(const theme of ['timekeeper','timekeeper-dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
      for(const [entry] of cases){
        const button=recent.locator(`[data-recent-note="${entry.id}"]`);await button.scrollIntoViewIfNeeded();
        const bounds=await button.evaluate(node=>{const icon=node.querySelector('.icon'),title=node.querySelector('.today-recent-note-label'),a=icon.getBoundingClientRect(),b=title.getBoundingClientRect(),style=getComputedStyle(icon,'::before'),button=getComputedStyle(node);return {before:a.right<=b.left,visible:a.width>0&&a.height>0,font:style.fontFamily,glyph:style.content,borders:[button.borderTopWidth,button.borderRightWidth,button.borderBottomWidth,button.borderLeftWidth],fontSize:parseFloat(button.fontSize),rem:parseFloat(getComputedStyle(document.documentElement).fontSize)};});
        expect(bounds.before&&bounds.visible,`${theme}, ${width}px`).toBe(true);expect(bounds.font).toContain('bootstrap-icons');expect(bounds.glyph).not.toBe('none');
        expect(bounds.borders).toEqual(['0px','0px','0px','0px']);expect(bounds.fontSize).toBeCloseTo(bounds.rem*.875,2);await expect(button.locator('.hint')).toHaveCount(0);
      }
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
      expect((await new AxeBuilder({page}).include('#today-recent-notes').analyze()).violations,`${theme}, ${width}px`).toEqual([]);
    }
    await recent.scrollIntoViewIfNeeded();await recent.screenshot({path:`test-results/recent-note-types-${width}.png`});
  }
  await page.evaluate(()=>document.documentElement.style.setProperty('--font-today-recent-note','1.1rem'));
  expect(await recent.locator('[data-recent-note]').first().evaluate(node=>parseFloat(getComputedStyle(node).fontSize)/parseFloat(getComputedStyle(document.documentElement).fontSize))).toBeCloseTo(1.1,2);
  await page.evaluate(()=>document.documentElement.style.removeProperty('--font-today-recent-note'));
  const current=await api('GET','/me');await api('PUT','/me',{zoneId:'UTC',locale:'nb-NO'},{'If-Match':current.headers().etag});await page.reload();
  // The first account snapshot resets sidebar state. Interact after that reset,
  // rather than mistaking the constructor's collapsed state for readiness.
  await expect(page.locator('#status')).toHaveAttribute('data-state','ready');await expect(page.locator('#workspace')).toHaveAttribute('aria-busy','false');
  const sidebar=page.locator('#today-main-actions-toggle-right-sidebar');await expect(sidebar).toHaveAttribute('aria-expanded','false');await sidebar.click();await expect(sidebar).toHaveAttribute('aria-expanded','true');await expect(recent).toBeVisible();
  for(const [entry,,,label] of cases)await expect(recent.locator(`[data-recent-note="${entry.id}"]`)).toHaveAccessibleName(new RegExp('^'+label+': '));
});

test('note views use filename and event titles while headings and Markdown stay intact',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const upload=async(path,text)=>(await api('PUT','/notes/files?'+new URLSearchParams({path}),Buffer.from(text),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  const ordinaryText='---\ntitle: Metadata is not the headline\n---\n\n```markdown\n# Example only\n```\n\n## Project **review**\n\nPrivate body\n';
  const prefix=ordinaryText.slice(0,ordinaryText.indexOf('\n---\n')+5);
  const ordinary=await upload('Notes/document.md',ordinaryText),plain=await upload('Notes/plain.md','Plain body without a heading.');
  const subscription=randomUUID(),eventKey='cd'.repeat(32),title='Planning <img src=x onerror="window.noteTitleExecuted=true"> & review';
  const eventPath='events/event-'+subscription+'-'+eventKey+'.md',eventText='---\ntimekeeper-event: '+JSON.stringify({subscriptionId:subscription,eventKey,event:{uid:'private-title-test',recurrenceId:'',title}})+'\n---\n# Different note heading\n\nDecisions\n';
  const event=await upload(eventPath,eventText);await page.reload();await expect(page.locator('#status')).toHaveText('Up to date');
  const recent=page.locator('#today-recent-notes');
  await expect(recent.locator(`[data-recent-note="${ordinary.id}"] .today-recent-note-label`)).toHaveText('document');await expect(recent.locator(`[data-recent-note="${plain.id}"] .today-recent-note-label`)).toHaveText('plain');await expect(recent.locator(`[data-recent-note="${event.id}"] .today-recent-note-label`)).toHaveText(title);
  const eventTooltip=await tooltipFor(recent.locator(`[data-recent-note="${event.id}"]`));await expect(eventTooltip).toContainText(title);await expect(eventTooltip).not.toContainText(eventKey);await expect(eventTooltip.locator('img')).toHaveCount(0);await page.keyboard.press('Escape');
  await expect(recent.locator('img')).toHaveCount(0);expect(await page.evaluate(()=>window.noteTitleExecuted)).toBeUndefined();
  await recent.locator(`[data-recent-note="${event.id}"]`).click();await expect(page.locator('#note-popup-title')).toHaveText(title);await page.locator('#note-popup-editor').getByRole('button',{name:'Open in workspace',exact:true}).click();await expect(page.locator('#notes-document-title')).toHaveText(title);const navigation=page.locator('#notes-navigation');await navigation.getByRole('button',{name:'events',exact:true}).click();await expect(noteEntry(navigation,eventPath)).toHaveText(title);const navigationTooltip=await tooltipFor(noteEntry(navigation,eventPath));await expect(navigationTooltip).toHaveText(title);await expect(navigationTooltip).not.toContainText(eventKey);await page.keyboard.press('Escape');
  await navigation.getByRole('button',{name:'Notes',exact:true}).click();await expect(noteEntry(navigation,ordinary.path)).toHaveText('document');await noteEntry(navigation,ordinary.path).click();await expect(page.locator('#notes-document-title')).toHaveText('document');
  await page.locator('#notes-panel').getByRole('button',{name:'Markdown source',exact:true}).click();await expect(page.locator('#notes-body')).toHaveValue(ordinaryText.slice(prefix.length));
  const updated='# Updated review\n\nThe filename stays document.md.\n';await page.locator('#notes-body').fill(updated);await saveMarkdown(page.locator('#notes-panel'));await expect(page.locator('#notes-document-title')).toHaveText('document');await expect(noteEntry(navigation,ordinary.path)).toHaveText('document');
  await noteEntry(navigation,plain.path).click();await expect(page.locator('#notes-document-title')).toHaveText('plain');
  await page.locator('[data-tab=today]').click();await expect(recent.locator(`[data-recent-note="${ordinary.id}"] .today-recent-note-label`)).toHaveText('document');
  expect(await(await api('GET','/notes/entries/'+ordinary.id+'/content')).text()).toBe(prefix+updated);expect(await(await api('GET','/notes/entries/'+event.id+'/content')).text()).toBe(eventText);
  expect((await new AxeBuilder({page}).include('.today-sidebar').analyze()).violations).toEqual([]);
  await page.setViewportSize({width:1440,height:900});await captureScreenshot(page,{path:'test-results/note-display-titles.png',fullPage:false});
});

test('Recent notes ignores stale account responses and refreshes after local midnight',async({page})=>{
  await page.clock.install({time:new Date('2026-11-05T12:00:00Z')});await page.goto('/web/');
  await page.evaluate(async()=>{
    const {RecentNotes}=await import('/web/recent-notes.js'),root=document.createElement('div');root.id='recent-lifecycle-test';document.body.append(root);
    const entry=path=>({id:path,path,modifiedAt:'2026-11-05T12:00:00Z'});
    let oldReply;const old={request:()=>new Promise(resolve=>{oldReply=resolve;})};
    let calls=0;const current={request:async()=>{calls++;return {data:{snapshot:'current',items:[entry('Current owner.md')]}};}};
    const list=new RecentNotes(root,{onUnauthorized:()=>{throw new Error('Unexpected authorization failure');}});
    list.update(old,'UTC',1);list.show();list.update(current,'UTC',1);
    await new Promise(resolve=>queueMicrotask(resolve));oldReply({data:{snapshot:'old',items:[entry('Other account secret.md')]}});
    await new Promise(resolve=>queueMicrotask(resolve));
    window.recentLifecycle={list,calls:()=>calls};
  });
  const root=page.locator('#recent-lifecycle-test');await expect(root).toContainText('Current owner');await expect(root).not.toContainText('Other account secret');
  expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(1);
  await page.evaluate(()=>window.recentLifecycle.list.refresh());expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(1);
  await page.clock.setFixedTime(new Date('2026-11-06T00:00:00Z'));await page.evaluate(()=>window.recentLifecycle.list.refresh());expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(2);
  await page.evaluate(()=>{const {list}=window.recentLifecycle;list.hide();list.refresh(true);});expect(await page.evaluate(()=>window.recentLifecycle.calls())).toBe(2);
  await page.evaluate(()=>window.recentLifecycle.list.reset());await expect(root).toBeEmpty();
});

test('Recent note popup saves before maximizing and retains drafts after a rejected save',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const body='# Popup note\n\nOriginal body.\n',createdAt='2025-01-02T10:00:00Z';
  const entry=await(await api('PUT','/notes/files?'+new URLSearchParams({path:'Notes/Popup.md',createdAt}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.reload();const pick=page.locator(`#today-recent-notes [data-recent-note="${entry.id}"]`);await expect(pick).toBeVisible();expect(await pick.locator('.today-recent-note-label').evaluate(node=>Number(getComputedStyle(node).fontWeight))).toBeLessThan(600);await pick.click();
  const popup=page.locator('#note-popup-editor');await expect(popup).toBeVisible();await expect(popup.getByRole('button',{name:'Open in workspace',exact:true}).locator('.bi-arrows-fullscreen')).toHaveCount(1);
  await popup.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#note-popup-body').fill('# Popup note\n\nEdited in popup.\n');await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();
  await expect(popup).not.toBeVisible();await expect(page.locator('#notes-panel')).toBeVisible();await expect(page.locator('#notes-formatted')).toContainText('Edited in popup.');await expect(page.locator('#notes-formatted .ProseMirror')).toBeFocused();expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('# Popup note\n\nEdited in popup.\n');expect((await(await api('GET','/notes/entries/'+entry.id)).json()).createdAt).toBe(createdAt);
  await page.locator('[data-tab=today]').click();await pick.click();await popup.getByRole('button',{name:'Markdown source',exact:true}).click();
  await page.route('**/api/v1/notes/entries/'+entry.id+'/content',route=>route.request().method()==='PUT'?route.fulfill({status:412,contentType:'application/json',body:JSON.stringify({code:'STALE_VERSION'})}):route.continue());
  await page.locator('#note-popup-body').fill('Draft retained after conflict');await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();
  await expect(popup).toBeVisible();await expect(page.locator('#note-popup-body')).toHaveValue('Draft retained after conflict');await expect(popup.locator(':scope > [role=alert]')).toBeVisible();await expect(page.locator('#today-panel')).toBeVisible();
  expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe('# Popup note\n\nEdited in popup.\n');
  await page.unrouteAll({behavior:'wait'});page.once('dialog',dialog=>dialog.accept());await popup.getByRole('button',{name:'Reload saved note',exact:true}).click();await popup.getByRole('button',{name:'Close',exact:true}).click();await expect(pick).toBeFocused();
});

test('Recent note popup remains accessible in all themes and narrow screens',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const entry=await(await api('PUT','/notes/files?path=Notes%2FA%20note%20with%20a%20long%20title%20that%20must%20wrap%20beside%20the%20window%20buttons.md',Buffer.from('# A note with a long title that must wrap beside the window buttons\n\nEditable body.\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.reload();await page.locator(`#today-recent-notes [data-recent-note="${entry.id}"]`).click();const popup=page.locator('#note-popup-editor');await expect(page.locator('#note-popup-formatted .ProseMirror')).toBeVisible();
  for(const width of [1440,390,320]){
    await page.setViewportSize({width,height:900});await expect(popup.getByRole('button',{name:'Open in workspace',exact:true})).toBeInViewport();await expect(popup.getByRole('button',{name:'Close',exact:true})).toBeInViewport();
    expect(await popup.evaluate(node=>node.scrollWidth<=node.clientWidth)).toBe(true);
    for(const theme of ['timekeeper','timekeeper-dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=(theme==='timekeeper')?'light':'dark';},theme);
      expect((await new AxeBuilder({page}).include('#note-popup-editor').analyze()).violations,theme+', '+width).toEqual([]);
    }
    await popup.screenshot({path:`test-results/note-popup-${width}.png`});
  }
  await popup.getByRole('button',{name:'Close',exact:true}).click();
});

test('journal popups retain custom asset paths and maximize saved daily and weekly edits',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');const settings=await api('GET','/notes/settings');await api('PUT','/notes/settings',{assetsFolder:'Images'},{'If-Match':settings.headers().etag});
  await api('PUT','/journal-assets/pixel.png',{data:'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9o9u4AAAAASUVORK5CYII='},{'If-None-Match':'*'});
  const day='2020-01-02',week='2020-W01',body='![Legacy](assets/pixel.png)\n\nOriginal day.\n';
  const daily=await(await api('PUT','/notes/files?'+new URLSearchParams({path:'journal/'+day+'.md'}),Buffer.from(body),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await api('PUT','/notes/files?'+new URLSearchParams({path:'journal/'+week+'.md'}),Buffer.from('# Weekly\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'});
  await page.reload();await page.locator(`#today-recent-notes [data-recent-note="${daily.id}"]`).click();const popup=page.locator('#note-popup-editor');await expect.poll(()=>popup.locator('img[alt="Legacy"]').evaluate(node=>node.naturalWidth)).toBe(1);
  await popup.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#note-popup-body').fill(body+'Changed from recent popup.\n');await popup.getByRole('button',{name:'Open in workspace',exact:true}).click();
  await expect(page.locator('#today-date')).toHaveValue(day);await expect(page.locator('#today-journal-formatted')).toContainText('Changed from recent popup.');
  await page.locator(`#today-panel [data-week="${week}"]`).click();const weekly=page.locator('#today-week-note-editor');await weekly.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#weekly-journal-body').fill('# Weekly\n\nChanged from weekly popup.\n');await weekly.getByRole('button',{name:'Open in workspace',exact:true}).click();
  await expect(weekly).not.toBeVisible();await expect(page.locator('#notes-panel')).toBeVisible();await expect(page.locator('#notes-formatted')).toContainText('Changed from weekly popup.');expect((await(await api('GET','/week-notes/'+week)).json()).body).toContain('Changed from weekly popup.');
  await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2020-01');await page.locator(`[data-track-note-date="${day}"]`).click();const nativeDay=page.locator('#track-note-editor');await nativeDay.getByRole('button',{name:'Markdown source',exact:true}).click();await page.locator('#journal-body').fill(body+'Changed from daily popup.\n');await nativeDay.getByRole('button',{name:'Open in workspace',exact:true}).click();
  await expect(nativeDay).not.toBeVisible();await expect(page.locator('#today-date')).toHaveValue(day);await expect(page.locator('#today-journal-formatted')).toContainText('Changed from daily popup.');
});

test('note popup clears the previous account title and body when authentication ends',async({page,request})=>{
  const api=await setup(page,request);await api('GET','/notes');await api('POST','/notes/folders',{path:'Notes'});
  const title='Private account headline 607',marker='Private account body marker 607';
  const entry=await(await api('PUT','/notes/files?path=Notes%2FPrivate.md',Buffer.from('# '+title+'\n\n'+marker+'\n'),{'Content-Type':'application/octet-stream','If-None-Match':'*'})).json();
  await page.reload();await page.locator(`#today-recent-notes [data-recent-note="${entry.id}"]`).click();await expect(page.locator('#note-popup-title')).toHaveText('Private');await expect(page.locator('#note-popup-formatted')).toContainText(marker);
  await api('POST','/auth/logout');await expect(page.locator('#workspace')).toBeHidden();await expect(page.locator('#note-popup-editor')).not.toBeVisible();await expect(page.locator('#note-popup-title')).toHaveText('');await expect(page.locator('#note-popup-body')).toHaveValue('');expect(await page.locator('#note-popup-editor').textContent()).not.toContain(title);expect(await page.locator('#note-popup-editor').textContent()).not.toContain(marker);
});

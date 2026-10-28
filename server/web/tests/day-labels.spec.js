import {test,expect} from '@playwright/test';
const date='2026-05-17',label='Keep this day label';
for(const view of ['today','track','week'])for(const mode of ['Formatted','Markdown source'])test(`independent day labels: ${view} ${mode} clear body preserves label`,async({page,request})=>{
 const headers={Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`};
 const read=()=>request.get('/api/v1/day-notes/'+date,{headers});
 let current=await read();expect(current.ok()).toBe(true);
 const saved=await request.put('/api/v1/day-notes/'+date,{headers:{...headers,'If-Match':current.headers().etag},data:{text:label,body:'# Body to clear\n\nParagraph.\n'}});expect(saved.ok()).toBe(true);
 await page.goto('/web/');await page.locator('#auth-mode').selectOption('token');await page.locator('#token').fill(process.env.TIMEKEEPER_WEB_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.locator('#status')).toHaveText('Up to date');
 if(view==='today')await page.locator('#today-date').fill(date);
 else if(view==='week'){await page.locator('[data-tab=calendar]').click();await page.locator('#calendar-date').fill(date);await page.locator('[data-week-note-date="'+date+'"]').click();}
 else{await page.locator('[data-tab=track]').click();await page.locator('#track-month').fill('2026-05');await expect(page.locator('[data-track-note-date="'+date+'"]')).toBeEnabled();await page.locator('[data-track-note-date="'+date+'"]').click();}
 const editor=page.locator('#'+view+'-note-editor'),field=page.locator('#'+view+'-note-text');await expect(field).toHaveValue(label);
 await editor.getByRole('button',{name:mode,exact:true}).click();
 const body=mode==='Formatted'?editor.locator('.ProseMirror'):editor.locator('textarea');await expect(body).toBeVisible();await body.click();await page.keyboard.press('ControlOrMeta+A');await page.keyboard.press('Backspace');
 await expect(field).toHaveValue(label);
 const sent=page.waitForRequest(r=>r.method()==='POST'&&r.url().endsWith('/day-entries/'+date));await editor.getByRole('button',{name:'Save',exact:true}).click();const input=(await sent).postDataJSON();expect(input).not.toHaveProperty('text');expect(input).not.toHaveProperty('labelVersion');expect(input).toHaveProperty('noteVersion');expect(input.body).toBe('');
 await expect.poll(async()=>{const response=await read();return(await response.json()).body;}).toBe('');
 current=await read();const note=await current.json();expect(note.deleted).toBe(false);expect(note.body).toBe('');
 const list=await request.get('/api/v1/day-notes?fromDate='+date+'&toDate='+date,{headers});expect((await list.json()).items[0].text).toBe(label);
 if(view==='track')await page.locator('[data-track-note-date="'+date+'"]').click();
 if(view==='week')await page.locator('[data-week-note-date="'+date+'"]').click();
 await expect(field).toHaveValue(label);await field.fill('Unsaved label draft');
 page.once('dialog',prompt=>prompt.accept());await editor.getByRole('button',{name:'Delete note',exact:true}).click();
 await expect(editor).toBeVisible();await expect(field).toHaveValue('Unsaved label draft');
 await expect.poll(async()=>{const response=await read();return(await response.json()).deleted;}).toBe(true);
 current=await read();const deleted=await current.json();expect(deleted.text).toBe(label);
 await editor.getByRole('button',{name:'Save',exact:true}).click();
 await expect.poll(async()=>{const response=await read();return(await response.json()).text;}).toBe('Unsaved label draft');
 const after=await(await read()).json();expect(after.deleted).toBe(true);expect(after.version).toBe(deleted.version);

});

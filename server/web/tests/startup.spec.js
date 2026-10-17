import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {browserRequest} from './browser-request.js';

test('cached modules restore a browser session after reload and opening another tab',async({page,request})=>{
  const username='startup-'+randomUUID().slice(0,8),password='Disposable browser startup passphrase';
  const created=await request.post('/api/v1/auth/users',{data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false},headers:{Authorization:`Bearer ${process.env.TIMEKEEPER_WEB_TOKEN}`}});
  expect(created.status()).toBe(201);
  const diagnostics=[];
  function observe(subject) {
    subject.on('pageerror',error=>diagnostics.push({error:error.message}));
    subject.on('requestfailed',request=>diagnostics.push({path:new URL(request.url()).pathname,error:request.failure()?.errorText}));
    subject.on('response',response=>{
      if(response.request().resourceType()==='script') diagnostics.push({path:new URL(response.url()).pathname,status:response.status(),type:response.headers()['content-type']});
    });
  }
  observe(page);
  await browserRequest(page).get('/api/v1/auth/status');
  expect((await browserRequest(page).post('/api/v1/auth/login',{data:{username,password,remember:true}})).status()).toBe(200);
  for(let iteration=0;iteration<12;iteration++) {
    if(iteration===0) await page.goto('/web/');else await page.reload();
    await expect(page.locator('#status'),JSON.stringify({iteration,diagnostics})).toHaveText('Up to date');
    await expect(page.locator('#owner')).toHaveText(username);
    expect(diagnostics.filter(event=>event.error),JSON.stringify(diagnostics)).toEqual([]);
  }
  const second=await page.context().newPage();observe(second);
  try {
    await second.goto('/web/');await expect(second.locator('#status'),JSON.stringify(diagnostics)).toHaveText('Up to date');
    await expect(second.locator('#owner')).toHaveText(username);
  } finally {await second.close();}
});

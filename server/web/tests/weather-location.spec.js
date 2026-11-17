import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';
import {browserRequest} from './browser-request.js';
import {openAccountMenu} from './navigation.js';
import {captureScreenshot} from './screenshots.js';

const places=[{name:'Trondheim, Trøndelag, Norge',latitude:63.4304,longitude:10.3952},{name:'Trondheim S, Norge',latitude:63.4366,longitude:10.4}];
test.beforeAll(async({request})=>{
  const headers={Origin:new URL(process.env.TIMEKEEPER_WEB_URL).origin},status=await request.get('/api/v1/auth/status',{headers});
  if((await status.json()).setupRequired){const reply=await request.post('/api/v1/auth/setup',{headers,data:{code:process.env.TIMEKEEPER_WEB_SETUP_CODE,username:'browser-admin',password:process.env.TIMEKEEPER_WEB_PASSWORD,displayName:'browser-admin',email:'browser-admin@example.invalid'}});expect(reply.status()).toBe(200);await request.post('/api/v1/auth/logout',{headers:{...headers,'X-CSRF-Token':(await reply.json()).csrf}});}
});
async function fresh(page,request){
  const username='weather-'+randomUUID().slice(0,8),password='Synthetic weather account passphrase';
  expect((await request.post('/api/v1/admin/users',{headers:{Authorization:'Bearer '+process.env.TIMEKEEPER_WEB_TOKEN},data:{username,password,displayName:username,email:username+'@example.invalid',administrator:false}})).status()).toBe(201);
  await page.goto('/web/');await page.locator('#auth-mode').selectOption('login');await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.locator('#login-form button[type=submit]').click();await expect(page.locator('#status')).toHaveText('Up to date');
  const http=browserRequest(page),identity=await(await http.get('/api/v1/auth/session')).json();
  const api=async(method,path,data,headers={})=>{const reply=await http.fetch('/api/v1'+path,{method,data,headers:{'X-CSRF-Token':identity.csrf,...headers}});expect(reply.ok(),method+' '+path+' '+reply.status()).toBe(true);return reply;};
  const owner=(await api('GET','/me')).headers()['x-timekeeper-owner'];
  await page.route('**/api/v1/daily-banners/*/preview',route=>route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{version:1,date:new URL(route.request().url()).pathname.split('/').at(-2),zoneId:'UTC',season:'late-autumn',weather:'cloudy',forecast:[]}}));
  return {api,owner};
}
async function profile(page){await openAccountMenu(page);await page.getByRole('link',{name:'My Account',exact:true}).click();await expect(page.getByRole('form',{name:'Search for a weather location',exact:true})).toBeVisible();}
const card=page=>page.locator('.account-card').filter({has:page.getByRole('heading',{name:'Weather location',exact:true})});
const search=page=>page.getByRole('form',{name:'Search for a weather location',exact:true});
async function lookup(page,query='Trondheim'){await search(page).getByLabel('Place name',{exact:true}).fill(query);await search(page).getByRole('button',{name:'Search places',exact:true}).click();}

test('place search stages coordinates and preserves saved forecasts until an explicit profile save',async({page,request})=>{
  const {api,owner}=await fresh(page,request);await api('GET','/notes');const snapshot={version:1,date:'2026-11-16',zoneId:'Europe/Oslo',season:'late-autumn',seasonal:{reference:'Images/Original.png',x:.25,y:.75,id:'late-autumn-snow',version:1},forecast:[9,12,15].map(hour=>({time:String(hour).padStart(2,'0')+':00',condition:'snow',label:'Snow',temperature:'−2 °C',temperatureCelsius:-2}))},source='---\nbanner: "![[Images/Original.png]]"\ndagsverk_seasonal_banner: '+JSON.stringify(JSON.stringify(snapshot))+'\ncustom: retain\n---\nSaved forecast prose.\n';
  const entry=await(await api('PUT','/notes/files?path=journal%2F2026-11-16.md',Buffer.from(source),{'If-None-Match':'*','Content-Type':'application/octet-stream'})).json();
  const queries=[];await page.route('**/api/v1/daily-banners/locations?**',route=>{const url=new URL(route.request().url());queries.push(Object.fromEntries(url.searchParams));return route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{items:places}});});
  await profile(page);let writes=0;page.on('request',r=>{if(r.method()==='PUT'&&new URL(r.url()).pathname==='/api/v1/me')writes++;});
  await card(page).getByLabel('Location name (optional)',{exact:true}).fill('Manual draft');await card(page).getByLabel('Latitude',{exact:true}).fill('1');await card(page).getByLabel('Longitude',{exact:true}).fill('2');await lookup(page);
  await expect(search(page).getByRole('button',{name:'Use '+places[0].name,exact:true})).toBeVisible();expect(queries).toEqual([{q:'Trondheim',language:'en'}]);await expect(card(page).getByLabel('Latitude',{exact:true})).toHaveValue('1');expect(writes).toBe(0);expect((await(await api('GET','/me')).json()).weatherLocation).toBeNull();
  for(const width of [1200,320])for(const theme of ['timekeeper','timekeeper-dark']){await page.setViewportSize({width,height:900});await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.documentElement.dataset.bsTheme=theme==='timekeeper'?'light':'dark';},theme);expect((await new AxeBuilder({page}).include('.weather-location-search').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:`test-results/weather-search-${theme}-${width}.png`});}
  await search(page).getByRole('button',{name:'Use '+places[0].name,exact:true}).press('Enter');await expect(card(page).getByLabel('Latitude',{exact:true})).toHaveValue('63.4304');await expect(card(page).getByLabel('Longitude',{exact:true})).toHaveValue('10.3952');expect(writes).toBe(0);
  await card(page).getByRole('button',{name:'Save',exact:true}).click();await expect(card(page)).toContainText('Weather location saved.');expect(writes).toBe(1);expect((await(await api('GET','/me')).json()).weatherLocation).toEqual(places[0]);expect(await(await api('GET','/notes/entries/'+entry.id+'/content')).text()).toBe(source);
});

test('failed, empty and stale searches retain the manual location draft and never replay a save',async({page,request})=>{
  const {api,owner}=await fresh(page,request);let mode='failed';await page.route('**/api/v1/daily-banners/locations?**',route=>mode==='failed'?route.fulfill({status:503,json:{status:503,code:'WEATHER_LOCATION_UNAVAILABLE'}}):route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{items:mode==='empty'?[]:places}}));
  await profile(page);await card(page).getByLabel('Latitude',{exact:true}).fill('1');await card(page).getByLabel('Longitude',{exact:true}).fill('2');await lookup(page);await expect(search(page)).toContainText('Place search is unavailable');await expect(card(page).getByLabel('Latitude',{exact:true})).toHaveValue('1');
  mode='empty';await lookup(page,'Nothing found');await expect(search(page)).toContainText('No matching places');await expect(card(page).getByLabel('Longitude',{exact:true})).toHaveValue('2');mode='ready';await lookup(page);await search(page).getByRole('button',{name:'Use '+places[0].name,exact:true}).click();
  const previous=await api('GET','/me');await api('PUT','/me',{zoneId:(await previous.json()).zoneId,weatherLocation:{name:'Peer location',latitude:9,longitude:10}},{'If-Match':previous.headers().etag});let writes=0;page.on('request',r=>{if(r.method()==='PUT'&&new URL(r.url()).pathname==='/api/v1/me')writes++;});
  await card(page).getByRole('button',{name:'Save',exact:true}).click();await expect(card(page)).toContainText('Your draft is kept');await expect(card(page).getByLabel('Latitude',{exact:true})).toHaveValue('63.4304');expect(writes).toBe(1);expect((await(await api('GET','/me')).json()).weatherLocation.name).toBe('Peer location');
});

test('a slow place search permits account navigation and cannot populate a later view',async({page,request})=>{
  const {owner}=await fresh(page,request);let release,started;const gate=new Promise(resolve=>release=resolve),entered=new Promise(resolve=>started=resolve);
  await page.route('**/api/v1/daily-banners/locations?**',async route=>{started();await gate;await route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{items:places}}).catch(()=>{});});
  try{await profile(page);await lookup(page);await entered;await expect(card(page).getByLabel('Latitude',{exact:true})).toBeEditable();await page.locator('#account-navigation a[href="#account/notes"]').click();await expect(search(page)).toHaveCount(0);release();await page.locator('#account-navigation a[href="#account/profile"]').click();await expect(search(page).getByLabel('Place name',{exact:true})).toHaveValue('');await expect(search(page).getByRole('button',{name:'Use '+places[0].name,exact:true})).toHaveCount(0);await expect(card(page).getByLabel('Latitude',{exact:true})).toHaveValue('');}finally{release();}
});

test('read-only account settings expose the location while disabling search and save',async({page,request,browser})=>{
  const {api}=await fresh(page,request);const before=await api('GET','/me');await api('PUT','/me',{zoneId:(await before.json()).zoneId,weatherLocation:places[0]},{'If-Match':before.headers().etag});const token=(await(await api('POST','/auth/tokens',{name:'Read-only weather',permissions:['read'],expiresAt:null})).json()).token,context=await browser.newContext({baseURL:process.env.TIMEKEEPER_WEB_URL,ignoreHTTPSErrors:true});
  try{const read=await context.newPage();await read.goto('/web/');await read.locator('#auth-mode').selectOption('token');await read.locator('#token').fill(token);await read.locator('#login-form button[type=submit]').click();await expect(read.locator('#status')).toHaveText('Up to date');await profile(read);await expect(card(read).getByLabel('Latitude',{exact:true})).toHaveValue('63.4304');await expect(card(read).getByLabel('Location name (optional)',{exact:true})).toHaveValue(places[0].name);await expect(search(read).getByLabel('Place name',{exact:true})).toBeDisabled();await expect(search(read).getByRole('button',{name:'Search places',exact:true})).toBeDisabled();await expect(card(read).getByRole('button',{name:'Save',exact:true})).toBeDisabled();}finally{await context.close();}
});


test('Norwegian place search requests local names and remains usable on a narrow screen',async({page,request})=>{
  const {api,owner}=await fresh(page,request),before=await api('GET','/me');await api('PUT','/me',{zoneId:(await before.json()).zoneId,locale:'nb-NO'},{'If-Match':before.headers().etag});
  const queries=[];await page.route('**/api/v1/daily-banners/locations?**',route=>{queries.push(Object.fromEntries(new URL(route.request().url()).searchParams));return route.fulfill({headers:{'X-Timekeeper-Owner':owner},json:{items:places}});});
  await page.goto('/web/#account/profile');const form=page.getByRole('form',{name:"Søk etter sted for værvarsel",exact:true});await expect(form).toBeVisible();await page.setViewportSize({width:320,height:900});
  await form.getByLabel("Stedsnavn",{exact:true}).fill('Trondheim');await form.getByLabel("Stedsnavn",{exact:true}).press('Enter');await expect(form.getByRole('button',{name:"Bruk "+places[0].name,exact:true})).toBeVisible();expect(queries).toEqual([{q:'Trondheim',language:'nb'}]);
  await form.getByRole('button',{name:"Bruk "+places[0].name,exact:true}).click();await expect(page.getByLabel('Breddegrad',{exact:true})).toHaveValue('63.4304');expect((await new AxeBuilder({page}).include('.weather-location-search').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await captureScreenshot(page,{path:'test-results/weather-search-nb-320.png'});
});

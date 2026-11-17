// Optional acceptance against an unmodified official draw.io WAR served locally.
// node tests/drawio-roundtrip.mjs http://127.0.0.1:18764 input.png output-folder
import {chromium} from 'playwright';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
const [editor,input,output]=process.argv.slice(2),url=new URL(editor);
assert.ok(['localhost','127.0.0.1','[::1]'].includes(url.hostname),'Use a temporary local official editor');assert.ok(input&&output);
await mkdir(output,{recursive:true});const browser=await chromium.launch();
try{
  const page=await browser.newPage(),origin=url.origin,harness=origin+'/dagsverk-png-acceptance';
  await page.route(harness,route=>route.fulfill({contentType:'text/html',body:'<!doctype html><title>Editable PNG acceptance</title><iframe id="editor" style="width:98vw;height:90vh"></iframe>'}));
  await page.goto(harness);await page.evaluate(()=>{window.messages=[];window.addEventListener('message',event=>{try{const value=JSON.parse(event.data);if(value.event)window.messages.push(value);}catch{}});});
  await page.locator('iframe').evaluate((frame,src)=>frame.src=src,editor+'/?embed=1&proto=json&ui=min&stealth=1');
  const event=async name=>{await page.waitForFunction(name=>window.messages.some(value=>value.event===name),name);return page.evaluate(name=>window.messages.find(value=>value.event===name),name);};
  const send=async value=>{await page.evaluate(({value,origin})=>{window.messages=[];document.getElementById('editor').contentWindow.postMessage(JSON.stringify(value),origin);},{value,origin});};
  await event('init');const png='data:image/png;base64,'+(await readFile(input)).toString('base64');await send({action:'load',xml:png});await event('load');
  await send({action:'export',format:'xml'});const reopened=await event('export');
  for(const text of ['name="Overview"','name="Details"','id="stage-a"','id="stage-b"'])assert.ok(reopened.xml.includes(text),text);
  await writeFile(output+'/reopened.drawio',reopened.xml);
  const frame=page.frames().find(value=>value.url().includes('embed=1'));await frame.getByText('Details',{exact:true}).click();await frame.getByText(/^Stage B(?: revised)?$/).dblclick();
  await frame.locator('.mxCellEditor').fill('Stage B revised');await frame.locator('.mxCellEditor').press('ControlOrMeta+Enter');
  await frame.getByText('Overview',{exact:true}).press('Enter');await frame.getByText(/^Stage A(?: revised)?$/).dblclick();await frame.locator('.mxCellEditor').fill('Stage A revised');await frame.locator('.mxCellEditor').press('ControlOrMeta+Enter');
  await send({action:'export',format:'xmlpng',scale:1,border:12});const revised=await event('export');assert.ok(revised.xml.includes('Stage B revised'));assert.ok(revised.xml.includes('name="Overview"'));
  await writeFile(output+'/revised.drawio.png',Buffer.from(revised.data.split(',')[1],'base64'));await page.screenshot({path:output+'/editable-pages.png'});
  console.log(JSON.stringify({input,pages:['Overview','Details'],editableShapes:['stage-a','stage-b'],revisedShape:'Stage B revised',output}));
}finally{await browser.close();}

import {readFile} from 'node:fs/promises';

const installed=new WeakSet();
/** Test-only adapters exercise the product component without shipping a demo UI. */
export async function openEditorFixture(page){
  if(!installed.has(page)){
    for(const name of ['html','js'])await page.route('**/web/editor-fixture.'+name,async route=>route.fulfill({
      contentType:name==='html'?'text/html':'text/javascript',
      body:await readFile(new URL('./fixtures/editor/field.'+name,import.meta.url),'utf8'),
      headers:name==='html'?{'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; connect-src 'self'; img-src 'self' data: blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"}:{}
    }));
    installed.add(page);
  }
  await page.goto('/web/editor-fixture.html');
  await page.waitForFunction(()=>!!globalThis.fixtureField);
}

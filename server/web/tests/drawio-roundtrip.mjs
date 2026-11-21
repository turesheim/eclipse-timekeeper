// Optional acceptance against the official draw.io web application on a local static host.
// node tests/drawio-roundtrip.mjs http://127.0.0.1:18764 input.png output-folder [chromium|webkit]
import assert from 'node:assert/strict';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {createServer} from 'node:http';
import {join} from 'node:path';
import {chromium, webkit} from 'playwright';

const [editor, input, output, engine = 'chromium'] = process.argv.slice(2);
assert.ok(editor && input && output, 'Supply an editor URL, input PNG and output folder');
assert.ok(['chromium', 'webkit'].includes(engine), 'Choose Chromium or WebKit');

const editorUrl = new URL(editor);
assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(editorUrl.hostname), 'Use a temporary local official editor');
const editorOrigin = editorUrl.origin;
editorUrl.search = new URLSearchParams({embed: '1', proto: 'json', ui: 'min', offline: '1', stealth: '1'});
// Keep the host's production policy and grant framing to the configured editor alone.
const properties = await readFile(new URL('../../app/src/main/resources/application.properties', import.meta.url), 'utf8');
const policy = properties.match(/^quarkus\.http\.filter\.web\.header\."Content-Security-Policy"=(.+)$/m)?.[1];
assert.ok(policy, 'Missing Dagsverk web Content Security Policy');
assert.doesNotMatch(policy, /(?:^|;)\s*frame-src\s/);

await mkdir(output, {recursive: true});
const parent = createServer((request, response) => {
  if (request.url !== '/dagsverk-png-acceptance') {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Security-Policy': policy + '; frame-src ' + editorOrigin,
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  }).end('<!doctype html><title>Editable PNG acceptance</title>'
      + '<iframe id="editor" title="Diagram editor" width="1200" height="640" sandbox="allow-scripts allow-same-origin"></iframe>');
});
await new Promise((resolve, reject) => {
  parent.once('error', reject);
  parent.listen(0, '127.0.0.1', resolve);
});
const hostname = editorUrl.hostname === 'localhost' ? '127.0.0.1' : 'localhost';
const parentOrigin = 'http://' + hostname + ':' + parent.address().port;
const harness = parentOrigin + '/dagsverk-png-acceptance';
let browser;
try {
  browser = await {chromium, webkit}[engine].launch();
  const context = await browser.newContext({serviceWorkers: 'block'});
  const blockedOrigins = new Set();
  await context.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.origin === editorOrigin || url.origin === parentOrigin) return route.continue();
    blockedOrigins.add(url.origin);
    return route.abort();
  });

  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  const waitForEvent = async name => {
    await page.waitForFunction(name => window.messages.some(message => message.event === name), name);
    return page.evaluate(name => window.messages.find(message => message.event === name), name);
  };
  const send = value => page.evaluate(({value, origin}) => {
    window.messages = [];
    document.getElementById('editor').contentWindow.postMessage(JSON.stringify(value), origin);
  }, {value, origin: editorOrigin});
  const openEditor = async png => {
    await page.goto(harness);
    await page.evaluate(({origin, src}) => {
      const frame = document.getElementById('editor');
      window.messages = [];
      window.addEventListener('message', event => {
        if (event.origin !== origin || event.source !== frame.contentWindow || typeof event.data !== 'string') return;
        try {
          const message = JSON.parse(event.data);
          if (typeof message?.event === 'string') window.messages.push(message);
        } catch (error) {
          if (!(error instanceof SyntaxError)) throw error;
        }
      });
      frame.src = src;
    }, {origin: editorOrigin, src: editorUrl.href});
    await waitForEvent('init');
    await send({action: 'load', xml: png});
    await waitForEvent('load');
  };
  const assertPages = xml => {
    for (const text of ['name="Overview"', 'name="Details"', 'id="stage-a"', 'id="stage-b"']) {
      assert.ok(xml.includes(text), text);
    }
  };

  await openEditor('data:image/png;base64,' + (await readFile(input)).toString('base64'));
  await send({action: 'export', format: 'xml'});
  const original = await waitForEvent('export');
  assertPages(original.xml);
  await writeFile(join(output, 'reopened.drawio'), original.xml);

  const frame = page.frameLocator('#editor');
  await frame.getByText('Details', {exact: true}).click();
  await frame.getByText(/^Stage B(?: revised)?$/).dblclick();
  await frame.locator('.mxCellEditor').fill('Stage B revised');
  await frame.locator('.mxCellEditor').press('ControlOrMeta+Enter');
  await frame.getByText('Overview', {exact: true}).press('Enter');
  await frame.getByText(/^Stage A(?: revised)?$/).dblclick();
  await frame.locator('.mxCellEditor').fill('Stage A revised');
  await frame.locator('.mxCellEditor').press('ControlOrMeta+Enter');
  await send({action: 'export', format: 'xmlpng', scale: 1, border: 12});
  const revised = await waitForEvent('export');
  const pngPrefix = 'data:image/png;base64,';
  assert.ok(revised.data.startsWith(pngPrefix));
  await writeFile(join(output, 'revised.drawio.png'), Buffer.from(revised.data.slice(pngPrefix.length), 'base64'));
  await page.screenshot({path: join(output, 'editable-pages.png')});

  await openEditor(revised.data);
  await send({action: 'export', format: 'xml'});
  const reopened = await waitForEvent('export');
  assertPages(reopened.xml);
  assert.ok(reopened.xml.includes('Stage A revised'));
  assert.ok(reopened.xml.includes('Stage B revised'));
  console.log(JSON.stringify({engine, parentOrigin, editorOrigin, blockedOrigins: [...blockedOrigins], input, output}));
} finally {
  try {
    await browser?.close();
  } finally {
    parent.close();
  }
}

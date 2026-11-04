import {test,expect} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {openEditorFixture} from './editor-fixture.js';

async function setup(page,description='Meeting details',title='Review'){
  await openEditorFixture(page);
  await page.evaluate(async({description,title})=>{
    const {richTooltip,installTooltips}=await import('/web/tooltips.js');
    const {calendarEventTooltip}=await import('/web/tooltip-content.js');installTooltips();
    const button=document.createElement('button');button.id='event-hint';button.textContent='Event details';
    button.style.position='fixed';button.style.left='200px';button.style.top='300px';document.body.prepend(button);
    window.updateEventDescription=value=>richTooltip(button,()=>calendarEventTooltip({title,description:value,start:'2026-11-04T10:00:00Z',end:'2026-11-04T11:00:00Z'},'UTC'));
    window.updateEventDescription(description);
  },{description,title});
  await page.clock.install({time:new Date('2030-01-01T00:00:00Z')});
  await page.clock.pauseAt(new Date('2030-01-01T00:00:01Z'));
  return page.locator('#event-hint');
}
async function show(page,button){await button.hover();await page.clock.runFor(850);const tip=page.locator('.timekeeper-tooltip');await expect(tip).toBeVisible();return tip;}
async function pointAt(page,tip){const box=await tip.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);return box;}

test('calendar descriptions preserve safe HTML and links, but all other event fields remain literal',async({page})=>{
  const button=await setup(page,'<p><strong>Agenda &amp; decisions</strong><br>Bring <em>notes</em>.</p><ul><li>Review</li><li>Plan</li></ul><table><tr><th>When</th><td>Now</td></tr></table><a href="https://meeting.example.test/join?q=one&amp;next=two" title="Open the call">Join meeting</a> <a href="mailto:team@example.org">Email</a> <a href="tel:+4712345678">Call</a> https://example.org/plain','Review <b>literal title</b>');
  const tip=await show(page,button);
  await expect(tip.locator('.tooltip-heading')).toHaveText('Review <b>literal title</b>');await expect(tip.locator('.tooltip-heading b')).toHaveCount(0);
  await expect(tip.locator('strong')).toHaveText('Agenda & decisions');await expect(tip.locator('em')).toHaveText('notes');await expect(tip.locator('li')).toHaveCount(2);await expect(tip.locator('td')).toHaveText('Now');
  const link=tip.getByRole('link',{name:'Join meeting'});await expect(link).toHaveAttribute('href','https://meeting.example.test/join?q=one&next=two');await expect(link).toHaveAttribute('target','_blank');await expect(link).toHaveAttribute('rel','noopener noreferrer');
  await expect(link).toHaveAttribute('title','Open the call');await expect(link).not.toHaveAttribute('data-tk-tooltip');
  await expect(tip.getByRole('link',{name:'Email'})).toHaveAttribute('href','mailto:team@example.org');await expect(tip.getByRole('link',{name:'Call'})).toHaveAttribute('href','tel:+4712345678');await expect(tip.getByRole('link',{name:'https://example.org/plain'})).toHaveCount(1);
  await page.context().route('https://meeting.example.test/**',route=>route.fulfill({contentType:'text/html',body:'Meeting page'}));
  const popupPromise=page.waitForEvent('popup');await link.click();const popup=await popupPromise;await popup.waitForLoadState();expect(await popup.evaluate(()=>window.opener)).toBeNull();await expect(popup.locator('body')).toHaveText('Meeting page');await popup.close();
});

test('hostile description HTML cannot execute, load resources, add UI attributes or create unsafe links',async({page})=>{
  const loads=[],errors=[];page.on('request',request=>{if(request.url().includes('untrusted.example.test'))loads.push(request.url());});page.on('pageerror',error=>errors.push(error.message));
  const button=await setup(page,'<script>window.tooltipInjection=true</script><style>body{display:none}</style><img src="https://untrusted.example.test/image" onerror="window.tooltipInjection=true"><iframe src="https://untrusted.example.test/frame"></iframe><svg><image href="https://untrusted.example.test/svg"/></svg><math><mtext>Hidden</mtext></math><form action="https://untrusted.example.test/form"><input autofocus></form><template><img src="https://untrusted.example.test/template"></template><p id="login" class="btn" style="color:red" data-tk-tooltip="" onclick="window.tooltipInjection=true">Safe text</p><a href="jav&#x61;script:alert(1)" target="_self">Bad script</a><a href="data:text/html,bad">Bad data</a><a href="/api/v1/me">Relative</a><a href="//untrusted.example.test/relative">Scheme relative</a><a href="https://example.org/ok" ping="https://untrusted.example.test/ping" download>Safe link</a>');
  const tip=await show(page,button),description=tip.locator('.tooltip-description');
  await expect(description.locator('script,style,img,iframe,svg,math,form,input,template')).toHaveCount(0);await expect(description.locator('[id],[class],[style],[data-tk-tooltip],[onclick],[onerror],[autofocus],[ping],[download]')).toHaveCount(0);
  for(const name of ['Bad script','Bad data','Relative','Scheme relative'])await expect(description.getByText(name,{exact:true})).not.toHaveAttribute('href');
  await expect(description.getByRole('link')).toHaveCount(1);await expect(description.getByRole('link')).toHaveText('Safe link');
  await page.clock.runFor(1000);expect(await page.evaluate(()=>!!window.tooltipInjection)).toBe(false);expect(loads).toEqual([]);expect(errors).toEqual([]);
});

test('plain descriptions keep line breaks, angle URLs and balanced URL parentheses',async({page})=>{
  const button=await setup(page,'Line one\n<https://example.org/meeting>\nRead (https://example.org/a_(b)).');const tip=await show(page,button);
  await expect(tip.locator('.tooltip-description')).toHaveText('Line one\n<https://example.org/meeting>\nRead (https://example.org/a_(b)).');await expect(tip.locator('.tooltip-description')).toHaveCSS('white-space','pre-wrap');
  await expect(tip.getByRole('link',{name:'https://example.org/meeting'})).toHaveAttribute('href','https://example.org/meeting');await expect(tip.getByRole('link',{name:'https://example.org/a_(b)'})).toHaveAttribute('href','https://example.org/a_(b)');
});

test('passive tooltip stays the same over another hint and focuses only after five continuous seconds',async({page})=>{
  const button=await setup(page,'<p>Scrollable details</p>'+('<p>Another line of the agenda.</p>').repeat(80)),tip=await show(page,button),id=await tip.getAttribute('id');
  const box=await tip.boundingBox();
  await page.evaluate(({box})=>{
    const below=document.createElement('button');below.id='under-tooltip';below.title='Underlying hint';below.textContent='Underlying control';below.style.position='fixed';below.style.left=(box.x+box.width/2-30)+'px';below.style.top=(box.y+box.height/2-10)+'px';document.body.append(below);
    window.tooltipRemovals=0;new MutationObserver(changes=>{for(const change of changes)for(const node of change.removedNodes)if(node instanceof Element&&node.classList.contains('timekeeper-tooltip'))window.tooltipRemovals++;}).observe(document.body,{childList:true});
  },{box});
  await pointAt(page,tip);await page.clock.runFor(1000);await expect(tip).toHaveAttribute('id',id);expect(await page.evaluate(()=>window.tooltipRemovals)).toBe(0);await expect(tip).toHaveAttribute('role','tooltip');await expect(tip.locator('.tooltip-inner')).not.toBeFocused();
  await page.mouse.wheel(0,200);await expect.poll(()=>tip.locator('.tooltip-inner').evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
  await page.clock.runFor(3999);await expect(tip.locator('.tooltip-inner')).not.toBeFocused();await page.clock.runFor(1);await expect(tip.locator('.tooltip-inner')).toBeFocused();await expect(tip).toHaveAttribute('role','dialog');await expect(tip).toHaveAttribute('aria-label','Review');await expect(tip).toHaveCSS('pointer-events','auto');
  // Native WebKit scrolling and axe need real timers after the dwell assertions.
  await page.clock.resume();const before=await tip.locator('.tooltip-inner').evaluate(node=>node.scrollTop);await page.mouse.wheel(0,200);await expect.poll(()=>tip.locator('.tooltip-inner').evaluate(node=>node.scrollTop)).toBeGreaterThan(before);
  const audit=await new AxeBuilder({page}).include('.timekeeper-tooltip').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(audit.violations).toEqual([]);
  await page.keyboard.press('Escape');await expect(tip).toHaveCount(0);await expect(button).toBeFocused();await expect(button).not.toHaveAttribute('aria-describedby');
});

test('leaving the tooltip cancels its focus countdown and a new hover starts a full countdown',async({page})=>{
  const button=await setup(page),tip=await show(page,button);await pointAt(page,tip);await page.clock.runFor(4900);
  await button.hover();await page.clock.runFor(200);await expect(tip.locator('.tooltip-inner')).not.toBeFocused();await pointAt(page,tip);await page.clock.runFor(4999);await expect(tip.locator('.tooltip-inner')).not.toBeFocused();await page.clock.runFor(1);await expect(tip.locator('.tooltip-inner')).toBeFocused();
  await page.mouse.move(0,0);await page.clock.runFor(1000);await expect(tip).toBeVisible();await page.locator('#event-hint').evaluate(node=>{node.hidden=true;});await expect(tip).toHaveCount(0);
});

test('keyboard focus opens details with F2 and live refreshes preserve the focused link and scrolled content',async({page,browserName})=>{
  const button=await setup(page,'<a href="https://example.org/first">First link</a>'+('<p>Long detail</p>').repeat(80));await button.focus();await page.clock.runFor(850);const tip=page.locator('.timekeeper-tooltip');await expect(tip).toBeVisible();await page.keyboard.press('F2');await expect(tip.locator('.tooltip-inner')).toBeFocused();
  await page.clock.resume();await page.keyboard.press('PageDown');await expect.poll(()=>tip.locator('.tooltip-inner').evaluate(node=>node.scrollTop)).toBeGreaterThan(0);
  await page.keyboard.press(browserName==='webkit'&&process.platform==='darwin'?'Alt+Tab':'Tab');const link=tip.getByRole('link',{name:'First link'});await expect(link).toBeFocused();
  await tip.locator('.tooltip-inner').evaluate(node=>{node.scrollTop=100;});await page.evaluate(()=>window.updateEventDescription('<a href="https://example.org/new">Updated link</a>'));await expect(link).toBeFocused();expect(await tip.locator('.tooltip-inner').evaluate(node=>node.scrollTop)).toBe(100);
  await page.keyboard.press('Escape');await expect(button).toBeFocused();await expect(tip).toHaveCount(0);await page.mouse.move(0,0);await show(page,button);await expect(page.locator('.timekeeper-tooltip')).toContainText('Updated link');
});

test('Escape closes a focused tooltip without closing its native dialog, and disposal cancels pending focus',async({page})=>{
  const button=await setup(page);await button.evaluate(node=>{const dialog=document.createElement('dialog');dialog.append(node);document.body.append(dialog);dialog.showModal();});
  const tip=await show(page,button);await pointAt(page,tip);await page.clock.runFor(5000);await expect(tip.locator('.tooltip-inner')).toBeFocused();await page.keyboard.press('Escape');await expect(tip).toHaveCount(0);await expect(page.locator('dialog[open]')).toBeVisible();await expect(button).toBeFocused();
  await page.mouse.move(0,0);const reopened=await show(page,button);await pointAt(page,reopened);await page.clock.runFor(4000);await page.locator('dialog[open]').evaluate(node=>node.close());await page.clock.runFor(2000);await expect(reopened).toHaveCount(0);await expect(page.locator('dialog[open]')).toHaveCount(0);
});

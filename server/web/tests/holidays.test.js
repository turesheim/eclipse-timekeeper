import {test} from 'node:test';
import assert from 'node:assert/strict';
import {holidayRegion,publicHolidayCalendar} from '../src/holidays.js';
import {monthCalendar,monthSessions} from '../src/month-model.js';
import {calendarWeek} from '../src/calendar-model.js';

const holidays=(month,locale,zone='UTC')=>publicHolidayCalendar(monthCalendar(month,zone),locale);

test('holiday country follows the locale region, including browser regions outside the saved choices',()=>{
  for(const [locale,country] of [['nb-NO','NO'],['nn-NO','NO'],['en-NO','NO'],['en-GB','GB'],['en-US','US'],['en-AU','AU'],['en','US']])assert.equal(holidayRegion(locale),country);
  assert.equal(holidayRegion('en-001'),null);assert.equal(holidayRegion('en-ZZ'),null);assert.equal(holidayRegion('invalid_locale'),null);
});
test('Norwegian public holidays include movable dates and exclude ordinary observances',async()=>{
  const may=await holidays('2026-05','nb-NO');assert.equal(may.state,'ready');assert.equal(may.country,'NO');
  assert.deepEqual([...may.days.keys()],['2026-05-01','2026-05-14','2026-05-17','2026-05-24','2026-05-25']);
  assert.equal(may.days.get('2026-05-17')[0].name,'Grunnlovsdagen');assert.equal(may.days.has('2026-05-08'),false);
  const easter=await holidays('2026-04','nb-NO');
  assert.equal(easter.days.get('2026-04-02')[0].name,'Skjærtorsdag');assert.equal(easter.days.get('2026-04-06')[0].name,'Andre påskedag');
  const next=await holidays('2027-03','nb-NO');assert.equal(next.days.get('2027-03-26')[0].name,'Langfredag');
});
test('country defaults and substitute days remain distinct from other countries and subdivisions',async()=>{
  // Anchors: gov.uk/bank-holidays.json and OPM's federal-holiday weekend rule.
  const british=await holidays('2026-05','en-GB');assert.equal(british.days.get('2026-05-04')[0].name,'Early May bank holiday');assert.equal(british.days.has('2026-05-17'),false);
  const christmas=await holidays('2026-12','en-GB');assert.equal(christmas.days.get('2026-12-28')[0].substitute,true);
  const american=await holidays('2026-07','en-US');assert.match(american.days.get('2026-07-03')[0].name,/Independence Day/);assert.equal(american.days.get('2026-07-03')[0].substitute,true);assert.equal(american.days.get('2026-07-04')[0].substitute,false);
  const national=await holidays('2026-01','en-GB');assert.equal(national.days.has('2026-01-02'),false,'A subdivision-only date must not become UK-wide');
});
test('New Year padding and holidays crossing year boundaries are included once',async()=>{
  const january=await holidays('2022-01','en-US');assert.equal(january.days.get('2021-12-31').length,1);assert.match(january.days.get('2021-12-31')[0].name,/New Year's Day/);assert.equal(january.days.get('2021-12-31')[0].substitute,true);
  assert.equal(january.days.get('2022-01-01')[0].substitute,false);
});
test('weekly calendars highlight only visible civil dates across month and year boundaries',async()=>{
  const calendar=calendarWeek('2025-12-29','America/Los_Angeles');
  const result=await publicHolidayCalendar({month:calendar.first.slice(0,7),days:calendar.days},'nb-NO');
  assert.deepEqual([...result.days.keys()],['2026-01-01']);
  assert.equal(result.days.get('2026-01-01')[0].name,'Første nyttårsdag');
});
test('multi-day public holidays cover each civil date, and names follow the interface language',async()=>{
  const armenian=await holidays('2026-01','en-AM');assert.equal(armenian.days.get('2026-01-02')[0].name,armenian.days.get('2026-01-01')[0].name);
  const english=await holidays('2026-05','en-NO'),norwegian=await holidays('2026-05','nb-NO');
  assert.equal(english.days.get('2026-05-17')[0].name,'Constitution Day');assert.equal(norwegian.days.get('2026-05-17')[0].name,'Grunnlovsdagen');
});
test('holiday dates are independent of both account zone and recorded session arithmetic',async()=>{
  const calendar=monthCalendar('2026-05','America/Los_Angeles'),original=structuredClone(calendar);
  const sessions=[{id:'holiday-work',clientId:'client',start:'2026-05-17T12:00:00.123456789Z',end:'2026-05-17T13:00:00.987654321Z'}];
  const before=monthSessions(calendar,sessions,'2026-06-01T00:00:00Z');
  const west=await publicHolidayCalendar(calendar,'nb-NO'),east=await holidays('2026-05','nb-NO','Pacific/Kiritimati');
  assert.deepEqual(west.days,east.days);assert.deepEqual(calendar,original);assert.deepEqual(monthSessions(calendar,sessions,'2026-06-01T00:00:00Z'),before);assert.equal(before.total,3600864197532n);
});
test('unsupported countries and the upstream numeric-year limitation return explicit states',async()=>{
  assert.equal((await holidays('2026-05','en-001')).state,'unsupported-region');
  const ancient=await publicHolidayCalendar({month:'0001-01',days:[{date:'0001-01-01'}]},'nb-NO');assert.equal(ancient.state,'unsupported-year');assert.equal(ancient.days.size,0);
  const distant=await holidays('9998-12','nb-NO');assert.equal(distant.state,'ready');assert.ok(distant.days.has('9998-12-25'));
});

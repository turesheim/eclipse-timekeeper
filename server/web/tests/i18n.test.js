import {test} from 'node:test';
import assert from 'node:assert/strict';
import {browserLocale,getLocale,setLocale,t} from '../src/i18n.js';
import {ApiError} from '../src/api.js';
import {nanosText,decimalHoursText,instantText,localDateTimeToInstant} from '../src/model.js';
import {parseTogglDetailedCsv} from '../src/toggl-csv.js';
import {importPlan} from '../src/import-model.js';

test('browser language detection respects preference order, Norwegian aliases and supported fallbacks',()=>{
  assert.equal(browserLocale(['de-DE','nb-NO','en-US']),'nb-NO');
  assert.equal(browserLocale(['no-NO']),'nb-NO');assert.equal(browserLocale(['nn-NO']),'nb-NO');
  assert.equal(browserLocale(['en-AU','nb-NO']),'en-AU');
  assert.equal(browserLocale(['invalid_tag','fr-FR']),'en-GB');
  setLocale('de-DE');assert.equal(getLocale(),browserLocale());setLocale('en-US');
});
test('Norwegian and English messages interpolate and pluralize without evaluating user text',()=>{
  try {
    setLocale('nb-NO');assert.equal(t('ui.my_account'),'Min konto');
    assert.equal(t('count.projects',{count:1}),'1 prosjekt');assert.equal(t('count.projects',{count:2}),'2 prosjekter');
    assert.equal(t('count.work_sessions',{count:1}),'1 arbeidsøkt\n');
    assert.equal(t('import.invalid_rows',{count:1}),'1 ugyldig rad kan ikke importeres.');
    assert.equal(t('import.invalid_rows',{count:2}),'2 ugyldige rader kan ikke importeres.');
    const name='<img src=x onerror=alert(1)> {{another}}';
    assert.equal(t('ui.signed_in_as_your_work_data_is_private_to_your_account',{value0:name}),`Logget inn som ${name}. Arbeidsdataene dine er private for kontoen din.`);
    assert.match(new ApiError('UNAUTHENTICATED').message,/Logg inn på nytt/);
    setLocale('en-GB');assert.equal(t('ui.my_account'),'My Account');assert.match(new ApiError('UNAUTHENTICATED').message,/Sign in again/);
  } finally {setLocale('en-US');}
});
test('display locale changes dates and decimal hours without rounding or changing machine timestamps',()=>{
  try {
    setLocale('nb-NO');assert.equal(nanosText(4500000000000n),'1t 15m');assert.equal(decimalHoursText(4500000000000n),'1,25t');
    assert.match(instantText('2026-10-19T12:00:00Z','Europe/Oslo'),/okt/);
    assert.equal(localDateTimeToInstant('2026-10-19T14:00:00.123456789','Europe/Oslo'),'2026-10-19T12:00:00.123456789Z');
    setLocale('en-US');assert.equal(nanosText(4500000000000n),'1h 15m');assert.equal(decimalHoursText(4500000000000n),'1.25h');
    assert.match(instantText('2026-10-19T12:00:00Z','Europe/Oslo'),/Oct/);
  } finally {setLocale('en-US');}
});
test('Toggl headers, user records and import fingerprints remain identical across interface languages',async()=>{
  const csv='User,Email,Client,Project,Task,Description,Start date,Start time,Stop date,Stop time,Duration,Tags\nTester,test@example.org,Profile,Project,,,2026-01-01,10:00:00,2026-01-01,11:00:00,01:00:00,Label';
  try {
    const options={zone:'Europe/Oslo',now:'2026-10-19T00:00:00Z'};
    setLocale('en-US');const english=parseTogglDetailedCsv(csv,options),first=await importPlan(english.rows,english.people[0].key,{fallbackClient:'Fallback'});
    setLocale('nb-NO');const norwegian=parseTogglDetailedCsv(csv,options),second=await importPlan(norwegian.rows,norwegian.people[0].key,{fallbackClient:'Fallback'});
    assert.deepEqual(norwegian,english);assert.deepEqual(second,first);
    assert.equal(norwegian.rows[0].client,'Profile');assert.equal(norwegian.rows[0].project,'Project');
  } finally {setLocale('en-US');}
});

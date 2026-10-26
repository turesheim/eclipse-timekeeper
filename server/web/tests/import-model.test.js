import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {parseTogglDetailedCsv} from '../src/toggl-csv.js';
import {importPlan, importBatches, importDayParts} from '../src/import-model.js';

test('work sessions are the default, using reviewed clients and projects without companion task/activity records', async () => {
  const parsed = parseTogglDetailedCsv(readFileSync(new URL('./fixtures/toggl/detailed-member-stop.csv', import.meta.url), 'utf8'), {zone:'Europe/Oslo'});
  const plan = await importPlan(parsed.rows, parsed.people[0].key, {fallbackClient:'Fallback'});
  assert.equal(plan.mode,'WORK_SESSION');assert.equal(plan.selectedEntries,7);assert.equal(plan.selectedNanos,'151462000000000');
  assert.deepEqual(plan.mappings.map(value=>value.resourceType),['CLIENT','PROJECT']);assert.equal(plan.mappings[0].name,parsed.rows[0].project);
  assert.ok(plan.rows.every(row=>row.clientKey===plan.mappings[0].key && row.title===row.summary && row.projectKey===plan.mappings[1].key && !('labelKeys' in row)));
  assert.deepEqual(plan.rows.map(row=>row.tags),parsed.rows.filter(row=>row.personKey===parsed.people[0].key && !row.errors.length).map(row=>row.tags));
  const resumable=await importPlan(parsed.rows,parsed.people[0].key,{fallbackClient:'Fallback',mapWorkSessionTags:true});assert.ok(resumable.rows.every(row=>row.labelKeys.length===row.tags.length));assert.equal(resumable.selectionFingerprint,plan.selectionFingerprint);
  const long = {start:'2000-01-01T00:00:00Z',end:'2025-01-01T00:00:00Z'};
  assert.deepEqual(importBatches(Array.from({length:201},()=>long),'UTC',0,plan.mode).map(batch=>batch.length),[100,100,1]);
  const legacy=await importPlan(parsed.rows,parsed.people[0].key,{fallbackClient:'Fallback',mapWorkSessionTags:false,mapWorkSessionProjects:false});
  assert.deepEqual(legacy.mappings.map(value=>value.resourceType),['CLIENT']);assert.ok(legacy.rows.every(row=>!('labelKeys' in row)));
  assert.equal(legacy.selectionFingerprint,plan.selectionFingerprint);assert.ok(legacy.rows.every(row=>!('projectKey' in row)));
  const projectless=await importPlan(parsed.rows.map(row=>({...row,project:''})),parsed.people[0].key,{fallbackClient:'Fallback'});assert.ok(projectless.rows.every(row=>!('projectKey' in row)));assert.ok(!projectless.mappings.some(value=>value.resourceType==='PROJECT'));
  assert.equal(plan.mappings[1].parentKey,plan.mappings[0].key);
  const activityPlan = await importPlan(parsed.rows,parsed.people[0].key,{mode:'ACTIVITY',fallbackClient:'Fallback'});
  assert.notEqual(activityPlan.selectionFingerprint,plan.selectionFingerprint);
});

test('real Legacy client names take precedence over project suggestions and fallback clients in both import modes', async () => {
  const parsed = parseTogglDetailedCsv(readFileSync(new URL('./fixtures/toggl/legacy-user-end.csv', import.meta.url), 'utf8'), {zone:'Europe/Oslo'});
  for(const mode of ['WORK_SESSION','ACTIVITY']) {
    const plan = await importPlan(parsed.rows,parsed.people[0].key,{mode,fallbackClient:'Fallback client',fallbackProject:'Fallback project'});
    assert.equal(plan.selectedEntries,7);assert.equal(plan.selectedNanos,'151462000000000');
    const client = plan.mappings.find(value=>value.resourceType==='CLIENT'),project = plan.mappings.find(value=>value.resourceType==='PROJECT');
    assert.equal(client.name,'Legacy client');assert.equal(project.name,'Legacy project');assert.equal(project.parentKey,client.key);
    assert.deepEqual(plan.mappings.map(value=>value.resourceType),['CLIENT','PROJECT']);
    assert.ok(plan.rows.every(row=>row.client==='Legacy client' && row.projectKey===project.key));
    if(mode==='WORK_SESSION') assert.ok(plan.rows.every(row=>row.clientKey===client.key && row.title==='Imported work session'));
    else assert.ok(plan.rows.every(row=>row.taskKey===null));
  }
});

test('the real anonymized export produces a reviewed taskless plan without changing source ownership', async () => {
  const text = readFileSync(new URL('./fixtures/toggl/detailed-member-stop.csv', import.meta.url), 'utf8');
  const parsed = parseTogglDetailedCsv(text, {zone: 'Europe/Oslo'});
  const plan = await importPlan(parsed.rows, parsed.people[0].key, {mode:'ACTIVITY',fallbackClient: 'Reviewed fallback', fallbackProject: 'Fallback project'});
  assert.equal(plan.selectedEntries, 7); assert.equal(plan.selectedNanos, String(parsed.totalNanos));
  assert.deepEqual(plan.mappings.map(value => value.resourceType), ['CLIENT', 'PROJECT', 'LABEL']);
  assert.ok(plan.rows.every(row => row.taskKey === null && row.sourceKey === plan.sourceKey));
  assert.equal(plan.mappings[0].name, 'Reviewed fallback');
  assert.equal(importBatches(plan.rows, 'Europe/Oslo').length, 1);
  const renamed = await importPlan(parsed.rows, parsed.people[0].key, {mode:'ACTIVITY',fallbackClient: 'Another destination name', fallbackProject: 'Other'});
  assert.deepEqual(plan.mappings.map(value => value.key), renamed.mappings.map(value => value.key));
  assert.equal(plan.sourceKey, renamed.sourceKey);
});

test('batches bound both source entries and resulting daily activities across DST', () => {
  const row = {start: '2025-10-25T20:00:00.000000001Z', end: '2025-10-26T03:00:00.000000003Z'};
  assert.equal(importDayParts(row, 'Europe/Oslo'), 2);
  assert.equal(importDayParts({...row, end: '2025-10-25T22:00:00Z'}, 'Europe/Oslo'), 1);
  assert.equal(importDayParts({start:'2011-12-29T22:00:00Z',end:'2011-12-31T00:00:00Z'}, 'Pacific/Apia'), 2);
  assert.equal(importDayParts({start:'2011-12-29T22:00:00Z',end:'2011-12-30T10:00:00Z'}, 'Pacific/Apia'), 1);
  const rows = Array.from({length: 250}, () => row);
  assert.deepEqual(importBatches(rows, 'Europe/Oslo').map(batch => batch.length), [100, 100, 50]);
  assert.deepEqual(importBatches(rows, 'Europe/Oslo', 100).map(batch => batch.length), [100, 50]);
  const long = {start: '2025-01-01T00:00:00Z', end: '2025-01-21T00:00:00Z'};
  assert.deepEqual(importBatches(Array.from({length: 100}, () => long), 'UTC').map(batch => batch.length), [50, 50]);
  assert.throws(() => importDayParts({start:'2000-01-01T00:00:00Z', end:'2025-01-01T00:00:00Z'}, 'UTC'), /1,000/);
});

test('source filtering, explicit row exclusions and duplicate review survive normalization', async () => {
  const row = {personKey:'person', errors:[], client:'Client', project:'Project', task:'', tags:[], summary:'Entry',
    start:'2025-10-20T08:00:00Z', end:'2025-10-20T09:00:00Z', durationNanos:3600000000000n, occurrence:0, sourceId:null};
  const plan = await importPlan([row, {...row, personKey:'other'}, {...row, excluded:true}, {...row, occurrence:1, duplicateReviewed:true}], 'person', {mode:'ACTIVITY'});
  assert.equal(plan.rows.length, 2);assert.equal(plan.rows[1].duplicateReviewed, true);assert.equal(plan.selectedNanos, '7200000000000');
});

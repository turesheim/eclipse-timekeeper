import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {parseTogglDetailedCsv} from '../src/toggl-csv.js';
import {importPlan, importBatches, importDayParts} from '../src/import-model.js';

test('the real anonymized export produces a reviewed taskless plan without changing source ownership', async () => {
  const text = readFileSync(new URL('./fixtures/toggl/detailed-member-stop.csv', import.meta.url), 'utf8');
  const parsed = parseTogglDetailedCsv(text, {zone: 'Europe/Oslo'});
  const plan = await importPlan(parsed.rows, parsed.people[0].key, {fallbackClient: 'Reviewed fallback', fallbackProject: 'Fallback project'});
  assert.equal(plan.selectedEntries, 7); assert.equal(plan.selectedNanos, String(parsed.totalNanos));
  assert.deepEqual(plan.mappings.map(value => value.resourceType), ['CLIENT', 'PROJECT', 'LABEL']);
  assert.ok(plan.rows.every(row => row.taskKey === null && row.sourceKey === plan.sourceKey));
  assert.equal(plan.mappings[0].name, 'Reviewed fallback');
  assert.equal(importBatches(plan.rows, 'Europe/Oslo').length, 1);
  const renamed = await importPlan(parsed.rows, parsed.people[0].key, {fallbackClient: 'Another destination name', fallbackProject: 'Other'});
  assert.deepEqual(plan.mappings.map(value => value.key), renamed.mappings.map(value => value.key));
  assert.equal(plan.sourceKey, renamed.sourceKey);
});

test('batches bound both source entries and resulting daily activities across DST', () => {
  const row = {start: '2025-10-25T20:00:00.000000001Z', end: '2025-10-26T03:00:00.000000003Z'};
  assert.equal(importDayParts(row, 'Europe/Oslo'), 2);
  assert.equal(importDayParts({...row, end: '2025-10-25T22:00:00Z'}, 'Europe/Oslo'), 1);
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
  const plan = await importPlan([row, {...row, personKey:'other'}, {...row, excluded:true}, {...row, occurrence:1, duplicateReviewed:true}], 'person', {});
  assert.equal(plan.rows.length, 2);assert.equal(plan.rows[1].duplicateReviewed, true);assert.equal(plan.selectedNanos, '7200000000000');
});

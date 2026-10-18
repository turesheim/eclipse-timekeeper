import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {csvRecords, parseTogglDetailedCsv, togglDuration} from '../src/toggl-csv.js';

const options = {zone:'Europe/Oslo',now:'2027-01-01T00:00:00Z'};
const header = 'User,Email,Client,Project,Task,Description,Start date,Start time,End date,End time,Duration,Tags';
const row = 'Example Member,member@example.invalid,Client A,Project A,,Work,2025-10-20,08:00:00,2025-10-20,09:00:00,1:00:00,Tag A';
const parse = (input, changes = {}) => parseTogglDetailedCsv(input,{...options,...changes});

test('anonymized real Member/Stop export preserves all seven completed intervals and absent client/task columns', () => {
  const text = readFileSync(new URL('fixtures/toggl/detailed-member-stop.csv',import.meta.url),'utf8');
  assert.ok(text.startsWith('\uFEFF'));const report = parse(text);
  assert.equal(report.variant,'member-stop');assert.equal(report.validRows,7);assert.equal(report.rejectedRows,0);
  assert.equal(report.people.length,1);assert.equal(report.totalNanos,togglDuration('42:04:22'));
  assert.ok(report.rows.every(row => row.client === '' && row.task === '' && row.project === 'Example project'));
  assert.equal(report.rows[0].start,'2025-10-18T07:04:20Z');
  assert.equal(report.rows[0].durationNanos,2000000000n);
});

test('legacy User/End headers retain independent description, task, client, project and tags', () => {
  const report = parse(header+'\n'+row.replace('Project A,,Work','Project A,Task A,Description A'));
  assert.equal(report.variant,'user-end');assert.equal(report.totalNanos,3600000000000n);
  assert.deepEqual(report.rows[0].tags,['Tag A']);assert.equal(report.rows[0].task,'Task A');
  assert.equal(report.rows[0].summary,'Description A');assert.deepEqual(report.unsupported,[]);
});

test('anonymized real Legacy export retains client names and all seven exact intervals', () => {
  const text = readFileSync(new URL('fixtures/toggl/legacy-user-end.csv',import.meta.url),'utf8');
  const report = parse(text);
  assert.equal(report.variant,'user-end');assert.equal(report.validRows,7);assert.equal(report.rejectedRows,0);
  assert.equal(report.people.length,1);assert.equal(report.totalNanos,togglDuration('42:04:22'));
  assert.ok(report.rows.every(row => row.client === 'Legacy client' && row.project === 'Legacy project'));
  assert.ok(report.rows.every(row => row.task === '' && row.summary === '' && row.tags.length === 0));
  assert.deepEqual(report.unsupported,['Billable']);
  assert.equal(report.rows[0].start,'2025-10-18T07:04:20Z');
  assert.equal(report.rows[0].durationNanos,2000000000n);
  const detailed = parse(text.replace('"User"','"Member"').replace('"End date"','"Stop date"').replace('"End time"','"Stop time"'));
  assert.equal(detailed.variant,'member-stop');
  assert.deepEqual(report.rows.map(row => row.identity),detailed.rows.map(row => row.identity));
});

test('CSV handles BOM, quoted commas/newlines, doubled quotes and empty fields without interpreting formula text', () => {
  assert.deepEqual(csvRecords('\uFEFFA,B,C\r\n"a,b","line 1\r\nline ""2""",\r\n').map(row=>row.fields),[
    ['A','B','C'],['a,b','line 1\r\nline "2"','']]);
  const report = parse(header+'\n'+row.replace(',Work,',',"=SUM(1,2)\nnext line",').replace('Tag A','"Tag B, Tag A, Tag B"'));
  assert.equal(report.validRows,1);assert.equal(report.rows[0].summary,'=SUM(1,2)\nnext line');
  assert.deepEqual(report.rows[0].tags,['Tag A','Tag B']);
  assert.throws(()=>csvRecords('A\n"unfinished'),/Unclosed/);assert.throws(()=>csvRecords('A\n"a"x'),/Malformed/);
  assert.throws(()=>csvRecords('A\na"b'),/Malformed/);
});

test('summary reports, duplicate/mixed headers and malformed row widths fail explicitly', () => {
  assert.throws(()=>parse('Project,Duration\nA,1:00:00'),/Detailed Report/);
  assert.throws(()=>parse(header+',User\n'+row+',Member'),/duplicated/);
  assert.throws(()=>parse(header+',Member\n'+row+',Member'),/mixes/);
  assert.equal(parse(header+'\n'+row+',extra').rows[0].errors[0],'The number of fields does not match the header.');
});

test('source zone is explicit and DST ambiguity/gaps require resolution; offsets retain exact nanoseconds', () => {
  assert.throws(()=>parseTogglDetailedCsv(header+'\n'+row),/source IANA/);
  const ambiguous = row.replaceAll('2025-10-20','2025-10-26').replace('08:00:00','02:00:00').replace('09:00:00','03:00:00');
  assert.match(parse(header+'\n'+ambiguous).rows[0].errors[0],/occurs twice/);
  const resolved = ambiguous.replace('02:00:00','02:00:00.123456789+02:00').replace('03:00:00','02:00:00.123456789+01:00');
  const report = parse(header+'\n'+resolved);assert.equal(report.validRows,1);
  assert.equal(report.rows[0].start,'2025-10-26T00:00:00.123456789Z');assert.equal(report.rows[0].end,'2025-10-26T01:00:00.123456789Z');
  const gap = ambiguous.replaceAll('2025-10-26','2025-03-30');assert.match(parse(header+'\n'+gap).rows[0].errors[0],/does not exist/);
});

test('selected date format, overnight elapsed time and 25-hour DST intervals reconcile', () => {
  assert.equal(parse(header+'\n'+row.replaceAll('2025-10-20','20/10/2025'),{dateFormat:'DMY'}).validRows,1);
  assert.equal(parse(header+'\n'+row.replaceAll('2025-10-20','10/20/2025'),{dateFormat:'MDY'}).validRows,1);
  const overnight = row.replace('2025-10-20,08:00:00,2025-10-20,09:00:00','2025-10-25,23:30:00,2025-10-26,03:30:00').replace('1:00:00','5:00:00');
  assert.equal(parse(header+'\n'+overnight).totalNanos,togglDuration('5:00:00'));
  const wholeDay = row.replace('2025-10-20,08:00:00,2025-10-20,09:00:00','2025-10-26,00:00:00,2025-10-27,00:00:00').replace('1:00:00','25:00:00');
  assert.equal(parse(header+'\n'+wholeDay).totalNanos,togglDuration('25:00:00'));
});

test('incomplete, invalid, rounded and future intervals are row errors, never silently repaired', () => {
  for (const invalid of [row.replace('2025-10-20,09:00:00',','),row.replace('1:00:00','0:59:00'),row.replaceAll('2025-10-20','2025-02-30'),row.replaceAll('2025-10-20','2028-01-01')]) {
    const report=parse(header+'\n'+invalid);assert.equal(report.rejectedRows,1);assert.equal(report.totalNanos,0n);
  }
  assert.throws(()=>togglDuration('1.5'),/HH:MM:SS/);assert.throws(()=>togglDuration('0:61:00'),/HH:MM:SS/);
});

test('missing Toggl durations require explicit exclusion rather than inferred time', () => {
  for(const duration of ['-','']) {
    const report=parse(header+'\n'+row.replace('1:00:00',duration));
    assert.equal(report.rejectedRows,1);assert.equal(report.totalNanos,0n);
    assert.match(report.rows[0].errors[0],/no recorded duration.*Exclude it/);
  }
});

test('source-user selection remains explicit, unsupported fields are visible and duplicate rows are flagged', () => {
  const second = row.replaceAll('Example Member','Other Member').replaceAll('member@example.invalid','other@example.invalid');
  const report=parse(header+',Billable\n'+row+',Yes\n'+row+',Yes\n'+second+',No');
  assert.equal(report.people.length,2);assert.deepEqual(report.unsupported,['Billable']);
  assert.deepEqual(report.rows.map(row=>row.ambiguousDuplicate),[true,true,false]);
  assert.deepEqual(report.rows.map(row=>row.occurrence),[0,1,0]);assert.equal(report.totalNanos,togglDuration('3:00:00'));
  const stable=parse(header+',Time Entry ID\n'+row+',entry-1\n'+row+',entry-1');
  assert.equal(stable.rejectedRows,2);assert.equal(stable.totalNanos,0n);
  const workspaces=parse(header+',Workspace\n'+row+',Source A\n'+row+',Source B');
  assert.equal(workspaces.people.length,1);assert.deepEqual(workspaces.rows.map(row=>row.ambiguousDuplicate),[true,true]);
  assert.deepEqual(workspaces.unsupported,['Workspace']);
});


test('Toggl workspace metadata never changes source selection or repeat identity', () => {
  const original=parse(header+'\n'+row), named=parse(header+',Workspace\n'+row+',Old workspace');
  const renamed=parse(header+',Workspace\n'+row+',Different workspace');
  assert.deepEqual(named.people,original.people);assert.deepEqual(renamed.people,original.people);
  assert.equal(named.rows[0].identity,original.rows[0].identity);assert.equal(renamed.rows[0].identity,original.rows[0].identity);
  assert.ok(!('workspace' in named.people[0]));
});

import {test} from 'node:test';
import assert from 'node:assert/strict';
import {dayStart, weekRange, durationNanos, durationText, dateInZone, instantNanos, taskPresentation, activityDayNanos, workweekGroups} from '../src/model.js';
test('Oslo report ranges include 23- and 25-hour days and Monday boundaries', () => {
  assert.equal(dayStart('2026-03-29', 'Europe/Oslo'), '2026-03-28T23:00:00.000Z');
  assert.equal(dayStart('2026-03-30', 'Europe/Oslo'), '2026-03-29T22:00:00.000Z');
  assert.equal(dayStart('2026-10-25', 'Europe/Oslo'), '2026-10-24T22:00:00.000Z');
  assert.equal(dayStart('2026-10-26', 'Europe/Oslo'), '2026-10-25T23:00:00.000Z');
  assert.deepEqual(weekRange('2026-10-25', 'Europe/Oslo'), {first:'2026-10-19', from:'2026-10-18T22:00:00.000Z', to:'2026-10-25T23:00:00.000Z'});
});
test('civil dates do not follow the browser zone and skipped dates are rejected', () => {
  assert.equal(dateInZone('2026-10-25T23:30:00Z', 'Europe/Oslo'), '2026-10-26');
  assert.equal(dayStart('2026-01-01', 'Pacific/Kiritimati'), '2025-12-31T10:00:00.000Z');
  assert.throws(() => dayStart('2011-12-30', 'Pacific/Apia'), /does not exist/);
});
test('report totals retain exact nanoseconds and arbitrarily long hours', () => {
  assert.equal(durationNanos('PT25H0.000000001S'), 90000000000001n);
  assert.equal(durationText('PT25H'), '25h 0m');
  assert.equal(durationText('PT0.000000001S'), '0h 0m');
  assert.equal(durationText('PT2H37M50.076758506S'), '2h 37m 50s');
  assert.equal(durationText('PT9007199254740993H'), '9007199254740993h 0m');
  assert.throws(() => durationNanos('private-token'), /Invalid/);
});
test('external task titles use Jira browse keys and GitHub issue numbers with safe links', () => {
  const reference = {providerId:'jira', repositoryId:'https://jira.example', externalId:'10017', externalUrl:'https://jira.example/browse/PLATFORM-3396'};
  assert.deepEqual(taskPresentation({summary:'Clean registry', externalReferences:[reference]}), {title:'PLATFORM-3396: Clean registry', url:reference.externalUrl});
  assert.equal(taskPresentation({summary:'PLATFORM-3396: Clean registry', externalReferences:[reference]}).title, 'PLATFORM-3396: Clean registry');
  assert.equal(taskPresentation({summary:'Fix', externalReferences:[{...reference,providerId:'github',externalUrl:'https://github.com/org/repo/issues/17'}]}).title, '#17: Fix');
  assert.deepEqual(taskPresentation({summary:'Native task',id:'internal-uuid',url:'javascript:alert(1)'}), {title:'Native task',url:null});
  assert.equal(taskPresentation({summary:'Fix', externalReferences:[{...reference,externalUrl:null}]}).title, '10017: Fix');
});
test('workweek hierarchy retains parents and clips exact activity time across DST and midnight', () => {
  const activity = {taskId:'child',start:'2026-10-24T22:00:00.123456789Z',end:'2026-10-25T23:00:00.123456790Z'};
  const day = activityDayNanos(activity,'2026-10-25','Europe/Oslo',activity.end);
  assert.equal(day,90000000000000n - 123456789n);
  assert.equal(activityDayNanos(activity,'2026-10-26','Europe/Oslo',activity.end),123456790n);
  assert.equal(activityDayNanos({...activity,end:null},'2026-10-25','Europe/Oslo','2026-10-24T23:00:00Z'),3600000000000n - 123456789n);
  const groups = workweekGroups([{id:'parent',summary:'Parent',projectId:'project'},{id:'child',summary:'Child',parentTaskId:'parent',projectId:'project'},{id:'empty',summary:'No time'}],[{id:'project',name:'Project'}],[activity]);
  assert.equal(groups.length,1); assert.equal(groups[0].name,'Project');
  assert.equal(groups[0].tasks[0].task.id,'parent'); assert.equal(groups[0].tasks[0].children[0].activities[0],activity);
});
test('daily clipping compares instants, including exact boundaries and submilliseconds', () => {
  assert.equal(instantNanos('2026-10-25T23:00:00Z'), instantNanos('2026-10-25T23:00:00.000Z'));
  assert.equal(instantNanos('2026-10-25T23:00:00.000000001Z') - instantNanos('2026-10-25T23:00:00Z'), 1n);
});

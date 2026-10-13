import {test} from 'node:test';
import assert from 'node:assert/strict';
import {dayStart, weekRange, durationNanos, durationText, dateInZone, instantNanos} from '../src/model.js';
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
  assert.equal(durationText('PT0.000000001S'), '0h 0m 0.000000001s');
  assert.equal(durationText('PT9007199254740993H'), '9007199254740993h 0m');
  assert.throws(() => durationNanos('private-token'), /Invalid/);
});
test('daily clipping compares instants, including exact boundaries and submilliseconds', () => {
  assert.equal(instantNanos('2026-10-25T23:00:00Z'), instantNanos('2026-10-25T23:00:00.000Z'));
  assert.equal(instantNanos('2026-10-25T23:00:00.000000001Z') - instantNanos('2026-10-25T23:00:00Z'), 1n);
});

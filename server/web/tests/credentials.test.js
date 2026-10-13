import {test} from 'node:test';
import assert from 'node:assert/strict';
import {savedToken, rememberToken, forgetToken} from '../src/credentials.js';

function storage() {
  const values = new Map([['other-app', 'untouched']]);
  return {values, getItem:key => values.get(key) ?? null, setItem:(key,value) => values.set(key,value), removeItem:key => values.delete(key)};
}
test('remembered credentials survive reads and sign-out removes only the saved token', () => {
  const target = storage(), source = () => target;
  assert.equal(savedToken(source), ''); assert.equal(rememberToken('fixture-token', source), true);
  assert.equal(savedToken(source), 'fixture-token'); assert.equal(target.values.size, 2);
  assert.equal(forgetToken('fixture-token', source), true); assert.equal(savedToken(source), '');
  assert.equal(target.values.get('other-app'), 'untouched');
});
test('an old session cannot remove a different token remembered by another tab', () => {
  const target = storage(), source = () => target;
  rememberToken('new-token', source); forgetToken('old-token', source);
  assert.equal(savedToken(source), 'new-token');
});
test('unavailable browser storage reports failure without preventing in-memory sign-in', () => {
  const unavailable = () => { throw new Error('Storage disabled'); };
  assert.equal(savedToken(unavailable), ''); assert.equal(rememberToken('fixture', unavailable), false);
  assert.equal(forgetToken(null, unavailable), false);
  const full = () => ({setItem() { throw new Error('Quota exceeded'); }});
  assert.equal(rememberToken('fixture', full), false);
});

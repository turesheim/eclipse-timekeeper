import {test} from 'node:test';
import assert from 'node:assert/strict';
import {savedToken, forgetToken} from '../src/credentials.js';
test('migration reads the legacy token and removes only that value after exchange', () => {
  const values=new Map([['timekeeper.access-token','old-token'],['other-app','untouched']]);
  const source=()=>({getItem:key=>values.get(key),removeItem:key=>values.delete(key)});
  assert.equal(savedToken(source),'old-token');
  assert.equal(forgetToken('different-token',source),true);assert.equal(savedToken(source),'old-token');
  assert.equal(forgetToken('old-token',source),true);assert.equal(savedToken(source),'');assert.equal(values.get('other-app'),'untouched');
});
test('blocked legacy storage is tolerated without persisting new credentials', () => {
  const unavailable=()=>{throw new Error('Storage disabled');};
  assert.equal(savedToken(unavailable),'');assert.equal(forgetToken(null,unavailable),false);
});

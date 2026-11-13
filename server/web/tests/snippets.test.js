import {test} from 'node:test';
import assert from 'node:assert/strict';
import {defaultSnippets,configureSnippets,snippetAt,sourceSnippetAt,expandTemplate} from '../src/snippets.js';
const options={snippets:defaultSnippets,zone:'Europe/Oslo',locale:'en-GB',now:new Date('2026-11-12T15:43:00Z')};
test('requested shortcuts expand using the account time zone and calendar arithmetic',()=>{
  for(const [keyword,expected]of [[';dt','2026-11-12 16:43'],[';dd','2026-11-12'],[';dd+7','2026-11-19'],[';dd+1m','2026-12-12'],[';dd-1','2026-11-11']])assert.equal(snippetAt('Meeting '+keyword,options).text,expected);
  assert.equal(snippetAt(';dd+1m',{...options,now:new Date('2028-01-31T12:00:00Z')}).text,'2028-02-29');
  assert.equal(snippetAt(';dd+1',{...options,now:new Date('2026-03-28T23:30:00Z')}).text,'2026-03-30');
  assert.equal(snippetAt(';dt',{...options,zone:'America/Los_Angeles',now:new Date('2026-11-12T01:00:00Z')}).text,'2026-11-11 17:00');
});
test('Alfred date arithmetic, custom Unicode formats, locale and cursor placeholders',()=>{
  const result=expandTemplate("Dear {cursor} — {isodate +1M -7d:yyyy-MM-dd} {time -20m:HH:mm} {date:MMMM yyyy} {clipboard} {query}",options);
  assert.equal(result.text,'Dear  — 2026-12-05 16:23 November 2026 {clipboard} {query}');assert.equal(result.cursor,5);
  assert.equal(expandTemplate('{date +1Y:yyyy-MM-dd}',options).text,'2027-11-12');
  assert.equal(expandTemplate('{date:MMMM}',{...options,locale:'nb-NO'}).text,'november');
  assert.equal(expandTemplate("{isodate:yyyy-MM-dd 'at' HH:mm}",options).text,'2026-11-12 at 16:43');
  assert.equal(expandTemplate('{isodate:unsupported}',options).text,'{isodate:unsupported}');
  assert.equal(expandTemplate('{date +999999999M}',options).text,'{date +999999999M}');
});
test('configuration is account scoped and empty preferences disable shortcuts',()=>{
  configureSnippets({zoneId:'UTC',textSnippets:[{keyword:';Hello',content:'Hi {cursor}friend'}]});
  assert.deepEqual(sourceSnippetAt(';hello'),{text:'Hi friend',cursor:3,length:6});
  assert.equal(sourceSnippetAt('email;hello'),null);
  for(const prefix of ['```js\n;hello','~~~\n;hello','    ;hello','`;hello','[link](;hello','[[;hello','<tag ;hello'])assert.equal(sourceSnippetAt(prefix),null,prefix);
  assert.equal(sourceSnippetAt('```\ncode\n```\n;hello').text,'Hi friend');
  configureSnippets({zoneId:'UTC',textSnippets:[]});assert.equal(sourceSnippetAt(';hello'),null);
  configureSnippets(null);assert.equal(sourceSnippetAt(';dd'),null);
});

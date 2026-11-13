import {test} from 'node:test';
import assert from 'node:assert/strict';
import {calendarSource} from '../src/calendar-source-status.js';

test('calendar failures identify known provider hosts and use only fixed public status pages',()=>{
  for(const [origin,label,url]of [['www.googleapis.com','Google','https://www.google.com/appsstatus/dashboard/'],['outlook.office365.com','Microsoft','https://status.cloud.microsoft/'],['p123-caldav.icloud.com','iCloud','https://www.apple.com/support/systemstatus/']])assert.deepEqual(calendarSource({origin}),{label,url});
  assert.deepEqual(calendarSource({providerId:'microsoft'}),{label:'Microsoft',url:'https://status.cloud.microsoft/'});
  assert.deepEqual(calendarSource({providerId:'toString',origin:'calendar.example.invalid'}),{label:'calendar.example.invalid',url:null});
  for(const origin of ['calendar.example.invalid','outlook.office365.com.example.invalid','google.com.example.invalid'])assert.deepEqual(calendarSource({origin}),{label:origin,url:null});
});

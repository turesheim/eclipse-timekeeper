import {test} from 'node:test';
import assert from 'node:assert/strict';
import {calendarTodo,calendarItemStatus,calendarItemWhen} from '../src/calendar-item.js';
import {ApiError} from '../src/api.js';
import {setLocale} from '../src/i18n.js';

test('calendar to-do presentation distinguishes status, an undated task and a deadline marker',()=>{
  setLocale('en-GB');
  const todo={componentType:'VTODO',todoStatus:'COMPLETED',start:'2026-10-30T09:00:00Z',end:'2026-10-30T09:00:00Z',allDay:false};
  assert.equal(calendarTodo(todo),true);
  assert.equal(calendarItemStatus(todo),'To-do · Completed');
  assert.equal(calendarItemWhen(todo,'Europe/Oslo',true),'10:00');
  assert.equal(calendarItemWhen({...todo,undated:true},'Europe/Oslo'),'No date');
  assert.equal(calendarItemWhen({...todo,allDay:true,firstDate:'2026-10-30',endDate:'2026-10-31'},'Europe/Oslo'),'All day · 30 Oct 2026');
  assert.equal(new ApiError('CALENDAR_EMPTY_SELECTION',422).message,'Select calendar events, to-do items, or both.');
  assert.equal(calendarTodo({}),false);assert.equal(calendarItemStatus({}),'Planned');
  assert.equal(calendarItemWhen({...todo,end:'2026-10-30T10:00:00Z'},'Europe/Oslo',true),'10:00–11:00');
  setLocale('nb-NO');assert.equal(calendarItemStatus({...todo,todoStatus:'IN-PROCESS'}),'Gjøremål · Pågår');setLocale('en-GB');
});

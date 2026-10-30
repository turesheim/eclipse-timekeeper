import {t} from './i18n.js';
import {icon,projectIcon} from './icons.js';
import {instantNanos,instantText,nanosText} from './model.js';
import {calendarTodo,calendarItemWhen,calendarItemStatus} from './calendar-item.js';

const node=(tag,text,css)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(css)value.className=css;return value;};
function line(glyph,text,css='tooltip-detail') {
  const row=node('div',undefined,css);if(glyph){glyph.removeAttribute('title');row.append(glyph);}row.append(node('span',text));return row;
}
function frame(title,glyph) {
  const root=node('div',undefined,'tooltip-details');root.append(line(glyph,title,'tooltip-heading'));return root;
}
export function workSessionsTooltip({client,sessions,projects=[],zone,asOf,total,date,format=nanosText}) {
  const root=frame(client?.name||t('ui.client'),icon('buildings'));
  if(date)root.append(line(icon('calendar'),date));
  root.append(line(icon('clock'),[total===undefined?null:format(total),t('count.work_sessions',{count:sessions.length})].filter(Boolean).join(' · ')));
  const list=node('div',undefined,'tooltip-sessions');
  for(const session of sessions){
    const entry=node('div',undefined,'tooltip-session'),project=projects.find(value=>value.id===session.projectId);
    if(project)entry.append(line(projectIcon(project)||icon('folder'),project.name,'tooltip-project'));
    entry.append(node('div',session.summary||t('ui.work_session_21aa04'),'tooltip-session-title'));
    entry.append(line(icon('clock'),instantText(session.start,zone)+' → '+(session.end?instantText(session.end,zone):t('ui.in_progress'))));
    const elapsed=(session.end?instantNanos(session.end):typeof asOf==='bigint'?asOf:instantNanos(asOf))-instantNanos(session.start);
    entry.append(line(icon(session.end?'hourglass-split':'start'),format(elapsed>0n?elapsed:0n)));
    list.append(entry);
  }
  root.append(list);return root;
}
export function calendarEventTooltip(event,zone) {
  const root=frame(event.title||t('calendar.untitled'),icon(calendarTodo(event)?'check2-square':'calendar-event'));
  root.append(line(icon('clock'),calendarItemWhen(event,zone)));
  if(event.clientName)root.append(line(icon('buildings'),event.clientName));
  if(event.calendarName&&event.calendarName!==event.clientName)root.append(line(icon('calendar'),event.calendarName));
  const status=calendarItemStatus(event);if(status)root.append(line(icon('info-circle'),status));
  if(event.location)root.append(line(icon('geo-alt'),event.location));
  if(event.description)root.append(node('div',event.description,'tooltip-description'));
  return root;
}

import {dateTimeFormat} from './formatters.js';
import {getLocale,t} from './i18n.js';
import {addDays,instantText} from './model.js';

export const calendarTodo=item=>item.componentType==='VTODO';
export const calendarItemStatus=item=>calendarTodo(item)?t('calendar.todo')+' · '+t('calendar.todo_status.'+(item.todoStatus||'NEEDS-ACTION')):t(item.activityIds?.length?'calendar.recorded':'calendar.planned');
export function calendarItemWhen(item,zone,compact=false){
  if(item.undated)return t('calendar.undated');
  if(!item.allDay){
    const format=compact?value=>dateTimeFormat(getLocale(),{hour:'2-digit',minute:'2-digit',timeZone:zone}).format(new Date(value)):value=>instantText(value,zone);
    return format(item.start)+(item.start===item.end?'':(compact?'–':' → ')+format(item.end));
  }
  if(compact)return t('calendar.all_day');
  const format=date=>dateTimeFormat(getLocale(),{dateStyle:'medium',timeZone:'UTC'}).format(new Date(date+'T12:00:00Z'));
  const last=addDays(item.endDate,-1);
  return t('calendar.all_day')+' · '+format(item.firstDate)+(last!==item.firstDate?' – '+format(last):'');
}

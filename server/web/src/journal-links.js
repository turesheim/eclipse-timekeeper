/** Daily-note links use the same bounded civil-date keys as the journal API. */
export function dailyNoteDate(value){
  if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||value.startsWith('0000'))return null;
  const date=new Date(value+'T12:00:00Z');
  return Number.isFinite(date.getTime())&&date.toISOString().slice(0,10)===value?value:null;
}
export const dailyNoteHref=date=>dailyNoteDate(date)?'/web/?day='+date:null;
export function dailyNoteDateFromSearch(search){const dates=new URLSearchParams(search).getAll('day');return dates.length===1?dailyNoteDate(dates[0]):null;}
export function withoutDailyWikilinks(value){
  return value.replace(/\[\[(\d{4}-\d{2}-\d{2})\]\]/g,(source,date,offset,input)=>
    dailyNoteDate(date)&&!['!','['].includes(input[offset-1])&&input[offset+source.length]!==']'?'':source);
}
export const dayNoteNavigation='timekeeper:open-day-note';
/** Dialogs contribute a non-mutating guard and a close action to one navigation. */
export function guardDayNoteNavigation(root,canLeave,leave){
  root.addEventListener(dayNoteNavigation,event=>{event.detail.guards.push(canLeave);event.detail.leave.push(leave);});
}

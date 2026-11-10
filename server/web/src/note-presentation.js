/** Shared note metadata presentation across navigation, results and calendars. */
export function noteIcon(entry,fallback='file-earmark-text'){
  if(entry?.kind==='FOLDER')return 'folder';
  if(entry?.bookmarked)return 'journal-bookmark';
  if(entry?.kind==='EVENT')return 'calendar-event';
  return ['DAY','WEEK'].includes(entry?.kind)?'journal-text':fallback;
}

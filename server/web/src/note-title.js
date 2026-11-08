import {emojiText} from './emoji.js';
/** A display title never replaces the collection path used by links and sync. */
export function noteTitle(entry,fallback=entry.path.split('/').at(-1)){
  if(typeof entry.title==='string'&&entry.title.trim())return emojiText(entry.title);
  const name=entry.path.split('/').at(-1);
  return entry.kind==='FOLDER'?fallback:name.replace(/\.md$/i,'');
}

import {emojiText} from './emoji.js';
/** A display title never replaces the collection path used by links and sync. */
export function noteTitle(entry,fallback=entry.path.split('/').at(-1)){
  return typeof entry.title==='string'&&entry.title.trim()?emojiText(entry.title):fallback;
}

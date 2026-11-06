import {emojiNames} from './assets/emoji/emoji.js';
export const emojiFor=name=>Object.hasOwn(emojiNames,name)?emojiNames[name]:null;
export function emojiSuggestions(query,limit=12){
  const term=query.toLowerCase();return Object.keys(emojiNames).filter(name=>name.includes(term)).sort((a,b)=>Number(b.startsWith(term))-Number(a.startsWith(term))).slice(0,limit).map(name=>({name,emoji:emojiNames[name]}));
}
/** Plain display labels only; document parsing separately respects code and escapes. */
export const emojiText=value=>value.replace(/:([a-z0-9_+-]{1,80}):/g,(source,name)=>emojiFor(name)||source);

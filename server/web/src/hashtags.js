/** The server uses the same bounded Unicode tag grammar and case folding. */
const segment='[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_-]*';
const token=new RegExp('(?<![\\p{L}\\p{M}\\p{N}_/#\\\\])#'+segment+'(?:/'+segment+')*','gu');
const whole=new RegExp('^#'+segment+'(?:/'+segment+')*$','u');
export const tagNavigation='timekeeper:notes-tag-navigation';
export function hashtagKey(value){
  if(typeof value!=='string')return null;
  const key=value.normalize('NFC').toUpperCase().toLowerCase().normalize('NFC');
  return key.length<=128&&whole.test(key)?key:null;
}
export function hashtagRanges(text){
  const prose=text.replace(/!?\[\[[^\]\r\n]*\]\]/g,value=>' '.repeat(value.length)),result=[];
  for(const match of prose.matchAll(token)){const key=hashtagKey(match[0]);if(key)result.push({from:match.index,to:match.index+match[0].length,key});}
  return result;
}

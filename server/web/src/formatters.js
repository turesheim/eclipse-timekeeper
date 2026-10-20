// Intl formatters own native browser resources. Reuse them across calendar
// boundary searches and timer paints, with a fixed bound for changing locales/zones.
const limit=32,dateFormats=new Map(),numberFormats=new Map();
function cached(cache,create,locale,options){
  const key=JSON.stringify([locale,Object.entries(options).filter(([,value])=>value!==undefined).sort(([a],[b])=>a<b?-1:a>b?1:0)]);
  let value=cache.get(key);
  if(value)cache.delete(key);
  else value=create(locale,options);
  cache.set(key,value);
  if(cache.size>limit)cache.delete(cache.keys().next().value);
  return value;
}
export const dateTimeFormat=(locale,options={})=>cached(dateFormats,(locale,options)=>new Intl.DateTimeFormat(locale,options),locale,options);
export const numberFormat=(locale,options={})=>cached(numberFormats,(locale,options)=>new Intl.NumberFormat(locale,options),locale,options);

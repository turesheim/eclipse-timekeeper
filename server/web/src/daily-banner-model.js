import {splitMarkdown} from './markdown-document.js';
import {wikiReference} from './wiki-links.js';
const validImage=reference=>{const value=wikiReference(reference,true);return !!value?.path&&!value.heading&&/\.(png|jpe?g|gif|webp)$/i.test(value.path);};

export const dailyBannerProperty='dagsverk_seasonal_banner';
const scalar=value=>{const match=/^("(?:[^"\\]|\\.)*")[ \t]*(?:#.*)?$/.exec(value);try{return match?JSON.parse(match[1]):null;}catch{return null;}};
/** A versioned string property keeps the saved forecast portable through ordinary Markdown clients. */
export function dailyBannerSnapshot(header){
  const lines=(header||'').split(/\r?\n/).map(line=>/^(?:dagsverk_seasonal_banner|"dagsverk_seasonal_banner"|'dagsverk_seasonal_banner')[ \t]*:[ \t]*(.*)$/.exec(line)).filter(Boolean);
  if(lines.length!==1)return null;
  try{
    const text=scalar(lines[0][1].trim());if(typeof text!=='string'||text.length>12000)return null;const value=JSON.parse(text);
    if(value.version!==1||!/^\d{4}-\d\d-\d\d$/.test(value.date)||!['winter','spring','summer','autumn','late-autumn'].includes(value.season)||typeof value.zoneId!=='string')return null;
    new Intl.DateTimeFormat('en',{timeZone:value.zoneId});
    const image=value.seasonal;if(!image||typeof image.reference!=='string'||!validImage(image.reference)||![image.x,image.y].every(n=>Number.isFinite(n)&&n>=0&&n<=1))return null;
    if(!Array.isArray(value.forecast)||![0,3].includes(value.forecast.length))return null;
    for(const slot of value.forecast)if(!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(slot.time)||typeof slot.label!=='string'||slot.label.length>100||typeof slot.temperature!=='string'||slot.temperature.length>30||!Number.isFinite(slot.temperatureCelsius)||Math.abs(slot.temperatureCelsius)>120||!['clear','fair','partlycloudy','cloudy','rain','snow','sleet','fog','thunder'].includes(slot.condition))return null;
    return value;
  }catch{return null;}
}
/** Change only owned top-level properties. Other YAML, comments, BOM and line endings remain byte-exact. */
export function replaceBanner(source,{reference,x=.5,y=.5,snapshot}={}){
  const parts=splitMarkdown(source);if(parts.unterminated)throw new Error('Invalid frontmatter');
  if(!validImage(reference)||![x,y].every(n=>Number.isFinite(n)&&n>=0&&n<=1))throw new Error('Invalid banner');
  const newline=parts.frontmatter.includes('\r\n')?'\r\n':source.includes('\r\n')?'\r\n':'\n';
  const changes=new Map([['banner',JSON.stringify('![['+reference+']]')],['banner_x',String(x)],['banner_y',String(y)]]);
  if(snapshot!==undefined)changes.set(dailyBannerProperty,JSON.stringify(JSON.stringify(snapshot)));
  if(!parts.frontmatter)return (source.startsWith('\uFEFF')?'\uFEFF':'')+'---'+newline+[...changes].map(([key,value])=>key+': '+value+newline).join('')+'---'+newline+source.replace(/^\uFEFF/,'');
  const lines=parts.frontmatter.match(/[^\n]*\n|[^\n]+$/g)||[],counts=new Map();
  for(let at=1;at<lines.length-1;at++){
    const match=/^(?:"(banner|banner_x|banner_y|dagsverk_seasonal_banner)"|'(banner|banner_x|banner_y|dagsverk_seasonal_banner)'|(banner|banner_x|banner_y|dagsverk_seasonal_banner))[ \t]*:/.exec(lines[at]);const key=match&&(match[1]||match[2]||match[3]);if(!changes.has(key))continue;
    if(/^[ \t]*[|>]/.test(lines[at].slice(match[0].length))||/^[ \t]+[^#\s]/.test(lines[at+1]||''))throw new Error('Unsupported banner metadata');counts.set(key,(counts.get(key)||0)+1);if(counts.get(key)>1)throw new Error('Ambiguous banner metadata');
    lines[at]=key+': '+changes.get(key)+newline;
  }
  const extra=[...changes].filter(([key])=>!counts.has(key)).map(([key,value])=>key+': '+value+newline).join('');lines.splice(lines.length-1,0,extra);
  return lines.join('')+parts.body;
}

import {wikiReference} from './wiki-links.js';
import {dailyBannerSnapshot} from './daily-banner-model.js';
import {t} from './i18n.js';

const weatherSymbols={clear:'☀',fair:'☀',partlycloudy:'⛅',cloudy:'☁',rain:'☂',snow:'❄',sleet:'❄',fog:'≋',thunder:'ϟ'};

const quoted=/^("(?:[^"\\]|\\.)*"|'(?:[^']|'')*')[ \t]*(?:#.*)?$/;
const scalar=value=>{
  const match=quoted.exec(value);if(!match)return null;
  try{return match[1][0]==='"'?JSON.parse(match[1]):match[1].slice(1,-1).replace(/''/g,"'");}catch{return null;}
};
const coordinate=value=>{
  if(typeof value!=='string')return .5;
  const number=value.replace(/[ \t]+#.*$/,'').trim();
  if(!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(number))return .5;
  const result=Number(number);return Number.isFinite(result)&&result>=0&&result<=1?result:.5;
};

/** Read a small YAML subset for presentation only; never serialize metadata. */
export function bannerMetadata(frontmatter){
  if(typeof frontmatter!=='string')return null;
  const lines=frontmatter.replace(/^\uFEFF/,'').split(/\r?\n/);
  if(lines.shift()!=='---')return null;
  while(lines.at(-1)==='')lines.pop();
  if(!['---','...'].includes(lines.pop()))return null;
  const values=new Map(),duplicates=new Set();
  for(const line of lines){
    if(!line.trim()||/^[ \t]*#/.test(line)||/^ /.test(line))continue;
    // Multiline quoted/flow scalars, aliases and merges are deliberately not
    // interpreted: a supported-looking line within them must not become a banner.
    const match=/^("[^"\\]*"|'(?:[^']|'')*'|[^:'"{}\[\],&*!?\t][^:]*):(?:[ \t]+(.*)|[ \t]*)$/.exec(line);
    if(!match)return null;
    const key=/^['"]/.test(match[1])?scalar(match[1]):match[1].trim(),value=match[2]||'';
    if(/^['"]/.test(value)&&scalar(value)===null)return null;
    if(/^[&*!]/.test(value)||/^[\[{]/.test(value)&&!/[}\]][ \t]*(?:#.*)?$/.test(value))return null;
    if(!['banner','banner_x','banner_y'].includes(key))continue;
    if(values.has(key))duplicates.add(key);values.set(key,value);
  }
  if(duplicates.has('banner'))return null;
  const value=scalar(values.get('banner')||''),embed=typeof value==='string'&&/^!?\[\[([^\r\n]+)\]\]$/.exec(value);
  const reference=embed&&wikiReference(embed[1],true);
  if(!reference?.path||reference.heading||!/\.(png|jpe?g|gif|webp)$/i.test(reference.path))return null;
  return {reference:reference.path,x:duplicates.has('banner_x') ? .5 : coordinate(values.get('banner_x')),y:duplicates.has('banner_y') ? .5 : coordinate(values.get('banner_y'))};
}

/** Decorative authenticated preview, separate from the editable Markdown DOM. */
export class MarkdownBanner{
  constructor(){
    this.generation=0;this.dom=document.createElement('div');this.dom.className='markdown-banner';this.dom.hidden=true;this.dom.setAttribute('aria-hidden','true');
  }
  clear({keepMetadata=false}={}){
    if(!keepMetadata)this.snapshot=null;
    ++this.generation;const previous=this.preview;this.preview=null;this.dom.hidden=true;previous?.destroy();this.dom.replaceChildren();
    this.dom.setAttribute('aria-hidden','true');
  }
  update(frontmatter,resources){
    this.clear();const current=this.generation;
    if(resources?.bannerFrontmatter){
      void resources.bannerFrontmatter().then(value=>{if(current===this.generation)this.show(value===null?frontmatter:value,resources);}).catch(()=>{});
    }else this.show(frontmatter,resources);
  }
  show(frontmatter,resources){
    this.snapshot=dailyBannerSnapshot(frontmatter);this.dom.dispatchEvent(new Event('timekeeper:banner-metadata'));
    const metadata=bannerMetadata(frontmatter);if(!metadata||!resources?.wikiImage)return;
    const preview=resources.wikiImage(metadata.reference,'');this.preview=preview;
    const image=preview.dom;image.loading='eager';image.alt='';image.style.objectPosition=metadata.x*100+'% '+metadata.y*100+'%';
    image.addEventListener('load',()=>{if(this.preview===preview&&image.naturalWidth>0)this.dom.hidden=false;});
    image.addEventListener('error',()=>{if(this.preview===preview)this.clear({keepMetadata:true});});
    this.dom.append(image);
    if(this.snapshot?.forecast.length===3){
      this.dom.removeAttribute('aria-hidden');
      const forecast=document.createElement('ul');forecast.className='banner-forecast';forecast.setAttribute('aria-label',t('daily_banner.saved_forecast',{date:this.snapshot.date,zone:this.snapshot.zoneId}));
      for(const slot of this.snapshot.forecast){
        const item=document.createElement('li'),time=document.createElement('time'),symbol=document.createElement('span'),label=document.createElement('span'),temperature=document.createElement('strong');
        time.textContent=slot.time;time.dateTime=this.snapshot.date+'T'+slot.time;symbol.textContent=weatherSymbols[slot.condition];symbol.className='banner-weather-symbol';symbol.setAttribute('aria-hidden','true');label.textContent=slot.label;label.className='banner-weather-label';temperature.textContent=slot.temperature;
        item.append(time,symbol,label,temperature);forecast.append(item);
      }
      const attribution=document.createElement('a');attribution.className='banner-weather-credit';attribution.href='https://www.met.no/en/free-meteorological-data/Licensing-and-crediting';attribution.target='_blank';attribution.rel='noopener noreferrer';attribution.textContent='MET Norway · CC BY 4.0';this.dom.append(forecast,attribution);
    }
  }
}

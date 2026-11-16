import {wikiReference} from './wiki-links.js';
import {dailyBannerSnapshot} from './daily-banner-model.js';
import {t} from './i18n.js';
import {dailyBannerProposal} from './daily-banner-presentation.js';
import {seasonalCatalog} from './seasonal-banner-catalog.js';

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

/** Decorative presentation stays outside the editable Markdown document. */
export class MarkdownBanner{
  constructor(){
    this.generation=0;this.dom=document.createElement('div');this.dom.className='markdown-banner';this.dom.hidden=true;this.dom.setAttribute('aria-hidden','true');
  }
  clear({keepMetadata=false}={}){
    this.metadataReady=false;
    if(!keepMetadata){this.snapshot=null;this.proposal=null;this.proposalState=null;}
    ++this.generation;if(this.preview!==this.shownPreview)this.preview?.destroy();this.shownPreview?.destroy();this.preview=this.shownPreview=null;this.imageKey=this.forecastKey=this.imageReference=null;this.dom.hidden=true;this.dom.replaceChildren();this.dom.setAttribute('aria-hidden','true');
  }
  update(frontmatter,resources){
    this.metadataReady=false;
    const identity=resources?.bannerIdentity?.()||{session:resources,origin:''};
    if(identity.session!==this.identity?.session||identity.origin!==this.identity?.origin)this.clear();
    this.identity=identity;this.resources=resources;const current=++this.generation;
    if(resources?.bannerFrontmatter){
      void resources.bannerFrontmatter().then(value=>{if(current===this.generation)this.show(value===null?frontmatter:value,resources);}).catch(()=>{});
    }else this.show(frontmatter,resources);
  }
  setProposal(context,preview,state={}){
    if(this.resources?.dailyBannerContext?.()?.date!==context.date)return;
    this.proposal=dailyBannerProposal(context,preview);this.proposalState=state;this.show(this.frontmatter||'',this.resources);
  }
  show(frontmatter,resources){
    this.metadataReady=true;
    this.frontmatter=frontmatter;this.snapshot=dailyBannerSnapshot(frontmatter);
    const context=resources?.dailyBannerContext?.(),metadata=bannerMetadata(frontmatter);
    const proposal=this.proposal?.date===context?.date?this.proposal:dailyBannerProposal(context);
    const savedArtwork=this.snapshot&&seasonalCatalog.find(art=>art.id===this.snapshot.seasonal.id&&art.version===this.snapshot.seasonal.version);
    const seasonal=!metadata&&!!proposal||!!savedArtwork&&metadata?.reference===this.snapshot.seasonal.reference;
    this.dom.classList.toggle('seasonal-banner',seasonal);
    const fallback=seasonal?(savedArtwork||proposal?.artwork):null;
    const imageKey=metadata?'private:'+metadata.reference+':'+(resources?.bannerVersion?.()??0):fallback?'public:'+fallback.path:null;
    if(imageKey!==this.imageKey){
      if(this.preview!==this.shownPreview)this.preview?.destroy();
      if(!metadata||metadata.reference!==this.imageReference){this.shownPreview?.destroy();this.shownPreview=null;this.dom.querySelector('img')?.remove();}
      this.preview=null;this.imageReference=metadata?.reference;this.imageKey=imageKey;this.dom.hidden=!this.dom.querySelector('img');
      let placeholder;
      if(fallback&&!this.dom.querySelector('img')){placeholder=document.createElement('img');placeholder.alt='';placeholder.loading='eager';placeholder.width=1800;placeholder.height=400;placeholder.src=fallback.path;this.dom.prepend(placeholder);this.dom.hidden=false;}
      if(metadata&&resources?.wikiImage){
        const preview=(seasonal&&resources.bannerImage?resources.bannerImage:resources.wikiImage)(metadata.reference,'');this.preview=preview;
        const image=preview.dom;image.loading='eager';image.alt='';
        image.addEventListener('load',()=>{if(this.preview!==preview||!image.naturalWidth)return;this.shownPreview?.destroy();this.dom.querySelector('img')?.remove();this.shownPreview=preview;this.dom.prepend(image);this.dom.hidden=false;});
        image.addEventListener('error',()=>{if(this.preview!==preview)return;preview.destroy();if(preview!==this.shownPreview)image.remove();this.preview=this.shownPreview;this.imageKey=null;if(!this.dom.querySelector('img'))this.dom.hidden=true;});
        if(!this.dom.querySelector('img'))this.dom.prepend(image);
      }
    }
    for(const image of [this.preview?.dom,this.dom.querySelector('img')].filter(Boolean))image.style.objectPosition=(metadata?.x??.5)*100+'% '+(metadata?.y??.5)*100+'%';
    const forecast=this.snapshot?.forecast.length===3?this.snapshot:proposal;
    const state=this.proposalState||{},forecastKey=JSON.stringify({forecast:forecast?.forecast,date:forecast?.date,zone:forecast?.zoneId,saved:!!this.snapshot?.forecast.length,location:!!context?.weatherLocation,pending:state.pending,failed:state.failed});
    if(forecastKey!==this.forecastKey){
      this.forecastKey=forecastKey;
      for(const node of this.dom.querySelectorAll('.banner-forecast,.banner-weather-credit,.banner-forecast-status'))node.remove();
      this.dom.setAttribute('aria-hidden','true');
      if(forecast?.forecast.length===3){
        this.dom.removeAttribute('aria-hidden');
        const list=document.createElement('ul');list.className='banner-forecast';list.setAttribute('aria-label',t(this.snapshot?.forecast.length?'daily_banner.saved_forecast':'daily_banner.forecast',{date:forecast.date,zone:forecast.zoneId}));
        for(const slot of forecast.forecast){
          const item=document.createElement('li'),time=document.createElement('time'),symbol=document.createElement('span'),label=document.createElement('span'),temperature=document.createElement('strong');
          time.textContent=slot.time;time.dateTime=forecast.date+'T'+slot.time;symbol.textContent=weatherSymbols[slot.condition];symbol.className='banner-weather-symbol';symbol.setAttribute('aria-hidden','true');label.textContent=slot.label;label.className='banner-weather-label';temperature.textContent=slot.temperature;
          item.append(time,symbol,label,temperature);list.append(item);
        }
        const credit=document.createElement('a');credit.className='banner-weather-credit';credit.href='https://www.met.no/en/free-meteorological-data/Licensing-and-crediting';credit.target='_blank';credit.rel='noopener noreferrer';credit.textContent='MET Norway · CC BY 4.0';this.dom.append(list,credit);
      }else if(context?.date){
        this.dom.removeAttribute('aria-hidden');const status=document.createElement('div');status.className='banner-forecast-status';
        const text=document.createElement('span');text.textContent=t(!context.weatherLocation?'daily_banner.location_missing':state.pending?'daily_banner.loading_forecast':'daily_banner.forecast_unavailable');status.append(text);
        if(!context.weatherLocation){const link=document.createElement('a');link.href='#account/profile';link.textContent=t('daily_banner.set_location');status.append(link);}
        else if(!state.pending){const retry=document.createElement('button');retry.type='button';retry.className='btn btn-sm btn-link';retry.textContent=t('daily_banner.retry_forecast');retry.addEventListener('click',()=>this.dom.dispatchEvent(new Event('timekeeper:banner-retry')));status.append(retry);}
        this.dom.append(status);
      }
    }
    this.dom.dispatchEvent(new Event('timekeeper:banner-metadata'));
  }
}

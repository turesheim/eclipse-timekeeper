import {t,getLocale} from './i18n.js';
import {splitMarkdown} from './markdown-document.js';
import {bannerMetadata} from './markdown-banner.js';
import {dailyBannerSnapshot,replaceBanner} from './daily-banner-model.js';
import {seasonalArtwork} from './seasonal-banner-catalog.js';
import {imagePreview} from './image-preview.js';
import {rasterType} from './notes-links.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=key=>{const value=node('button',t('daily_banner.'+key),'btn btn-outline-secondary');value.type='button';return value;};

/** Daily presentation changes use the complete, ETagged vault file after flushing prose. */
export function dailyBannerTools(f){
  const resources=f.resources;if(!resources?.readBanner)return;
  const group=node('div'),add=button('add'),change=button('change'),restore=button('restore');group.append(add,change,restore);f.addActionsGroup(group);
  f.syncDailyBanner=()=>{
    group.hidden=!resources.dailyBannerContext?.()||f.readOnly;
    const saved=f.banner.snapshot||dailyBannerSnapshot(f.frontmatter);
    add.hidden=!!saved;restore.hidden=!saved;
    for(const action of group.children)action.disabled=f.loading||f.readOnly;
  };
  f.banner.dom.addEventListener('timekeeper:banner-metadata',()=>{f.syncDailyBanner();f.syncActions();});
  const run=async operation=>{
    if(f.readOnly||f.loading||!resources.dailyBannerContext?.())return;
    const generation=f.generation,active=()=>generation===f.generation&&!f.readOnly;let saved=false;
    try{
      // Flush before locking: autosave refuses to save a loading editor.
      const context=await resources.readBanner();if(!active())return;
      f.loading=true;f.bannerBusy=true;f.controls();f.status.textContent='';
      const result=await operation(context,active);if(!result||!active())return;
      await resources.writeBanner(context,result);saved=true;
    }catch(error){if(active())f.status.textContent=error.message||t('daily_banner.failed');}
    finally{if(generation===f.generation){f.loading=false;f.bannerBusy=false;f.controls();if(saved)await resources.bannerChanged();}}
  };
  add.addEventListener('click',()=>void run(async(context,active)=>{
    const header=splitMarkdown(context.text).frontmatter;if(dailyBannerSnapshot(header))return;
    const preview=(await context.session.request('/daily-banners/'+context.date+'/preview')).data;if(!active())return;
    const artwork=seasonalArtwork(preview.season,preview.weather),response=await fetch(artwork.path,{credentials:'same-origin'});
    if(!response.ok)throw new Error(t('daily_banner.failed'));const bytes=new Uint8Array(await response.arrayBuffer());if(!active())return;
    const reference=await resources.uploadBanner(context,bytes,'png');if(!active())return;
    const temperature=new Intl.NumberFormat(getLocale(),{style:'unit',unit:'celsius',maximumFractionDigits:1});
    const snapshot={version:1,date:preview.date,zoneId:preview.zoneId,season:preview.season,seasonal:{reference,x:artwork.x,y:artwork.y,id:artwork.id,version:artwork.version},forecast:preview.forecast.map(slot=>({...slot,label:t('daily_banner.condition_'+slot.condition),temperature:temperature.format(slot.temperatureCelsius)})),provider:preview.provider,capturedAt:preview.capturedAt};
    // An existing user picture stays in place until the owner explicitly restores the seasonal art.
    return replaceBanner(context.text,{...(bannerMetadata(header)||snapshot.seasonal),snapshot});
  }));
  restore.addEventListener('click',()=>void run(async context=>{
    const snapshot=dailyBannerSnapshot(splitMarkdown(context.text).frontmatter);if(!snapshot)return;
    return replaceBanner(context.text,snapshot.seasonal);
  }));
  change.addEventListener('click',()=>void run(async(context,active)=>{
    const value=await chooseImage(f,context,active);if(!value||!active())return;
    let reference=value.reference;
    if(value.file){const bytes=new Uint8Array(await value.file.arrayBuffer());if(!rasterType(bytes))throw new Error(t('daily_banner.invalid_image'));reference=await resources.uploadBanner(context,bytes,value.extension);}
    if(!active())return;return replaceBanner(context.text,{reference,x:value.x,y:value.y});
  }));
}

function chooseImage(f,context,active){
  return new Promise(resolve=>{
    const dialog=node('dialog',undefined,'app-dialog banner-image-dialog shadow-lg'),title=node('h2',t('daily_banner.change'));title.id='banner-title-'+crypto.randomUUID();dialog.setAttribute('aria-labelledby',title.id);
    const makeField=(key,input)=>{const label=node('label',t('daily_banner.'+key));input.classList.add('form-control');label.append(input);dialog.append(label);return input;};
    dialog.append(title);const file=makeField('upload',node('input'));file.type='file';file.accept='image/png,image/jpeg,image/gif,image/webp';
    const search=makeField('search',node('input'));search.type='search';const select=makeField('existing',node('select'));select.append(new Option(t('daily_banner.choose'),''));
    const preview=node('div',undefined,'markdown-banner banner-image-preview');preview.setAttribute('aria-hidden','true');dialog.append(preview);
    const crop=axis=>{const value=makeField('crop_'+axis,node('input'));value.type='range';value.min='0';value.max='1';value.step='.01';value.value=String(bannerMetadata(splitMarkdown(context.text).frontmatter)?.[axis]??.5);return value;};
    const x=crop('x'),y=crop('y'),error=node('p','','alert alert-danger');error.hidden=true;error.setAttribute('role','alert');dialog.append(error);
    const actions=node('div',undefined,'dialog-actions'),cancel=button('cancel'),save=button('save');save.className='btn btn-primary';actions.append(cancel,save);dialog.append(actions);
    let shown,sequence=0;const position=()=>{if(shown)shown.dom.style.objectPosition=x.value*100+'% '+y.value*100+'%';};
    const paint=()=>{shown?.destroy();shown=null;preview.replaceChildren();if(file.files?.[0]){const picked=file.files[0];shown=imagePreview({alt:'',load:async()=>picked});}else if(select.value)shown=f.resources.wikiImage(select.value,'');if(shown)preview.append(shown.dom);position();save.disabled=!shown;};
    x.addEventListener('input',position);y.addEventListener('input',position);file.addEventListener('change',()=>{if(file.files?.[0])select.value='';paint();});select.addEventListener('change',()=>{file.value='';paint();});
    const lookup=async()=>{const current=++sequence;try{const entries=await f.resources.complete(search.value,{assetsOnly:true});if(!active()||current!==sequence||!dialog.open)return;const selected=select.value;select.replaceChildren(new Option(t('daily_banner.choose'),''));for(const entry of entries.filter(entry=>/\.(png|jpe?g|gif|webp)$/i.test(entry.path)))select.append(new Option(entry.path,entry.path));if(selected&&!entries.some(entry=>entry.path===selected))select.append(new Option(selected,selected));select.value=selected;}catch(failure){if(active()&&dialog.open){error.hidden=false;error.textContent=failure.message;}}};
    search.addEventListener('input',()=>void lookup());const original=bannerMetadata(splitMarkdown(context.text).frontmatter);if(original){select.append(new Option(original.reference,original.reference));select.value=original.reference;}
    let result=null;cancel.addEventListener('click',()=>dialog.close());save.addEventListener('click',()=>{
      const picked=file.files?.[0],extension=picked?.name.split('.').at(-1).toLowerCase();
      if(picked&&(picked.size>5_000_000||!['png','jpg','jpeg','gif','webp'].includes(extension))){error.hidden=false;error.textContent=t('daily_banner.invalid_image');return;}
      if(!active()||!picked&&!select.value)return;result={file:picked,extension:extension==='jpeg'?'jpg':extension,reference:select.value,x:Number(x.value),y:Number(y.value)};dialog.close();
    });
    dialog.addEventListener('close',()=>{++sequence;shown?.destroy();dialog.remove();if(f.dailyBannerDialog===dialog)f.dailyBannerDialog=null;resolve(result);},{once:true});
    f.dailyBannerDialog=dialog;f.root.append(dialog);dialog.showModal();paint();void lookup();
  });
}

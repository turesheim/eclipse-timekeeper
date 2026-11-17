import {form,field,node,button} from './account-ui.js';
import {t,getLocale} from './i18n.js';

/** Optional coordinates supply uncaptured daily forecasts; saved forecasts stay unchanged. */
export function weatherSettings(view,profile){
  const current=profile.data.weatherLocation;
  const editor=form(view,t('daily_banner.location'),t('ui.save'),async()=>{
    const empty=!latitude.value.trim()&&!longitude.value.trim();
    if(!empty&&(!latitude.value.trim()||!longitude.value.trim()))throw new Error(t('daily_banner.coordinates_required'));
    const location=empty?null:{name:name.value.trim(),latitude:Number(latitude.value),longitude:Number(longitude.value)};
    if(location&&(!Number.isFinite(location.latitude)||!Number.isFinite(location.longitude)||Math.abs(location.latitude)>90||Math.abs(location.longitude)>180))throw new Error(t('daily_banner.invalid_coordinates'));
    const reply=await view.session.request('/me',{method:'PUT',etag:profile.etag,body:{zoneId:profile.data.zoneId,weatherLocation:location}});
    if(!view.valid())return;Object.assign(profile,reply);view.clean(editor.value);editor.feedback.textContent=t('daily_banner.location_saved');void view.refreshProfile(reply).catch(()=>{});
  });
  const name=field(editor.value,t('daily_banner.location_name'),current?.name||'',{required:false,maxLength:120});
  const latitude=field(editor.value,t('daily_banner.latitude'),current?.latitude??'',{required:false,type:'number'}),longitude=field(editor.value,t('daily_banner.longitude'),current?.longitude??'',{required:false,type:'number'});
  latitude.min='-90';latitude.max='90';longitude.min='-180';longitude.max='180';latitude.step=longitude.step='any';
  editor.value.append(node('p',t('daily_banner.location_help'),'hint'));editor.finish();
  locationSearch(view,editor,name,latitude,longitude);
}

/** Search is a cancellable read; choosing a place stages an ordinary profile draft. */
function locationSearch(view,editor,name,latitude,longitude){
  const search=node('form',undefined,'weather-location-search');search.setAttribute('aria-label',t('weather.search_title'));
  const query=field(search,t('weather.search_query'),'',{type:'search',maxLength:120});query.minLength=2;query.disabled=!view.canWrite;
  const status=node('p','','hint');status.setAttribute('role','status');const results=node('ul',undefined,'weather-location-results');results.setAttribute('aria-label',t('weather.results'));results.hidden=true;
  const help=node('p',t('weather.search_help'),'hint'),credit=node('a',t('weather.credit'));credit.href='https://photon.komoot.io/';credit.target='_blank';credit.rel='noopener noreferrer';
  const submit=button(t('weather.search_button'),()=>{});submit.type='submit';submit.disabled=!view.canWrite;
  search.append(submit,help,status,results,credit);editor.value.before(search);
  let generation=0,controller;
  const clear=()=>{++generation;controller?.abort();controller=null;results.replaceChildren();results.hidden=true;status.textContent='';search.setAttribute('aria-busy','false');submit.disabled=!view.canWrite;};
  query.addEventListener('input',clear);view.cleanup.add(clear);
  search.addEventListener('submit',event=>{
    event.preventDefault();if(!view.valid()||!view.canWrite||controller||!search.reportValidity())return;
    const value=query.value.trim();if([...value].length<2){status.textContent=t('weather.query_short');query.focus();return;}clear();const cycle=generation,session=view.session;controller=new AbortController();
    const active=()=>view.valid()&&session===view.session&&cycle===generation;
    submit.disabled=true;search.setAttribute('aria-busy','true');status.textContent=t('weather.searching');
    void (async()=>{
      try{
        const reply=await session.request('/daily-banners/locations?'+new URLSearchParams({q:value,language:getLocale().startsWith('nb')?'nb':'en'}),{signal:controller.signal});if(!active())return;
        for(const location of reply.data.items){
          const item=node('li'),pick=button(location.name,()=>{
            if(!active()||!view.canWrite||view.busy)return;
            name.value=location.name;latitude.value=String(location.latitude);longitude.value=String(location.longitude);
            name.dispatchEvent(new Event('input',{bubbles:true}));status.textContent=t('weather.selected');name.focus();
          });pick.setAttribute('aria-label',t('weather.use_location',{name:location.name}));
          const coordinates=node('span',location.latitude+', '+location.longitude,'hint');item.append(pick,coordinates);results.append(item);
        }
        results.hidden=!results.children.length;status.textContent=t(results.hidden?'weather.no_results':'weather.choose_result');
      }catch(error){if(active()&&error.name!=='AbortError'){if(error.status===401)view.signOut(error.message);else status.textContent=t(error.status===429?'weather.busy':'weather.failed');}}
      finally{if(active()){controller=null;submit.disabled=!view.canWrite;search.setAttribute('aria-busy','false');}}
    })();
  });
}

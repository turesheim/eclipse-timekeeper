import {form,field,node} from './account-ui.js';
import {t} from './i18n.js';

/** Location is optional and is used only when an owner explicitly creates a seasonal banner. */
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
}

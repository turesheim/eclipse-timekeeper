import {t,getLocale} from './i18n.js';
import {seasonalArtwork} from './seasonal-banner-catalog.js';

export function seasonForDay(date,latitude=0){
  if(typeof date!=='string'||!/^\d{4}-\d\d-\d\d$/.test(date))return null;
  const instant=new Date(date+'T12:00:00Z');if(!Number.isFinite(instant.getTime())||instant.toISOString().slice(0,10)!==date)return null;
  const month=(instant.getUTCMonth()+(latitude<0?6:0))%12+1;
  return month===11?'late-autumn':month===12||month<=2?'winter':month<=5?'spring':month<=8?'summer':'autumn';
}
/** A proposal is presentation only until its note is saved with a private asset. */
export function dailyBannerProposal(context,preview){
  const civilSeason=seasonForDay(context?.date,context?.weatherLocation?.latitude);if(!civilSeason)return null;
  if(preview&&(preview.version!==1||preview.date!==context.date||typeof preview.zoneId!=='string'))return null;
  const season=preview?.season||civilSeason;if(!['winter','spring','summer','autumn','late-autumn'].includes(season))return null;
  const artwork=seasonalArtwork(season,preview?.weather||'cloudy');
  const temperature=new Intl.NumberFormat(getLocale(),{style:'unit',unit:'celsius',maximumFractionDigits:1});
  const slots=preview?.forecast,valid=slots?.length===3&&slots.every(slot=>/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(slot.time)&&Number.isFinite(slot.temperatureCelsius)&&Math.abs(slot.temperatureCelsius)<=120&&['clear','fair','partlycloudy','cloudy','rain','snow','sleet','fog','thunder'].includes(slot.condition));
  const forecast=valid?slots.map(slot=>({...slot,label:t('daily_banner.condition_'+slot.condition),temperature:temperature.format(slot.temperatureCelsius)})):[];
  return {version:1,date:context.date,zoneId:preview?.zoneId||context.zoneId||'UTC',season,artwork,forecast,...(preview?.provider?{provider:preview.provider}:{}),...(preview?.capturedAt?{capturedAt:preview.capturedAt}:{})};
}
export function capturedBanner(proposal,reference){
  const {artwork,...snapshot}=proposal;
  return {...snapshot,seasonal:{reference,x:artwork.x,y:artwork.y,id:artwork.id,version:artwork.version}};
}

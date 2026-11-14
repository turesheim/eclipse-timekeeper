import {t} from './i18n.js';

const providers={
  google:{name:'Google',url:'https://www.google.com/appsstatus/dashboard/',hosts:['google.com','googleapis.com']},
  microsoft:{name:'Microsoft',url:'https://status.cloud.microsoft/',hosts:['microsoft.com','office.com','office365.com','outlook.com','live.com']},
  apple:{name:'iCloud',url:'https://www.apple.com/support/systemstatus/',hosts:['icloud.com']}
};

/** Only known public status pages become links; private calendar URLs never do. */
export function calendarSource(value){
  const host=typeof value?.origin==='string'?value.origin.toLowerCase():'';
  const provider=(Object.hasOwn(providers,value?.providerId)?providers[value.providerId]:null)||Object.values(providers).find(provider=>provider.hosts.some(domain=>host===domain||host.endsWith('.'+domain)));
  return {label:provider?.name||host||t('calendar.subscription_source'),url:provider?.url||null};
}

export function calendarSourceFailure(value,message){
  const source=calendarSource(value),result=document.createElement('span');result.className='calendar-source-failure d-block text-break';
  result.append(document.createTextNode(t('calendar.source_failure',{name:value.name||t('calendar.untitled'),source:source.label,message})));
  if(source.url){const link=document.createElement('a');link.href=source.url;link.target='_blank';link.rel='noopener noreferrer';link.textContent=t('calendar.service_status',{provider:source.label});result.append(document.createTextNode(' '),link);}
  return result;
}

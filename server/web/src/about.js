import {getLocale,t} from './i18n.js';
import {selectedHolidayCountry} from './holidays.js';
import {thirdPartyNotices} from './third-party-notices.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);value.textContent=text;if(className)value.className=className;return value;};
const link=(text,href)=>{const value=node('a',text);value.href=href;value.target='_blank';value.rel='noopener noreferrer';return value;};

export function openAbout(dialog,preference){
  const country=selectedHolidayCountry(preference);
  dialog.querySelector('#about-holidays').textContent=country?t('holidays.country',{country:new Intl.DisplayNames([getLocale()],{type:'region'}).of(country)}):t(preference==='none'?'holidays.none':'holidays.unsupported_country');
  const list=dialog.querySelector('#about-components');list.replaceChildren();
  for(const component of thirdPartyNotices){
    const item=document.createElement('li'),heading=document.createElement('p');heading.className='about-component-title';
    heading.append(link(component.name,component.homepage),' ',node('span',component.version,'hint'));
    const licenses=document.createElement('p'),license=link(component.license,component.licenseUrl);
    license.setAttribute('aria-label',t('about.component_license',{name:component.name,license:component.license}));licenses.append(license);
    if(component.notices)licenses.append(' · ',link(t('about.dependency_notices'),component.notices));
    item.append(heading,node('p',component.copyright,'hint'),licenses);list.append(item);
  }
  dialog.showModal();dialog.querySelector('#about-title').focus();dialog.scrollTop=0;
}

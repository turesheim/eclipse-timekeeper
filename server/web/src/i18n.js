import i18next from './assets/i18next/i18next.js';
import {resources} from './locales/resources.js';

export const supportedLocales=['en-GB','en-US','nb-NO'];
export function browserLocale(languages=globalThis.navigator?.languages || [globalThis.navigator?.language || 'en-GB']) {
  for(const value of languages) {
    try {
      const [locale]=Intl.getCanonicalLocales(value),language=locale.split('-')[0];
      if(['nb','no','nn'].includes(language))return 'nb-NO';
      if(language==='en')return locale;
    } catch { /* Ignore invalid browser language entries. */ }
  }
  return 'en-GB';
}
let locale=browserLocale();
const language=()=>locale.startsWith('nb')?'nb':'en';
const instance=i18next.createInstance();
instance.init({resources,lng:language(),fallbackLng:'en',supportedLngs:['en','nb'],load:'languageOnly',
  initAsync:false,keySeparator:false,nsSeparator:false,interpolation:{escapeValue:false}});
export const getLocale=()=>locale;
export const t=(key,values={})=>instance.t(key,values);
export const localeForPreference=preference=>supportedLocales.includes(preference)?preference:browserLocale();
export function setLocale(preference) {
  locale=localeForPreference(preference);
  void instance.changeLanguage(language());
  if(globalThis.document)document.documentElement.lang=locale;
  return locale;
}
// Only explicitly marked interface text is translated. Account records and user
// input are never scanned or replaced, and translations never become HTML.
export function applyTranslations(root=globalThis.document) {
  if(!root)return;
  const translate=node=>{
    if(node.dataset.i18n)node.textContent=t(node.dataset.i18n);
    for(const attribute of ['title','placeholder','aria-label','alt']) {
      const key=node.getAttribute('data-i18n-'+attribute);
      if(key)node.setAttribute(attribute,t(key));
    }
  };
  if(root.nodeType===1)translate(root);
  for(const node of root.querySelectorAll('[data-i18n],[data-i18n-title],[data-i18n-placeholder],[data-i18n-aria-label],[data-i18n-alt]'))translate(node);
  if(globalThis.document)document.documentElement.lang=locale;
}

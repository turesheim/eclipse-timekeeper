import {countries} from './assets/date-holidays/countries.js';

const supported=new Set(countries),years=new Map();
let engine,attempt=0;
function loadEngine() {
  if(!engine) {
    const url=new URL('./assets/date-holidays/engine.js',import.meta.url);
    if(attempt)url.searchParams.set('retry',String(attempt));
    engine=import(url.href).then(module=>module.default).catch(error=>{engine=null;attempt++;throw error;});
  }
  return engine;
}
export function holidayRegion(locale) {
  try {const parsed=new Intl.Locale(locale),region=parsed.region||parsed.maximize().region;return supported.has(region)?region:null;}
  catch {return null;}
}
function yearHolidays(Holidays,country,locale,year) {
  const key=country+'|'+locale+'|'+year;
  if(years.has(key)){const value=years.get(key);years.delete(key);years.set(key,value);return value;}
  const language=new Intl.Locale(locale).language;
  const languages=language==='nb'||language==='nn'||language==='no'?['nb','no','en']:[language,'en'];
  // Holidays belong to civil calendar dates in their country. Account/browser
  // zones must not move Christmas or a substitute day to another date.
  const calendar=new Holidays(country,{languages,timezone:'UTC',types:['public']});
  const result=new Map();
  for(const holiday of calendar.getHolidays(year)) {
    if(holiday.type!=='public'||!/^\d{4}-\d{2}-\d{2} /.test(holiday.date))continue;
    const start=Date.parse(holiday.date.slice(0,10)+'T00:00:00Z'),end=holiday.end.getTime();
    if(!Number.isFinite(start)||!Number.isFinite(end)||end<=start)continue;
    for(let instant=start;instant<end;instant+=86400000) {
      const date=new Date(instant).toISOString().slice(0,10),values=result.get(date)||[];
      if(!values.some(value=>value.name===holiday.name))values.push({name:holiday.name,substitute:holiday.substitute===true});
      result.set(date,values);
    }
  }
  years.set(key,result);if(years.size>24)years.delete(years.keys().next().value);
  return result;
}
export function selectedHolidayCountry(preference,languages=globalThis.navigator?.languages || [globalThis.navigator?.language || 'en-GB']) {
  if(preference==='none')return null;
  return supported.has(preference)?preference:holidayRegion(languages[0] || 'en-GB');
}
export {countries};
export async function publicHolidayCalendar(calendar,locale,country=holidayRegion(locale)) {
  const days=new Map();
  if(!country || !supported.has(country))return {country,days,state:'unsupported-region'};
  const selectedYear=Number(calendar.month.slice(0,4));
  // Upstream uses Date's numeric-year constructor, which maps 0–99 to 1900–99.
  if(selectedYear<100)return {country,days,state:'unsupported-year'};
  const Holidays=await loadEngine(),visible=new Set(calendar.days.map(day=>day.date));
  const visibleYears=calendar.days.map(day=>Number(day.date.slice(0,4)));
  // Include the preceding year for a holiday interval crossing New Year.
  for(let year=Math.max(100,Math.min(...visibleYears)-1);year<=Math.max(...visibleYears);year++) {
    for(const [date,values] of yearHolidays(Holidays,country,locale,year))if(visible.has(date)) {
      const existing=days.get(date)||[];
      for(const value of values)if(!existing.some(item=>item.name===value.name))existing.push(value);
      days.set(date,existing);
    }
  }
  return {country,days,state:'ready'};
}

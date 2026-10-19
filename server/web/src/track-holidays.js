import {getLocale,t as i18nText} from './i18n.js';
import {icon} from './icons.js';
import {publicHolidayCalendar} from './holidays.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};

/** Read-only country calendars have their own loading, dialog and account lifecycle. */
export class TrackHolidays {
  constructor(root,grid,input) {
    this.root=root;this.grid=grid;this.input=input;this.active=false;
    this.holidayGeneration=0;
    this.holidayStatus=node('p',undefined,'hint track-holiday-status');this.holidayStatus.hidden=true;this.holidayStatus.tabIndex=-1;this.holidayStatus.setAttribute('role','status');
    this.holidayMessage=node('span');
    this.holidayRetry=node('button',i18nText('ui.retry'),'btn btn-link btn-sm');this.holidayRetry.type='button';this.holidayRetry.hidden=true;
    this.holidayRetry.addEventListener('click',()=>{if(!this.active)return;this.holidayKey=null;this.paintHolidays();});
    this.holidayCredit=node('a','date-holidays');this.holidayCredit.href='/web/assets/date-holidays/LICENSE';this.holidayCredit.target='_blank';this.holidayCredit.rel='noopener noreferrer';this.holidayCredit.title=i18nText('holidays.license');this.holidayCredit.setAttribute('aria-label',i18nText('holidays.license'));
    this.holidaySource=node('span');this.holidaySource.append(' · ',i18nText('holidays.data'), ' ',this.holidayCredit);
    this.holidayStatus.append(this.holidayMessage,' ',this.holidayRetry,this.holidaySource);this.grid.after(this.holidayStatus);
    this.holidayDialog=node('dialog',undefined,'app-dialog shadow-lg');this.holidayDialog.id='track-holiday-details';this.holidayDialog.setAttribute('aria-labelledby','track-holiday-title');
    this.holidayTitle=node('h2',i18nText('holidays.title'));this.holidayTitle.id='track-holiday-title';this.holidayContext=node('p',undefined,'hint');this.holidayList=node('ul');
    const holidayActions=node('div',undefined,'dialog-actions');this.holidayClose=node('button',i18nText('ui.close'),'btn btn-outline-secondary');this.holidayClose.type='button';this.holidayClose.addEventListener('click',()=>this.closeHoliday());holidayActions.append(this.holidayClose);
    this.holidayDialog.append(this.holidayTitle,this.holidayContext,this.holidayList,holidayActions);root.append(this.holidayDialog);
    this.holidayDialog.addEventListener('close',()=>{
      const date=this.holidayOrigin;this.holidayOrigin=null;
      if(date&&this.active&&!this.root.hidden)(this.grid.querySelector(`[data-track-holiday-date="${date}"]`)||this.input).focus();
    });
  }
  update(calendar,cells,focused) {
    this.active=true;this.calendar=calendar;this.holidayCells=cells;this.paintHolidays(focused);
  }
  hide() {this.closeHoliday();}
  reset() {
    this.active=false;++this.holidayGeneration;this.holidayKey=null;this.holidayData=null;this.holidayOrigin=null;this.calendar=null;this.holidayCells=null;
    this.closeHoliday();this.holidayList.replaceChildren();this.holidayContext.textContent='';this.holidayTitle.textContent=i18nText('holidays.title');this.holidayStatus.hidden=true;this.holidayMessage.textContent='';this.holidayRetry.hidden=true;this.holidaySource.hidden=true;
  }
  closeHoliday() {if(this.holidayDialog.open)this.holidayDialog.close();}
  openHoliday(date) {
    const holidays=this.holidayData?.days.get(date);if(!holidays?.length)return;
    this.holidayOrigin=date;
    const fullDate=new Intl.DateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(date+'T12:00:00Z'));
    this.holidayTitle.textContent=i18nText('holidays.on_date',{date:fullDate});this.holidayContext.textContent=this.holidayCountry();
    this.holidayList.replaceChildren(...holidays.map(value=>node('li',value.name)));
    if(!this.holidayDialog.open)this.holidayDialog.showModal();this.holidayClose.focus();
  }
  holidayCountry() {
    const country=this.holidayData?.country;
    if(!country)return '';
    let name;try{name=new Intl.DisplayNames([getLocale()],{type:'region'}).of(country);}catch{name=country;}
    return i18nText('holidays.country',{country:name});
  }
  applyHolidays(data,focused) {
    const retryFocused=document.activeElement===this.holidayRetry;
    this.holidayRetry.hidden=true;this.holidayRetry.disabled=false;
    this.holidaySource.hidden=data.state!=='ready';
    this.holidayMessage.textContent=data.state==='ready'?this.holidayCountry():i18nText(data.state==='unsupported-year'?'holidays.unsupported_year':'holidays.unsupported_region');
    for(const [date,holidays] of data.days) {
      const cell=this.holidayCells.get(date);if(!cell)continue;
      cell.classList.add('track-public-holiday');
      const names=holidays.map(value=>value.name).join(' · '),button=node('button',undefined,'btn btn-link btn-sm track-holiday-label');button.type='button';button.dataset.trackHolidayDate=date;
      const fullDate=new Intl.DateTimeFormat(getLocale(),{dateStyle:'full',timeZone:'UTC'}).format(new Date(date+'T12:00:00Z'));
      button.setAttribute('aria-haspopup','dialog');button.setAttribute('aria-label',i18nText('holidays.details',{date:fullDate,names}));button.title=names;
      button.append(icon('calendar-day'),node('span',names,'track-holiday-name'));button.addEventListener('click',()=>this.openHoliday(date));
      cell.querySelector('.track-date').after(button);
    }
    if(focused&&document.activeElement===document.body)this.grid.querySelector(`[data-track-holiday-date="${focused}"]`)?.focus();
    else if(retryFocused)this.holidayStatus.focus();
  }
  paintHolidays(focused) {
    if(!this.active||!this.calendar)return;
    const key=this.calendar.month+'|'+getLocale();
    if(this.holidayKey===key) {if(this.holidayData)this.applyHolidays(this.holidayData,focused);return;}
    const generation=++this.holidayGeneration;this.holidayKey=key;this.holidayData=null;
    this.holidayStatus.hidden=false;this.holidayMessage.textContent=i18nText('holidays.loading');this.holidaySource.hidden=true;this.holidayRetry.disabled=true;
    void publicHolidayCalendar(this.calendar,getLocale()).then(data=>{
      if(generation!==this.holidayGeneration||!this.active)return;
      this.holidayData=data;this.applyHolidays(data,focused);
    }).catch(()=>{
      if(generation!==this.holidayGeneration||!this.active)return;
      this.holidayMessage.textContent=i18nText('holidays.unavailable');this.holidayRetry.hidden=false;this.holidayRetry.disabled=false;
    });
  }
}

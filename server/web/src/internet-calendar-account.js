import {t} from './i18n.js';
import {ApiError} from './api.js';
import {instantText} from './model.js';
import {node} from './account-ui.js';

/** Calendar choices have their own draft and version; task-list saves cannot overwrite them. */
export function internetCalendarAccount(view,panel,{reload,clients,zone,controls:allControls}){
  const root=node('div',undefined,'mt-3'),heading=node('h4',t('internet_accounts.calendars'),'h5'),status=node('p','','hint'),error=node('p','','alert alert-warning'),form=node('form'),choices=node('div'),feedback=node('p','','form-feedback'),save=node('button',t('internet_accounts.save_calendars'),'btn btn-primary');
  feedback.setAttribute('role','status');status.setAttribute('role','status');save.type='submit';form.append(choices,feedback,save);root.append(heading,status,error,form);panel.append(root);
  let data=null,draftVersion=0,signature='';
  const controls=()=>{
    const enabled=view.canWrite&&!view.busy&&!view.uncertain&&data?.enabled;
    for(const input of form.querySelectorAll('input,select'))input.disabled=!enabled||input.dataset.unreadable==='true'||input.tagName==='SELECT'&&!form.querySelector(`input[value="${CSS.escape(input.dataset.calendarClient)}"]`)?.checked;
    save.disabled=!enabled||!clients.length;
  };
  form.addEventListener('change',()=>{view.dirty.add(form);controls();});
  form.addEventListener('submit',event=>{
    event.preventDefault();void view.run(async()=>{
      const selectedCalendars=[...form.querySelectorAll('input[name=selectedCalendars]:checked')].map(input=>input.value);
      const calendarMappings=Object.fromEntries([...form.querySelectorAll('select')].filter(input=>selectedCalendars.includes(input.dataset.calendarClient)).map(input=>[input.dataset.calendarClient,input.value]));
      const current=await view.session.request('/task-providers/'+data.id);if(!view.valid())return;
      if((current.data.calendarConfigurationVersion??0)!==draftVersion)throw new ApiError('STALE_VERSION',412);
      await view.session.request('/task-providers/'+data.id+'/calendars',{method:'PUT',etag:current.etag,body:{selectedCalendars,calendarMappings}});if(!view.valid())return;
      view.clean(form);feedback.textContent=t('internet_accounts.calendars_saved');await reload();
    },{submit:save,feedback,write:true}).finally(()=>{if(view.valid()){controls();allControls();}});
  });
  return {
    update(value){
      data=value;root.hidden=!value.services?.calendars;
      status.textContent=t('task_providers.last_success',{date:value.calendarLastSuccess?instantText(value.calendarLastSuccess,zone()):t('task_providers.never')});error.hidden=!value.calendarLastError;error.textContent=value.calendarLastError?t('internet_accounts.calendar_refresh_failed'):'';
      if(!view.dirty.has(form)){
        draftVersion=value.calendarConfigurationVersion??0;const next=JSON.stringify([value.availableCalendars,value.selectedCalendars,value.calendarMappings]);
        if(next!==signature){
          signature=next;choices.replaceChildren();const selected=value.selectedCalendars||[],available=[...(value.availableCalendars||[])];
          for(const id of selected)if(!available.some(item=>item.id===id))available.push({id,name:t('internet_accounts.unavailable_calendar'),readable:false});
          for(const calendar of available){
            const row=node('div',undefined,'mb-3'),label=node('label',undefined,'d-flex gap-2 align-items-start mb-2'),input=node('input',undefined,'form-check-input');input.type='checkbox';input.name='selectedCalendars';input.value=calendar.id;input.checked=selected.includes(calendar.id);input.dataset.unreadable=String(!calendar.readable&&!input.checked);label.append(input,node('span',calendar.name,'text-break'));row.append(label);
            const clientLabel=node('label',undefined,'d-block'),select=node('select',undefined,'form-select');select.dataset.calendarClient=calendar.id;
            for(const client of clients)select.append(new Option(client.name,client.id));select.value=value.calendarMappings?.[calendar.id]||clients[0]?.id||'';
            clientLabel.append(node('span',t('internet_accounts.calendar_client',{name:calendar.name}),'form-label'),select);row.append(clientLabel);
            if(!calendar.readable)row.append(node('p',t('internet_accounts.calendar_unreadable'),'hint'));choices.append(row);
          }
          if(!available.length)choices.append(node('p',t('internet_accounts.no_calendars'),'hint'));
          if(!clients.length)choices.append(node('p',t('internet_accounts.calendar_needs_client'),'hint'));
        }
      }
      controls();
    },
    controls,
    dispose(){view.clean(form);root.remove();}
  };
}

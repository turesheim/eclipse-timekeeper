import {t} from './i18n.js';
import {node,field,form,section} from './account-ui.js';
import {NotesFolderImport} from './notes-folder-import.js';
import {archiveDownload} from './archive-download.js';

const changed=()=>document.dispatchEvent(new Event('timekeeper:notes-changed'));

/** Notes preferences and guarded, browser-selected vault imports share account lifecycle. */
export async function renderNotesAccount(view){
  const [settings,info]=await Promise.all([view.session.request('/notes/settings'),view.session.request('/notes')]);
  if(!view.valid())return;
  const fields={},checks={};
  const preferences=form(view,t('notes.settings'),t('ui.save'),async()=>{
    const body={};
    for(const [name,input]of Object.entries(fields))if(input.value!==settings.data[name])body[name]=input.value;
    for(const [name,input]of Object.entries(checks))if(input.checked!==!!settings.data[name])body[name]=input.checked;
    const reply=await view.session.request('/notes/settings',{method:'PUT',etag:settings.etag,body});
    if(!view.valid())return;settings.data=reply.data;settings.etag=reply.etag;info.data.settings=reply.data;
    view.clean(preferences.value);preferences.feedback.textContent=t('notes.settings_saved');changed();
  });
  preferences.value.append(node('p',t('notes.settings_help'),'hint'));
  for(const name of ['journalFolder','eventsFolder','assetsFolder'])fields[name]=field(preferences.value,t('notes.'+name),settings.data[name],{maxLength:1024});
  for(const [name,label,help]of [['hideJournalFolder','notes.hide_journal','notes.hide_journal_help'],['syncObsidianConfig','notes.config_opt_in','notes.config_help']]){
    const wrap=node('div',undefined,'form-check my-3'),input=node('input',undefined,'form-check-input'),caption=node('label',t(label),'form-check-label'),hint=node('p',t(help),'hint');
    input.type='checkbox';input.id='notes-preference-'+name;input.checked=!!settings.data[name];caption.htmlFor=input.id;hint.id=input.id+'-help';input.setAttribute('aria-describedby',hint.id);wrap.append(input,caption);preferences.value.append(wrap,hint);checks[name]=input;
  }
  for(const input of [...Object.values(fields),...Object.values(checks)])input.disabled=!view.canWrite;
  preferences.finish();

  const card=section(view,t('notes.import_title'));
  let importer;
  const owner={root:card,session:view.session,info:info.data,valid:()=>view.valid(),
    get writable(){return view.canWrite&&!view.uncertain&&view.valid();},
    get busy(){return view.busy;},set busy(value){view.busy=value;card.setAttribute('aria-busy',String(value));},
    controls(){importer?.controls();},changed(){view.dirty.add(card);},clean(){view.clean(card);},
    fail(error){if(error.status===401)view.signOut(error.message);},
    async reload(){if(!importer.pending())view.clean(card);changed();}
  };
  importer=new NotesFolderImport(owner);importer.controls();view.cleanup.add(()=>importer.reset());
  const downloads=section(view,t('notes.export_title'));downloads.append(node('p',t('notes.export_help')));
  archiveDownload(view,downloads,{path:'/notes/export',fileName:`timekeeper-notes-${new Date().toISOString().slice(0,10)}.zip`});
  const connection=section(view,t('notes.webdav_url'));
  connection.append(node('code',new URL('/dav/notes/',location.origin).href,'notes-webdav-url'),node('p',t('notes.webdav_credentials'),'hint'));
  const tokens=node('a',t('notes.webdav_tokens'),'btn btn-outline-secondary');tokens.href='#account/tokens';
  const client=node('a',t('notes.download_client'),'btn btn-outline-secondary');client.href='/web/tools/webdav_sync.py';client.download='webdav_sync.py';
  const actions=node('div',undefined,'d-flex flex-wrap gap-2 mb-3');actions.append(client,tokens);connection.append(actions,node('p',t('notes.webdav_naming')),node('p',t('notes.webdav_concurrency'),'hint'));
  const guide=node('a',t('notes.sync_guide'));guide.href='https://github.com/turesheim/eclipse-timekeeper/blob/main/docs/operations/NOTES-WEBDAV.md';guide.target='_blank';guide.rel='noopener noreferrer';connection.append(guide);
}

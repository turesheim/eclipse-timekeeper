import {node,button} from './account-ui.js';
import {t} from './i18n.js';

/** Keep one private browser copy per export until replacement or sign-out. */
export function createArchiveDownloads(session){
  const entries=new Map(),controller=new AbortController();
  function get(path){
    if(!entries.has(path))entries.set(path,{ready:null,pending:null});
    return entries.get(path);
  }
  const clear=(entry)=>{
    if(!entry.ready)return;
    URL.revokeObjectURL(entry.ready.url);
    entry.ready=null;
  };
  return {
    get,
    prepare(path){
      const entry=get(path);
      if(entry.pending)return entry.pending;
      clear(entry);
      entry.pending=session.request(path,{binary:true,timeout:1800000,signal:controller.signal}).then(reply=>{
        if(!controller.signal.aborted)entry.ready={url:URL.createObjectURL(reply.data),fileName:reply.fileName};
      }).finally(()=>{entry.pending=null;});
      return entry.pending;
    },
    close(){
      controller.abort();
      for(const entry of entries.values())clear(entry);
      entries.clear();
    }
  };
}

export function archiveDownload(view,container,{path,fileName}){
  const downloads=view.archiveDownloads,entry=downloads.get(path);
  const feedback=node('p','','form-feedback');feedback.setAttribute('role','status');
  const prepare=button(t('ui.prepare_archive'),()=>{
    if(view.valid()&&!view.busy&&!entry.pending)void watch(downloads.prepare(path));
  },'btn btn-primary');
  container.append(prepare,feedback);

  function showReady(){
    if(!entry.ready)return;
    const link=node('a',t('ui.download_archive'),'btn btn-outline-secondary');
    link.href=entry.ready.url;link.download=entry.ready.fileName||fileName;
    feedback.replaceChildren(node('span',t('ui.your_private_archive_is_ready')),link);
  }
  async function watch(pending){
    prepare.disabled=true;
    const spinner=node('span',undefined,'spinner-border spinner-border-sm me-2');
    spinner.setAttribute('aria-hidden','true');
    feedback.replaceChildren(spinner,t('ui.preparing_archive'));
    try{
      await pending;
      if(view.valid())showReady();
    }catch(error){
      if(!view.valid())return;
      if(error.status===401)view.signOut(error.message);
      else feedback.textContent=error.message;
    }finally{
      if(view.valid())prepare.disabled=false;
    }
  }
  if(entry.pending)void watch(entry.pending);
  else showReady();
}

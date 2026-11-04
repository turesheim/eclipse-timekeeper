import {node,button} from './account-ui.js';
import {t} from './i18n.js';

/** Prepare one private browser copy; navigation and replacement revoke its URL. */
export function archiveDownload(view,container,{path,fileName}){
  let active=true;const urls=new Set(),feedback=node('p','','form-feedback');feedback.setAttribute('role','status');
  const clear=()=>{for(const url of urls)URL.revokeObjectURL(url);urls.clear();};
  const prepare=button(t('ui.prepare_archive'),()=>void view.run(async()=>{
    prepare.disabled=true;clear();feedback.textContent=t('ui.preparing_archive');
    try{
      const reply=await view.session.request(path,{binary:true,timeout:300000});if(!active||!view.valid())return;
      const url=URL.createObjectURL(reply.data);urls.add(url);const link=node('a',t('ui.download_archive'),'btn btn-outline-secondary');link.href=url;link.download=reply.fileName||fileName;
      feedback.replaceChildren(node('span',t('ui.your_private_archive_is_ready')),link);
    }finally{if(view.valid())prepare.disabled=false;}
  },{feedback}),'btn btn-primary');
  container.append(prepare,feedback);view.cleanup.add(()=>{active=false;clear();});return {prepare,feedback};
}

import {t,getLocale} from './i18n.js';
import {node,section,button,link} from './account-ui.js';
import {storageRatio,storageBytes} from './storage-format.js';

/** Logical account usage, including recovery items. No file contents are fetched. */
export async function renderStorage(view){
  const overview=section(view,t('storage.title')),content=node('div'),refresh=button(t('notes.refresh'),()=>void view.run(load));
  overview.append(node('p',t('storage.help'),'hint'),content,refresh);
  if(view.account?.administrator&&view.canWrite)overview.append(link(t('storage.manage'),'/admin/#notes','btn btn-outline-secondary ms-2'));
  let generation=0;
  const meter=(root,label,used,maximum,format)=>{
    const row=node('div',undefined,'mb-3'),heading=node('div',undefined,'d-flex justify-content-between gap-2 flex-wrap');
    const summary=t('storage.used_of',{used:format(used),limit:format(maximum)});
    heading.append(node('strong',label),node('span',summary,'hint'));
    const progress=node('div',undefined,'progress');progress.setAttribute('role','progressbar');progress.setAttribute('aria-label',label);progress.setAttribute('aria-valuemin','0');progress.setAttribute('aria-valuemax','100');progress.setAttribute('aria-valuenow',String(storageRatio(used,maximum)));progress.setAttribute('aria-valuetext',summary);
    const bar=node('div',undefined,'progress-bar'+(used>maximum?' bg-danger':''));bar.style.width=storageRatio(used,maximum)+'%';progress.append(bar);row.append(heading,progress);root.append(row);
  };
  async function load(){
    const current=++generation;refresh.disabled=true;
    try{
      const reply=await view.session.request('/notes/storage');if(!view.valid()||current!==generation)return;
      const data=reply.data,bytes=value=>storageBytes(value,getLocale()),count=value=>new Intl.NumberFormat(getLocale()).format(value);content.replaceChildren();
      meter(content,t('storage.total'),data.usage.bytes,data.limits.totalBytes,bytes);meter(content,t('storage.entries'),data.usage.entries,data.limits.entries,count);
      content.append(node('p',t('storage.entry_detail',{files:count(data.usage.files),folders:count(data.usage.folders)}),'hint'));
      if(data.usage.bytes>data.limits.totalBytes||data.usage.entries>data.limits.entries)content.append(node('p',t('storage.over_limit'),'alert alert-warning'));
      const breakdown=node('section');breakdown.append(node('h3',t('storage.breakdown')),node('p',t('storage.shared_allowance'),'hint'));
      for(const item of data.categories)meter(breakdown,t('storage.kind.'+item.kind),item.bytes,data.limits.totalBytes,bytes);content.append(breakdown);
      const files=node('section');files.append(node('h3',t('storage.file_limits')));meter(files,t('storage.largest_markdown'),data.largestMarkdownBytes,data.limits.markdownBytes,bytes);meter(files,t('storage.largest_attachment'),data.largestAttachmentBytes,data.limits.fileBytes,bytes);content.append(files);
    }finally{if(view.valid()&&current===generation)refresh.disabled=false;}
  }
  await load();
}

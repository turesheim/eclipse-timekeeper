import {t,getLocale} from './i18n.js';
import {readNotesMetadata,metadataEntry,importedFileDates} from './notes-import-metadata.js';
const node=(tag,text)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;return value;};
const button=(text,run)=>{const value=node('button',text);value.type='button';value.className='btn btn-outline-secondary';value.addEventListener('click',run);return value;};
const key=value=>value.toUpperCase().toLowerCase().normalize('NFC');
const eligible=row=>['ready','failed'].includes(row.status);
const number=value=>new Intl.NumberFormat(getLocale()).format(value);

/** Browser-selected files only. No local absolute path is ever sent to the server. */
export function folderImportPlan(files,{metadata=null,includeConfig=false}={}){
  const rows=[],seen=new Map(),folders=new Map();
  for(const file of files){
    if(file===metadata?.file)continue;
    const parts=(file.webkitRelativePath||'').split('/'),path=parts.slice(1).join('/').normalize('NFC');
    const row={file,path,status:'ready',selected:true};rows.push(row);
    if(parts.length<2||!path||parts.some(part=>!part||part==='..'||part==='.'||/[\\\u0000-\u001f\u007f]/.test(part))){row.status='skipped';row.reason=t('notes.import_invalid_path');continue;}
    const hidden=parts.slice(1).some(part=>part.startsWith('.')),config=parts.slice(1).some(part=>key(part)==='.obsidian');
    if(config&&!includeConfig||hidden&&!metadataEntry(metadata,path)&&!(config&&includeConfig)){row.status='skipped';row.reason=t('notes.import_hidden');continue;}
    const previous=seen.get(key(path));if(previous)row.status=previous.status='conflict';else seen.set(key(path),row);
    for(let end=2;end<parts.length;end++){
      const spelling=parts.slice(1,end).join('/'),id=key(spelling),folder=folders.get(id);
      if(folder){folder.rows.push(row);if(folder.spelling!==spelling){folder.conflict=true;for(const child of folder.rows)child.status='conflict';}else if(folder.conflict)row.status='conflict';}
      else folders.set(id,{spelling,rows:[row],conflict:false});
    }
  }
  for(const row of rows)row.selected=row.status==='ready';
  return rows.sort((a,b)=>a.path.localeCompare(b.path));
}
export class NotesFolderImport {
  constructor(owner){
    this.owner=owner;this.generation=0;this.rows=[];this.folders=new Map();
    this.input=node('input');this.input.type='file';this.input.multiple=true;this.input.setAttribute('webkitdirectory','');this.input.hidden=true;
    this.overwrite=node('input');this.overwrite.type='checkbox';this.overwrite.className='form-check-input';this.overwrite.id='notes-import-overwrite';
    const overwriteLabel=node('label',t('notes.import_overwrite'));overwriteLabel.className='form-check-label';overwriteLabel.htmlFor=this.overwrite.id;
    const overwriteControl=node('div');overwriteControl.className='form-check';overwriteControl.append(this.overwrite,overwriteLabel);
    const overwriteHelp=node('p',t('notes.import_overwrite_help'));overwriteHelp.className='hint';
    this.overwrite.addEventListener('change',()=>{for(const row of this.rows)if(row.existing&&!row.blocked&&!row.stale&&row.status!=='imported'){row.status=this.overwrite.checked?'ready':'conflict';row.selected=this.overwrite.checked;}this.owner.changed();this.reviewStatus();this.render();});
    this.button=button(t('notes.choose_folder'),()=>this.input.click());this.button.id='notes-import-folder';
    this.reviewPanel=node('div');this.reviewPanel.className='notes-import-review';this.reviewPanel.hidden=true;
    this.status=node('p');this.status.setAttribute('role','status');
    this.progress=node('div');this.progress.className='progress mb-3';this.progress.hidden=true;this.progress.setAttribute('role','progressbar');this.progress.setAttribute('aria-label',t('notes.import_progress_label'));this.progress.setAttribute('aria-valuemin','0');this.progress.setAttribute('aria-valuemax','100');
    this.bar=node('div');this.bar.className='progress-bar';this.progress.append(this.bar);
    this.list=node('ul');this.list.className='notes-import-files';this.list.tabIndex=0;this.list.setAttribute('aria-label',t('notes.import_files'));
    this.apply=button(t('notes.import_apply'),()=>void this.importFiles());this.apply.className='btn btn-primary';this.close=button(t('ui.cancel'),()=>this.reset());
    const actions=node('div');actions.className='dialog-actions';actions.append(this.close,this.apply);
    const help=node('p',t('notes.import_limits',{markdown:number(owner.info.limits.markdownBytes),file:number(owner.info.limits.fileBytes)}));help.className='hint';
    const dates=node('p',t('notes.import_dates_help'));dates.className='hint';
    this.reviewPanel.append(this.status,this.progress,this.list,actions);owner.root.append(node('p',t('notes.import_help')),help,dates,overwriteControl,overwriteHelp,this.button,this.input,this.reviewPanel);
    this.input.addEventListener('change',()=>{const files=[...this.input.files];this.input.value='';if(files.length)void this.review(files);});
  }
  entries(){return [...this.rows,...this.folders.values()].filter(row=>!row.folder||row.explicit);}
  pending(){return this.entries().some(row=>row.selected&&eligible(row));}
  controls(){this.render();}
  reviewStatus(){
    const selected=this.rows.filter(row=>row.selected&&eligible(row)),replacements=selected.filter(row=>row.existing).length;
    this.status.textContent=t(replacements?'notes.import_reviewed_overwrite':'notes.import_reviewed',{count:selected.length,replacements});
  }
  reset(){this.overwrite.checked=false;++this.generation;this.abort?.abort();this.rows=[];this.folders.clear();this.metadata=null;this.tree=[];this.processed=undefined;this.list.replaceChildren();this.reviewPanel.hidden=true;this.owner.clean();this.input.value='';this.working=this.applying=this.reviewFailed=false;this.status.textContent='';this.progress.hidden=true;this.render();}
  buildTree(){
    this.folders=new Map();const roots=[];
    const folder=path=>{
      const id=key(path);if(this.folders.has(id))return this.folders.get(id);
      const row={folder:true,path,status:'ready',selected:true,children:[]};this.folders.set(id,row);
      const parent=path.split('/').slice(0,-1).join('/');(parent?folder(parent).children:roots).push(row);return row;
    };
    for(const row of this.rows){const parent=row.path.split('/').slice(0,-1).join('/');(parent?folder(parent).children:roots).push(row);}
    for(const value of this.metadata?.entries.values()||[]){
      if(value.kind!=='FOLDER'||value.path.split('/').some(part=>key(part)==='.obsidian')&&!this.owner.info.settings.syncObsidianConfig)continue;
      Object.assign(folder(value.path),{explicit:true,dates:Object.fromEntries(['createdAt','modifiedAt','createdAtSource'].filter(name=>value[name]).map(name=>[name,value[name]]))});
    }
    for(const row of this.folders.values())if(row.dates?.createdAt&&!row.dates.createdAtSource)row.dates.createdAtSource='UNKNOWN';
    this.list.replaceChildren();
    const append=(rows,host)=>{
      rows.sort((a,b)=>Number(!!b.folder)-Number(!!a.folder)||a.path.localeCompare(b.path));
      for(const row of rows){
        const entry=node('li'),line=node('div');line.className='notes-import-row';
        const label=node('label'),input=node('input');input.type='checkbox';input.className='form-check-input';input.setAttribute('aria-label',row.path+(row.folder?'/':''));
        const title=node('span',(row.path.split('/').at(-1)||row.file?.name)+(row.folder?'/':''));title.className='notes-import-path';label.append(input,title);line.append(label);row.checkbox=input;row.label=node('span');row.label.className='notes-import-status';line.append(row.label);entry.append(line);host.append(entry);
        input.addEventListener('change',()=>{const select=value=>{if(eligible(value))value.selected=input.checked;for(const child of value.children||[])select(child);};select(row);this.owner.changed();this.reviewStatus();this.render();});
        if(row.folder){const list=node('ul');entry.append(list);append(row.children,list);}
      }
    };append(roots,this.list);this.tree=roots;
  }
  render(changed){
    const paint=row=>{
      if(!row.checkbox)return;
      if(!row.folder){row.label.textContent=t(row.selected&&eligible(row)&&row.existing?'notes.import_replace':'notes.import_'+(row.selected||!eligible(row)?row.status:'excluded'))+(row.reason?' · '+row.reason:'');row.checkbox.checked=!!row.selected;row.checkbox.disabled=!!this.working||!this.owner.writable||!eligible(row);return {total:eligible(row)||row.status==='imported'?1:0,selected:(eligible(row)||row.status==='imported')&&row.selected?1:0,editable:eligible(row)?1:0};}
      let total=row.explicit&&(eligible(row)||row.status==='imported')?1:0,selected=total&&row.selected?1:0,editable=row.explicit&&eligible(row)?1:0;
      for(const child of row.children){const value=paint(child);total+=value?.total||0;selected+=value?.selected||0;editable+=value?.editable||0;}
      row.checkbox.checked=total>0&&selected===total;row.checkbox.indeterminate=selected>0&&selected<total;row.checkbox.disabled=!!this.working||!this.owner.writable||!editable;
      row.label.textContent=row.explicit&&!eligible(row)?t('notes.import_'+row.status):'';return {total,selected,editable};
    };
    if(changed)paint(changed);else for(const row of this.tree||[])paint(row);
    this.button.disabled=this.input.disabled=!!this.working||!this.owner.writable||this.owner.busy;this.close.disabled=!!this.applying;this.overwrite.disabled=!!this.working||!this.owner.writable||this.owner.busy;
    const selected=this.entries().filter(row=>row.selected&&eligible(row));this.apply.textContent=t(selected.length&&selected.every(row=>row.status==='failed')?'ui.retry':selected.some(row=>row.existing)?'notes.import_apply_selected':'notes.import_apply');this.apply.disabled=!!this.working||this.reviewFailed||!this.owner.writable||!selected.length;
    if(!this.working&&!this.reviewFailed&&this.rows.length&&!this.progress.hidden)this.progress.setAttribute('aria-valuenow','100');
    if(this.processed!==undefined){const percent=this.total?Math.round(this.processed/this.total*100):0;this.progress.setAttribute('aria-valuenow',String(percent));this.progress.setAttribute('aria-valuetext',t('notes.import_progress_count',{count:this.processed,total:this.total}));this.bar.style.width=percent+'%';this.bar.textContent=percent+'%';}
  }
  async review(files){
    const owner=this.owner,session=owner.session,generation=++this.generation;this.abort?.abort();this.abort=new AbortController();this.rows=[];this.folders.clear();this.list.replaceChildren();this.tree=[];this.metadata=null;this.reviewFailed=false;this.working=true;this.processed=undefined;this.progress.hidden=true;this.status.textContent=t('notes.import_reviewing');this.render();this.reviewPanel.hidden=false;owner.changed();
    const active=()=>generation===this.generation&&session===owner.session&&owner.valid();
    try{
      this.metadata=await readNotesMetadata(files);if(!active())return;
      this.rows=folderImportPlan(files,{metadata:this.metadata,includeConfig:owner.info.settings.syncObsidianConfig});
      const selectedPaths=new Set(this.rows.map(row=>key(row.path)));
      for(const entry of this.metadata?.entries.values()||[])if(entry.kind==='FILE'&&!selectedPaths.has(key(entry.path)))this.rows.push({path:entry.path,status:'skipped',selected:false,reason:t('notes.import_not_present')});
      this.buildTree();this.render();
      const folders=new Set();
      for(const row of [...this.folders.values()].filter(row=>row.explicit)){
        if(!active())return;
        try{const reply=await session.request('/notes/resolve?'+new URLSearchParams({path:row.path}));if(reply.data.kind!=='FOLDER'){row.status='conflict';row.selected=false;}else folders.add(key(row.path));}
        catch(error){if([403,422].includes(error.status)){row.status='skipped';row.selected=false;row.reason=error.message;}else if(error.status!==404)throw error;}
      }
      for(const row of this.rows){
        if(!active())return;if(row.status!=='ready')continue;
        const limit=/\.md$/i.test(row.path)?owner.info.limits.markdownBytes:owner.info.limits.fileBytes;
        if(row.file.size>limit){row.status='skipped';row.selected=false;row.reason=t('notes.import_oversized',{size:number(row.file.size),limit:number(limit)});continue;}
        const metadata=await importedFileDates(row.file,metadataEntry(this.metadata,row.path),{signal:this.abort.signal});if(!active())return;row.dates=metadata.dates;if(metadata.changed)row.reason=t('notes.import_metadata_changed');
        try{const reply=await session.request('/notes/resolve?'+new URLSearchParams({path:row.path}));
          if(reply.data.kind==='FOLDER'){row.status='conflict';row.selected=false;row.blocked=true;}
          else{row.existing=reply.data;row.status=this.overwrite.checked?'ready':'conflict';row.selected=this.overwrite.checked;}}
        catch(error){if([403,422].includes(error.status)){row.status='skipped';row.selected=false;row.reason=error.message;continue;}if(error.status!==404)throw error;}
        if(!active())return;
        const parts=row.path.split('/');parts.pop();let path='';
        for(const part of parts){path+=(path?'/':'')+part;if(folders.has(key(path)))continue;try{const reply=await session.request('/notes/resolve?'+new URLSearchParams({path}));if(reply.data.kind!=='FOLDER'){row.status='conflict';row.selected=false;row.blocked=true;}else folders.add(key(path));}catch(error){if(error.status!==404)throw error;}if(!active())return;}
      }
      if(active())this.reviewStatus();
    }catch(error){if(active()){this.status.textContent=error.message;this.reviewFailed=true;}}
    finally{if(active()){this.working=false;this.render();}}
  }
  async importFiles(){
    const owner=this.owner;if(this.reviewFailed||this.working||!owner.writable||!owner.session||owner.busy||!this.pending())return;
    const session=owner.session,generation=this.generation,active=()=>generation===this.generation&&session===owner.session&&owner.valid()&&owner.writable;
    const pending=this.entries().filter(row=>row.selected&&eligible(row)).sort((a,b)=>Number(!!b.folder)-Number(!!a.folder)||a.path.split('/').length-b.path.split('/').length||a.path.localeCompare(b.path));
    this.total=pending.length;this.processed=0;this.progress.hidden=false;this.applying=this.working=owner.busy=true;owner.controls();this.render();const folders=new Set();
    const ensure=async path=>{
      if(!path||folders.has(key(path)))return;await ensure(path.split('/').slice(0,-1).join('/'));if(!active())return;
      try{const reply=await session.request('/notes/resolve?'+new URLSearchParams({path}));if(reply.data.kind!=='FOLDER')throw Object.assign(new Error(t('notes.import_conflict')),{status:409});}
      catch(error){if(error.status!==404)throw error;try{await session.request('/notes/folders',{method:'POST',body:{path,...this.folders.get(key(path))?.dates}});}catch(created){if(![405,409].includes(created.status))throw created;const reply=await session.request('/notes/resolve?'+new URLSearchParams({path}));if(reply.data.kind!=='FOLDER')throw created;}}
      if(active())folders.add(key(path));
    };
    try{
      for(const row of pending){
        if(!active())return;row.reason='';this.status.textContent=t('notes.import_progress',{path:row.path});
        try{
          await ensure(row.folder?row.path:row.path.split('/').slice(0,-1).join('/'));if(!active())return;
          if(!row.folder){
            if(row.existing)await session.request('/notes/entries/'+row.existing.id+'/import?'+new URLSearchParams(row.dates),{method:'PUT',etag:row.existing.entryEtag,rawBody:row.file});
            else await session.request('/notes/files?'+new URLSearchParams({path:row.path,...row.dates}),{method:'PUT',noneMatch:'*',rawBody:row.file});
          }
          if(!active())return;row.status='imported';
        }catch(error){if(!active())return;row.status=[404,409,412].includes(error.status)?'conflict':'failed';if(row.status==='conflict')row.stale=true;row.reason=error.message;if(error.status===401){owner.fail(error);return;}}
        this.processed++;this.render(row);
      }
      if(active()){this.status.textContent=t('notes.import_done',{count:this.rows.filter(row=>row.status==='imported').length});await owner.reload();}
    }finally{if(generation===this.generation){this.applying=this.working=false;if(session===owner.session&&owner.valid()){owner.busy=false;owner.controls();}this.render();}}
  }
}

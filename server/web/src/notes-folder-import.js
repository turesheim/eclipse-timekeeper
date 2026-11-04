import {t} from './i18n.js';
const node=(tag,text)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;return value;};
const button=(text,run)=>{const value=node('button',text);value.type='button';value.className='btn btn-outline-secondary';value.addEventListener('click',run);return value;};

/** Browser-selected files only. No local path is ever sent to the server. */
export function folderImportPlan(files){
  const rows=[],seen=new Map();
  for(const file of files){
    const parts=(file.webkitRelativePath||'').split('/'),path=parts.slice(1).join('/').normalize('NFC');
    const row={file,path,status:'ready'};rows.push(row);
    if(parts.length<2||!path||parts.some(part=>!part||part==='..'||part==='.'||/[\\\u0000-\u001f\u007f]/.test(part))){row.status='skipped';continue;}
    if(parts.slice(1).some(part=>part.startsWith('.'))){row.status='skipped';continue;}
    const key=path.toUpperCase().toLowerCase(),previous=seen.get(key);if(previous){row.status=previous.status='conflict';}else seen.set(key,row);
  }
  return rows.sort((a,b)=>a.path.localeCompare(b.path));
}
export class NotesFolderImport {
  constructor(owner){
    this.owner=owner;this.generation=0;this.rows=[];
    this.input=node('input');this.input.type='file';this.input.multiple=true;this.input.setAttribute('webkitdirectory','');this.input.hidden=true;
    this.button=button(t('notes.choose_folder'),()=>this.input.click());this.button.id='notes-import-folder';
    this.reviewPanel=node('div');this.reviewPanel.className='notes-import-review';this.reviewPanel.hidden=true;
    this.status=node('p');this.status.setAttribute('role','status');this.list=node('ul');this.list.className='notes-import-files';this.list.tabIndex=0;this.list.setAttribute('aria-label',t('notes.import_files'));
    this.apply=button(t('notes.import_apply'),()=>void this.importFiles());this.apply.className='btn btn-primary';this.close=button(t('ui.cancel'),()=>this.reset());
    const actions=node('div');actions.className='dialog-actions';actions.append(this.close,this.apply);
    this.reviewPanel.append(this.status,this.list,actions);owner.root.append(node('p',t('notes.import_help')),this.button,this.input,this.reviewPanel);
    this.input.addEventListener('change',()=>{const files=[...this.input.files];this.input.value='';if(files.length)void this.review(files);});
  }
  controls(){this.button.disabled=this.input.disabled=!this.owner.session||!this.owner.writable||this.owner.busy||this.working;this.render();}
  reset(){++this.generation;this.rows=[];this.renderedRows=null;this.list.replaceChildren();this.reviewPanel.hidden=true;this.owner.clean();this.input.value='';this.working=this.applying=this.reviewFailed=false;this.status.textContent='';}
  render(changed){
    if(this.renderedRows!==this.rows){
      this.renderedRows=this.rows;this.list.replaceChildren();
      for(const row of this.rows){const entry=node('li');row.label=node('span');entry.append(node('span',row.path||row.file.name),row.label);this.list.append(entry);}
    }
    for(const row of changed?[changed]:this.rows)row.label.textContent=t('notes.import_'+row.status)+(row.reason?' · '+row.reason:'');
    this.button.disabled=this.input.disabled=this.working||!this.owner.writable||this.owner.busy;this.close.disabled=!!this.applying;this.apply.textContent=t(!this.rows.some(row=>row.status==='ready')&&this.rows.some(row=>row.status==='failed')?'ui.retry':'notes.import_apply');this.apply.disabled=this.working||this.reviewFailed||!this.owner.writable||!this.rows.some(row=>row.status==='ready'||row.status==='failed');
  }
  async review(files){
    const owner=this.owner,session=owner.session,generation=++this.generation;this.rows=folderImportPlan(files);this.reviewFailed=false;this.working=true;this.status.textContent=t('notes.import_reviewing');this.render();this.reviewPanel.hidden=false;owner.changed();
    const active=()=>generation===this.generation&&session===owner.session&&owner.valid();
    try{
      const folders=new Set();
      for(const row of this.rows){
        if(!active())return;if(row.status!=='ready')continue;
        const limit=/\.md$/i.test(row.path)?owner.info?.limits.markdownBytes:owner.info?.limits.fileBytes;
        if(limit&&row.file.size>limit){row.status='skipped';continue;}
        try{await session.request('/notes/resolve?'+new URLSearchParams({path:row.path}));row.status='conflict';}
        catch(error){if(error.status===422){row.status='skipped';row.reason=error.message;continue;}if(error.status!==404)throw error;}
        if(!active())return;
        const parts=row.path.split('/');parts.pop();let path='';
        for(const part of parts){path+=(path?'/':'')+part;if(folders.has(path))continue;try{const reply=await session.request('/notes/resolve?'+new URLSearchParams({path}));if(reply.data.kind!=='FOLDER')row.status='conflict';else folders.add(path);}catch(error){if(error.status!==404)throw error;}if(!active())return;}
      }
      if(active())this.status.textContent=t('notes.import_reviewed',{count:this.rows.filter(row=>row.status==='ready').length});
    }catch(error){if(active()){this.status.textContent=error.message;for(const row of this.rows)if(row.status==='ready')row.status='failed';this.reviewFailed=true;}}
    finally{if(active()){this.working=false;this.render();}}
  }
  async importFiles(){
    const owner=this.owner;if(this.reviewFailed||this.working||!owner.writable||!owner.session||owner.busy)return;
    const session=owner.session,generation=this.generation,active=()=>generation===this.generation&&session===owner.session&&owner.valid()&&owner.writable;
    this.applying=this.working=owner.busy=true;owner.controls();this.render();const folders=new Set();
    try{
      for(const row of this.rows){
        if(!active())return;if(!['ready','failed'].includes(row.status))continue;
        row.reason='';this.status.textContent=t('notes.import_progress',{path:row.path});
        try{
          const parts=row.path.split('/');parts.pop();let path='';
          for(const part of parts){path+=(path?'/':'')+part;if(folders.has(path))continue;
            try{const reply=await session.request('/notes/resolve?'+new URLSearchParams({path}));if(reply.data.kind!=='FOLDER')throw new Error(t('notes.import_conflict'));}
            catch(error){if(error.status!==404)throw error;try{await session.request('/notes/folders',{method:'POST',body:{path}});}catch(created){if(![405,409].includes(created.status))throw created;const reply=await session.request('/notes/resolve?'+new URLSearchParams({path}));if(reply.data.kind!=='FOLDER')throw created;}}
            if(!active())return;folders.add(path);
          }
          if(!active())return;
          await session.request('/notes/files?'+new URLSearchParams({path:row.path}),{method:'PUT',noneMatch:'*',rawBody:row.file});
          if(!active())return;row.status='imported';
        }catch(error){if(!active())return;row.status=[409,412].includes(error.status)?'conflict':'failed';row.reason=error.message;if(error.status===401){owner.fail(error);return;}}
        this.render(row);
      }
      if(active()){this.status.textContent=t('notes.import_done',{count:this.rows.filter(row=>row.status==='imported').length});await owner.reload();}
    }finally{if(generation===this.generation){this.applying=this.working=false;if(session===owner.session&&owner.valid()){owner.busy=false;owner.controls();}this.render();}}
  }
}

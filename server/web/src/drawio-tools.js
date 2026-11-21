import {t} from './i18n.js';
import {splitMarkdown} from './markdown-document.js';
import {drawioPngInfo,samePngBytes} from './drawio-png.js';
import {updateImagePreviews} from './image-preview.js';
import {drawioOrigin,DrawioEditor} from './drawio-editor.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(label,run)=>{const value=node('button',label,'btn btn-outline-secondary');value.type='button';value.addEventListener('click',run);return value;};
const set=(element,key,value)=>{if(element[key]!==value)element[key]=value;};
const same=(a,b)=>!!a&&!!b&&a.session===b.session&&a.origin===b.origin;
let parser;

/** Original PNGs are attachments; editing their metadata never changes note text. */
export class DrawioTools {
  constructor(field){
    this.field=field;this.generation=0;this.group=node('div');
    this.insert=button(t('drawio.insert'),()=>void this.open('insert'));this.browse=button(t('drawio.files'),()=>void this.open('files'));this.create=button(t('drawio.new'),()=>void this.open('new'));this.create.hidden=true;this.group.append(this.create,this.insert,this.browse);field.addActionsGroup(this.group);
    this.dialog=node('dialog',undefined,'app-dialog drawio-dialog');this.heading=node('h2');this.heading.id='drawio-'+crypto.randomUUID();this.dialog.setAttribute('aria-labelledby',this.heading.id);
    this.help=node('p',t('drawio.help'),'hint');const guide=node('a',t('drawio.help_link'));guide.href='https://www.drawio.com/docs/manual/export/export-to-png/';guide.target='_blank';guide.rel='noopener noreferrer';
    this.list=node('ul',undefined,'drawio-files');this.savedName=node('p','','drawio-filename');this.preview=node('img',undefined,'drawio-file-preview');this.preview.alt=t('drawio.preview');this.preview.hidden=true;
    this.fileLabel=node('label',t('drawio.file_label'),'form-label');this.fileInput=node('input',undefined,'form-control');this.fileInput.type='file';this.fileInput.accept='.png,image/png';this.fileInput.setAttribute('aria-label',t('drawio.file_label'));this.fileInput.addEventListener('change',()=>void this.selectFile());
    this.altLabel=node('label',t('drawio.alt'),'form-label');this.alt=node('input',undefined,'form-control');this.alt.required=true;this.alt.maxLength=255;this.alt.setAttribute('aria-label',t('drawio.alt'));this.altLabel.append(this.alt);
    this.nameLabel=node('label',t('drawio.name'),'form-label');this.name=node('input',undefined,'form-control');this.name.required=true;this.name.maxLength=200;this.nameLabel.append(this.name);
    this.stage=node('div',undefined,'drawio-stage');this.stage.hidden=true;
    this.status=node('p','','hint');this.status.setAttribute('role','status');this.error=node('p','','alert alert-danger');this.error.setAttribute('role','alert');this.error.hidden=true;
    this.download=button(t('drawio.download'),()=>this.downloadFile());
    this.edit=button(t('drawio.edit'),()=>void this.openEditor(this.context?.blob));
    this.reload=button(t('drawio.reload'),()=>{
      if(confirm(t(this.editor?'drawio.reload_edit_confirm':'drawio.reload_confirm')))void this.loadAsset();
    });
    this.check=button(t('drawio.check'),()=>void this.checkSaved());
    this.cancel=button(t('ui.cancel'),()=>this.cancelDialog());
    this.apply=button(t('drawio.insert'),()=>void this.commit());this.apply.className='btn btn-primary';
    const actions=node('div',undefined,'dialog-actions');
    actions.append(this.download,this.edit,this.reload,this.check,this.cancel,this.apply);
    this.manual=node('div');this.manual.append(this.help,guide,this.list,this.savedName);
    this.details=node('div',undefined,'drawio-details');this.details.append(this.nameLabel,this.altLabel);
    this.dialog.append(this.heading,this.manual,this.preview,this.fileLabel,this.stage,this.details,this.status,this.error,actions);
    field.root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{
      event.stopPropagation();event.preventDefault();this.cancelDialog();
    });
    this.dialog.addEventListener('close',()=>{
      if(this.dialog.open)return;
      this.stopEditor();this.abort?.abort();++this.generation;
      this.fileInput.remove();this.bookmarkEditor?.release(this.bookmark);
      this.bookmark=null;this.bookmarkEditor=null;this.clearFile();this.context=null;this.reference=null;
      this.list.replaceChildren();this.error.textContent='';this.status.textContent='';this.savedName.textContent='';
      this.clearPreview();this.busy=this.saving=false;
      if(same(this.bound,this.current())&&field.root.getClientRects().length)field.focus();
      this.bound=null;
    });
    void drawioOrigin().then(origin=>{this.editorOrigin=origin;this.sync();});
  }
  current(){return this.field.resources.assetIdentity?.();}
  active(cycle){return this.dialog.open&&cycle===this.generation&&same(this.bound,this.current());}
  clearFile(){this.file=null;this.bytes=null;this.fileInfo=null;this.fileInput.value='';}
  sync(){
    const current=this.current();if(this.dialog.open&&!same(this.bound,current))this.dialog.close();
    const disabled=this.field.readOnly||this.field.loading||!this.field.onUpload;
    set(this.group,'hidden',!current?.session);
    set(this.create,'hidden',!this.editorOrigin);set(this.create,'disabled',disabled);
    set(this.insert,'disabled',disabled);set(this.browse,'disabled',this.field.loading||!current?.session);
    if(this.dialog.open)this.paint();this.field.syncActions();
  }
  paint(){
    const f=this.field,writable=!f.readOnly&&f.resources.canReplaceAsset?.();
    const creating=this.mode==='new',inserting=this.mode==='insert',editing=!!this.editor;
    this.dialog.classList.toggle('drawio-editor-dialog',editing);
    set(this.heading,'textContent',t(creating?'drawio.new':editing?'drawio.edit':inserting?'drawio.insert':'drawio.files'));
    set(this.apply,'textContent',t(editing?'drawio.save':inserting?'drawio.insert':'drawio.replace'));
    this.stage.hidden=!editing;this.stage.inert=!!this.saving;this.manual.hidden=editing;this.details.hidden=!inserting&&!creating;
    this.nameLabel.hidden=!creating;this.list.hidden=inserting;
    this.preview.hidden=!this.url||editing&&!this.reviewing;
    this.fileLabel.hidden=editing||creating||!inserting&&!writable;
    this.apply.hidden=!writable||creating&&!editing;
    this.edit.hidden=editing||inserting||creating||!writable||!this.editorOrigin||!this.info?.editable;
    this.edit.disabled=this.busy||this.stale||this.uncertain;
    this.fileInput.disabled=this.busy||!writable;this.name.disabled=this.alt.disabled=this.busy;
    this.apply.disabled=this.busy||f.loading||!writable||this.stale||this.uncertain
      ||(editing?!this.editorReady||!creating&&!this.info?.editable:!this.fileInfo?.editable||!inserting&&!this.info?.editable);
    this.download.hidden=editing||inserting||creating;
    this.download.disabled=this.busy||!this.context?.blob;
    this.cancel.disabled=!!this.saving;this.reload.hidden=!this.stale;this.check.hidden=!this.uncertain;
    this.reload.disabled=this.check.disabled=this.busy;
    for(const pick of this.list.querySelectorAll('button'))pick.disabled=this.busy;
    this.dialog.setAttribute('aria-busy',String(!!this.busy));
  }
  failure(error){this.error.textContent=error.message;this.error.hidden=false;}
  clearPreview(){
    this.preview.removeAttribute('src');this.preview.hidden=true;
    if(this.url)URL.revokeObjectURL(this.url);
    this.url=null;
  }
  stopEditor(){
    this.editorAbort?.abort();this.editorAbort=null;this.editor=null;this.editorReady=false;this.reviewing=false;
  }
  cancelDialog(){
    if(this.saving)return;
    if(this.editorReady&&!confirm(t('drawio.discard_edit')))return;
    if(!this.editor||this.mode==='new'||this.uncertain||this.stale){this.dialog.close();return;}
    this.stopEditor();this.clearFile();this.busy=false;this.status.textContent='';this.paint();
    (this.stale?this.reload:this.uncertain?this.check:this.edit).focus();
  }
  async openEditor(blob){
    if(!this.editorOrigin||this.editor||this.field.readOnly||this.busy||!this.field.resources.canReplaceAsset?.()
      ||this.mode!=='new'&&(!blob||!this.info?.editable))return;
    const cycle=this.generation;
    this.clearFile();this.editorAbort=new AbortController();
    const editor=new DrawioEditor(this.editorOrigin,this.editorAbort.signal);
    this.editor=editor;this.stage.append(editor.frame);this.busy=true;this.error.hidden=true;
    this.status.textContent=t('drawio.loading_editor');this.paint();this.cancel.focus();
    try{
      await editor.load(blob);
      if(!this.active(cycle)||this.editor!==editor)return;
      this.editorReady=true;this.status.textContent='';this.busy=false;this.paint();
      if(this.mode==='new')this.name.focus();else editor.frame.focus();
    }catch(error){
      if(!this.active(cycle)||this.editor!==editor)return;
      this.stopEditor();this.failure(new Error(t('drawio.editor_unavailable')));this.status.textContent='';
      this.busy=false;this.paint();this.cancel.focus();
    }
  }
  async open(mode){
    const f=this.field,current=this.current();
    if(!current?.session||this.dialog.open||mode!=='files'&&(f.readOnly||f.loading||!f.onUpload))return;
    this.fileLabel.append(this.fileInput);this.bound=current;this.mode=mode;this.context=null;this.info=null;this.reference=null;
    this.stale=this.uncertain=false;this.clearFile();this.alt.value='';this.name.value='';this.autoAlt='';
    this.error.hidden=true;this.status.textContent='';this.savedName.textContent='';this.clearPreview();this.list.replaceChildren();
    this.abort=new AbortController();const cycle=++this.generation;
    this.bookmarkEditor=mode==='files'?null:f.editor;
    this.bookmark=this.bookmarkEditor?.bookmark();
    this.selection={start:f.text.selectionStart,end:f.text.selectionEnd};
    this.dialog.showModal();this.paint();
    if(mode==='new'){void this.openEditor();return;}
    if(mode==='insert'){this.fileInput.focus();return;}
    this.busy=true;this.status.textContent=t('drawio.loading');this.paint();
    try{
      const source=splitMarkdown(this.field.value()).body;parser??=import('./assets/milkdown/markdown-preview.js');const images=(await parser).markdownImages(source);
      if(!this.active(cycle))return;
      const references=images.filter(value=>/\.png(?:$|[?#])/i.test(value.reference.split('|')[0])&&(value.kind==='wiki'||this.field.resources.allowsAsset?.(value.reference)));
      for(const value of references){const item=node('li'),pick=button(value.alt||value.reference,()=>{this.reference=value;this.clearFile();void this.loadAsset();});pick.classList.add('text-start');item.append(pick);this.list.append(item);}
      this.status.textContent=references.length?'':t('drawio.no_diagrams');
    }catch(error){if(this.active(cycle))this.failure(error);}
    finally{if(this.active(cycle)){this.busy=false;this.paint();this.list.querySelector('button')?.focus();}}
  }
  async selectFile(){
    const file=this.fileInput.files?.[0],cycle=this.generation;this.file=file;this.fileInfo=null;this.bytes=null;this.error.hidden=true;if(!file){this.paint();return;}
    this.busy=true;this.paint();
    try{
      if(file.size>5_000_000)throw new Error(t('markdown.asset_too_large'));
      const bytes=new Uint8Array(await file.arrayBuffer());if(!this.active(cycle)||this.file!==file)return;const info=drawioPngInfo(bytes);if(!info)throw new Error(t('drawio.invalid_png'));if(!info.editable)throw new Error(t('drawio.not_editable'));
      this.bytes=bytes;this.fileInfo=info;this.status.textContent=file.name;
      if(this.mode==='insert'&&(!this.alt.value||this.alt.value===this.autoAlt)){this.autoAlt=file.name.replace(/(?:\.drawio)?\.png$/i,'').replace(/[_-]+/g,' ').trim();this.alt.value=this.autoAlt;}
    }catch(error){if(this.active(cycle))this.failure(error);}
    finally{if(this.active(cycle)){this.busy=false;this.paint();}}
  }
  async loadAsset(){
    if(!this.reference||this.busy)return;const cycle=this.generation,previous=this.context;this.busy=true;this.error.hidden=true;this.status.textContent=t('drawio.loading');this.paint();
    try{
      const context=await this.field.resources.readAsset(this.reference,{signal:this.abort.signal}),bytes=new Uint8Array(await context.blob.arrayBuffer());if(!this.active(cycle))return;
      if(this.editor&&previous?.entry.id!==context.entry.id)throw new Error(t('drawio.asset_moved'));
      this.context=context;this.info=drawioPngInfo(bytes);this.stale=this.uncertain=false;
      this.reviewing=!!this.editor;this.savedName.textContent=context.entry.path;this.status.textContent=this.editor?'':this.file?.name||'';
      this.clearPreview();
      if(this.info){this.url=URL.createObjectURL(context.blob.slice(0,context.blob.size,'image/png'));this.preview.src=this.url;}
      if(!this.info?.editable)this.failure(new Error(t(this.info?'drawio.not_editable':'drawio.invalid_png')));
    }catch(error){
      if(this.active(cycle)&&error.name!=='AbortError'){
        if(!this.editor){this.context=null;this.info=null;this.clearPreview();this.savedName.textContent='';}
        this.status.textContent='';this.failure(error);
      }
    }
    finally{if(this.active(cycle)){this.busy=false;this.paint();}}
  }
  downloadFile(){
    if(!this.context||!same(this.bound,this.current()))return;
    const url=URL.createObjectURL(this.context.blob),link=node('a');link.href=url;link.download=this.context.entry.path.split('/').at(-1);link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  async commit(){
    const f=this.field,cycle=this.generation,editor=this.editor,creating=this.mode==='new';
    if(this.apply.disabled||!this.active(cycle)||f.readOnly||this.busy)return;
    const inserting=this.mode==='insert'||creating;
    let name;
    if(inserting){
      this.alt.value=this.alt.value.trim();if(!this.alt.reportValidity())return;
      if(creating){
        const base=this.name.value.trim().replace(/(?:\.drawio)?\.png$/i,'').replace(/\.drawio$/i,'').trim();
        this.name.setCustomValidity(!base||base==='.'||base==='..'||/[\\/\u0000-\u001f]/.test(base)?t('drawio.invalid_name'):'');
        if(!this.name.reportValidity())return;
        name=base+'.drawio.png';
      }else name=/\.png$/i.test(this.file.name)?this.file.name:this.file.name+'.drawio.png';
    }
    this.busy=this.saving=true;this.error.hidden=true;
    if(editor)this.status.textContent=t('drawio.saving');
    this.paint();
    try{
      if(editor){
        const bytes=await editor.exportPng();
        if(!this.active(cycle)||this.editor!==editor||f.readOnly)return;
        const info=drawioPngInfo(bytes);
        if(!info?.editable)throw new Error(t('drawio.export_failed'));
        this.bytes=bytes;this.fileInfo=info;this.file=new File([bytes],name??this.context.entry.path.split('/').at(-1),{type:'image/png'});
      }
      if(inserting){
        const file=creating?this.file:new File([this.file],name,{type:'image/png'});
        const success=await f.uploadFiles([file],this.bookmark,true,{label:this.alt.value,retry:false,selection:this.selection});
        if(this.active(cycle)){
          if(success)this.dialog.close();else this.failure(new Error(f.status.textContent||t('markdown.upload_failed')));
        }
        return;
      }
      await this.replace(cycle);
    }catch(error){if(this.active(cycle)&&error.name!=='AbortError')this.failure(error);}
    finally{if(this.active(cycle)){this.busy=this.saving=false;if(editor)this.status.textContent='';this.paint();}}
  }
  async replace(cycle){
    try{
      await this.field.resources.replaceAsset(this.context,this.file,{signal:this.abort.signal});
      if(this.active(cycle))this.saved(this.file);
    }catch(error){
      if(this.active(cycle)&&error.name!=='AbortError'){
        this.stale=error.status===412;this.uncertain=!error.status||error.status>=500;
        this.failure(new Error(t(this.stale?'drawio.stale':this.uncertain?'drawio.unknown':'drawio.not_saved')));
      }
    }
  }
  saved(blob){updateImagePreviews(this.context.session,this.context.entry.id,blob);this.saving=false;this.dialog.close();document.dispatchEvent(new CustomEvent('timekeeper:notes-changed'));}
  async checkSaved(){
    const cycle=this.generation;if(!this.uncertain||this.busy||!same(this.bound,this.current()))return;this.busy=true;this.paint();
    try{
      const current=await this.field.resources.readAsset(this.reference,{signal:this.abort.signal}),bytes=new Uint8Array(await current.blob.arrayBuffer());if(!this.active(cycle))return;
      const sameEntry=current.entry.id===this.context.entry.id;
      if(sameEntry&&samePngBytes(bytes,this.bytes)){this.saved(current.blob);return;}
      this.uncertain=false;this.stale=!sameEntry||current.etag!==this.context.etag;this.failure(new Error(t(this.stale?'drawio.stale':'drawio.not_saved')));
    }catch(error){if(this.active(cycle)&&error.name!=='AbortError')this.failure(error);}
    finally{if(this.active(cycle)){this.busy=false;this.paint();}}
  }
}

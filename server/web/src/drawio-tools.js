import {t} from './i18n.js';
import {iconButton} from './icons.js';
import {splitMarkdown} from './markdown-document.js';
import {drawioPngInfo,samePngBytes,withDrawioDescription} from './drawio-png.js';
import {updateImagePreviews} from './image-preview.js';
import {drawioEndpoint,DrawioEditor} from './drawio-editor.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=(label,run)=>{const value=node('button',label,'btn btn-outline-secondary');value.type='button';value.addEventListener('click',run);return value;};
const set=(element,key,value)=>{if(element[key]!==value)element[key]=value;};
const same=(a,b)=>!!a&&!!b&&a.session===b.session&&a.origin===b.origin;
const pngReference=value=>typeof value==='string'&&/\.png(?:$|[?#])/i.test(value);
let parser;

/** Original PNGs are attachments; editing their metadata never changes note text. */
export class DrawioTools {
  constructor(field){
    this.field=field;this.generation=0;this.group=node('div');
    const editSelected=()=>{
      const reference=this.selectedReference();
      if(reference)void this.open('files',reference);
    };
    this.insert=button(t('drawio.insert'),()=>void this.open('new'));this.insert.hidden=true;
    this.browse=button(t('drawio.files'),()=>void this.open('files'));
    this.insertExisting=button(t('drawio.insert_existing'),()=>void this.open('existing'));
    this.selectedEdit=button(t('drawio.edit'),editSelected);this.selectedEdit.hidden=true;
    this.sourceEdit=iconButton(button('',()=>{
      if(this.sourceDiagram?.editable)void this.open('files',this.sourceDiagram.reference);
    }),'pencil',t('drawio.edit'));
    this.sourceEdit.classList.add('diagram-source-edit');
    this.sourceEdit.setAttribute('aria-keyshortcuts','Alt+Enter');
    this.sourceEdit.hidden=true;
    field.text.after(this.sourceEdit);
    const schedule=()=>{
      if(this.sourceFrame)return;
      this.sourceFrame=requestAnimationFrame(()=>{
        this.sourceFrame=null;void this.syncSourceEdit();
      });
    };
    for(const name of ['focus','blur','input','keyup','click','select','scroll'])field.text.addEventListener(name,schedule);
    this.sourceEdit.addEventListener('blur',schedule);
    document.addEventListener('selectionchange',()=>{if(document.activeElement===field.text)schedule();});
    window.addEventListener('resize',schedule);
    field.text.addEventListener('keydown',event=>{
      if(event.altKey&&event.key==='Enter'&&!this.sourceEdit.hidden){event.preventDefault();this.sourceEdit.focus();}
    });
    this.group.append(this.insert,this.insertExisting,this.selectedEdit,this.browse);field.addActionsGroup(this.group);
    this.dialog=node('dialog',undefined,'app-dialog drawio-dialog');this.heading=node('h2');this.heading.id='drawio-'+crypto.randomUUID();this.dialog.setAttribute('aria-labelledby',this.heading.id);
    this.resize=button(t('drawio.fill_window'),()=>{
      this.dialog.classList.toggle('drawio-fill-window');
      this.paint();
    });
    const headingRow=node('div',undefined,'drawio-heading');
    headingRow.append(this.heading,this.resize);
    this.help=node('p',t('drawio.help'),'hint');const guide=node('a',t('drawio.help_link'));guide.href='https://www.drawio.com/docs/manual/export/export-to-png/';guide.target='_blank';guide.rel='noopener noreferrer';
    this.searchLabel=node('label',t('drawio.search'),'form-label');
    this.search=node('input',undefined,'form-control');this.search.type='search';
    this.searchLabel.append(this.search);this.search.addEventListener('input',()=>this.paintDiagrams());
    this.list=node('ul',undefined,'drawio-files');this.savedName=node('p','','drawio-filename');this.preview=node('img',undefined,'drawio-file-preview');this.preview.alt=t('drawio.preview');this.preview.hidden=true;
    this.fileLabel=node('label',t('drawio.file_label'),'form-label');this.fileInput=node('input',undefined,'form-control');this.fileInput.type='file';this.fileInput.accept='.png,image/png';this.fileInput.setAttribute('aria-label',t('drawio.file_label'));this.fileInput.addEventListener('change',()=>void this.selectFile());
    this.altLabel=node('label',t('drawio.alt'),'form-label');this.alt=node('input',undefined,'form-control');this.alt.maxLength=255;this.alt.setAttribute('aria-label',t('drawio.alt'));this.altLabel.append(this.alt);
    this.nameLabel=node('label',t('drawio.name'),'form-label');this.name=node('input',undefined,'form-control');this.name.maxLength=200;this.nameLabel.append(this.name);
    this.stage=node('div',undefined,'drawio-stage');this.stage.hidden=true;
    this.status=node('p','','hint');this.status.setAttribute('role','status');this.error=node('p','','alert alert-danger');this.error.setAttribute('role','alert');this.error.hidden=true;
    this.download=button(t('drawio.download'),()=>this.downloadFile());
    this.edit=button(t('drawio.edit'),()=>void this.openEditor(this.context?.blob));
    this.reload=button(t('drawio.reload'),()=>{
      if(confirm(t(this.editor?'drawio.reload_edit_confirm':'drawio.reload_confirm')))void this.loadAsset();
    });
    this.check=button(t('drawio.check'),()=>void this.checkSaved());
    this.cancel=button(t('ui.cancel'),()=>this.cancelDialog());
    this.apply=button(t('drawio.save'),()=>void this.commit());this.apply.className='btn btn-primary';
    const actions=node('div',undefined,'dialog-actions');
    actions.append(this.download,this.edit,this.reload,this.check,this.cancel,this.apply);
    this.manual=node('div');this.manual.append(this.help,guide,this.searchLabel,this.list,this.savedName);
    this.exportGuide=guide;
    this.details=node('div',undefined,'drawio-details');this.details.append(this.nameLabel,this.altLabel);
    this.dialog.append(headingRow,this.manual,this.preview,this.fileLabel,this.stage,this.details,this.status,this.error,actions);
    field.root.append(this.dialog);
    this.dialog.addEventListener('cancel',event=>{
      event.stopPropagation();event.preventDefault();this.cancelDialog();
    });
    this.dialog.addEventListener('close',()=>{
      if(this.dialog.open)return;
      this.stopEditor();this.abort?.abort();++this.generation;
      this.fileInput.remove();this.bookmarkEditor?.release(this.bookmark);
      this.bookmark=null;this.bookmarkEditor=null;this.clearFile();this.context=null;this.reference=null;
      this.diagrams=null;this.list.replaceChildren();this.error.textContent='';this.status.textContent='';this.savedName.textContent='';
      this.clearPreview();this.busy=this.saving=false;
      if(same(this.bound,this.current())&&field.root.getClientRects().length)field.focus();
      this.bound=null;
    });
    void drawioEndpoint().then(endpoint=>{this.editorEndpoint=endpoint;this.sync();});
  }
  current(){return this.field.resources.assetIdentity?.();}
  active(cycle){return this.dialog.open&&cycle===this.generation&&same(this.bound,this.current());}
  clearFile(){this.file=null;this.bytes=null;this.fileInfo=null;this.fileInput.value='';}
  sync(){
    const current=this.current();if(this.dialog.open&&!same(this.bound,current))this.dialog.close();
    const f=this.field;
    if(this.sourceImages?.generation!==f.generation)this.sourceImages=null;
    set(this.group,'hidden',!current?.session);
    set(this.insert,'hidden',!this.editorEndpoint);
    set(this.insert,'disabled',f.readOnly||f.loading||!f.onUpload||!f.resources.canReplaceAsset?.());
    set(this.browse,'disabled',f.loading||!current?.session);
    set(this.insertExisting,'hidden',!f.resources.listDiagrams);
    set(this.insertExisting,'disabled',f.readOnly||f.loading||!f.resources.canReplaceAsset?.());
    f.root.classList.toggle('diagram-editable',!!this.editorEndpoint&&!f.readOnly&&!f.loading&&!!f.resources.canReplaceAsset?.());
    if(this.dialog.open)this.paint();
    if(f.text===document.activeElement||!this.sourceEdit.hidden)void this.syncSourceEdit();
    f.syncActions();
  }
  selectedReference(){
    const image=this.field.editor?.imageDetails();
    if(!pngReference(image?.path)||!image.embed&&!this.field.resources.allowsAsset?.(image.path))return null;
    return {kind:image.embed?'wiki':'image',reference:image.path};
  }
  syncSelection(){
    const f=this.field;
    const selected=!f.readOnly&&!f.loading&&f.resources.canReplaceAsset?.()&&!!this.selectedReference();
    set(this.selectedEdit,'hidden',!selected);

  }
  async syncSourceEdit(){
    const f=this.field,text=f.text,active=document.activeElement;
    if(active===this.sourceEdit&&!f.readOnly&&!f.loading)return;
    const body=text.value,position=text.selectionStart,generation=f.generation;
    if(f.editor||f.readOnly||f.loading||!this.editorEndpoint||!f.resources.canReplaceAsset?.()
        ||active!==text||position!==text.selectionEnd||!/\.png/i.test(body)){
      this.sourceEdit.hidden=true;return;
    }
    const valid=()=>generation===f.generation&&document.activeElement===text&&!f.editor
      &&!f.loading&&!f.readOnly&&text.value===body&&text.selectionStart===position&&text.selectionEnd===position;
    try{
      if(this.sourceImages?.body!==body){
        this.sourceEdit.hidden=true;
        parser??=import('./assets/milkdown/markdown-preview.js');
        const {markdownImages}=await parser;
        if(!valid())return;
        this.sourceImages={body,generation,images:markdownImages(body,{ranges:true})};
      }
      const reference=this.sourceImages.images.find(image=>image.start<=position&&position<=image.end
        &&pngReference(image.reference.split('|')[0])&&(image.kind==='wiki'||f.resources.allowsAsset?.(image.reference)));
      if(!reference){this.sourceEdit.hidden=true;return;}
      const key=JSON.stringify([generation,reference.kind,reference.reference]);
      if(this.sourceDiagram?.key!==key){
        this.sourceEdit.hidden=true;
        const candidate={key,reference};
        this.sourceDiagram=candidate;
        candidate.read=f.resources.readAsset(reference).then(async({blob})=>blob.size<=5_000_000
          &&!!drawioPngInfo(new Uint8Array(await blob.arrayBuffer()))?.editable).catch(()=>false);
      }
      const candidate=this.sourceDiagram;
      const editable=await candidate.read;
      if(!valid()||candidate!==this.sourceDiagram)return;
      candidate.editable=editable;
      const rect=editable?f.caret.sourceRect(text):null;
      this.sourceEdit.hidden=!rect||rect.top<Math.max(0,rect.box.top)||rect.top+rect.height>Math.min(innerHeight,rect.box.bottom);
      if(!this.sourceEdit.hidden){
        this.sourceEdit.style.left=Math.min(rect.box.right-38,innerWidth-38)+'px';
        this.sourceEdit.style.top=rect.top+'px';
      }
    }catch{if(valid())this.sourceEdit.hidden=true;}
  }
  decoratePreview(preview,reference){
    if(!preview?.observeDiagram||!pngReference(reference.reference.split('|')[0]))return preview;
    const {dom:image}=preview,dom=node('span',undefined,'markdown-diagram');
    const caption=node('span',undefined,'markdown-diagram-caption');
    caption.hidden=true;
    const edit=iconButton(button('',()=>void this.open('files',reference)),'pencil',t('drawio.edit'));
    edit.className='btn btn-light icon-button markdown-diagram-edit';edit.hidden=true;
    dom.contentEditable='false';dom.append(image,caption,edit);
    preview.observeDiagram(info=>{
      dom.classList.toggle('markdown-diagram-ready',!!info?.editable);
      caption.textContent=info?.description||'';
      caption.hidden=!info?.editable||!info.description;
      edit.hidden=!info?.editable;
    });
    return {...preview,dom,stopEvent:event=>edit.contains(event.target)||!!preview.stopEvent?.(event)};
  }
  paint(){
    const f=this.field,writable=!f.readOnly&&f.resources.canReplaceAsset?.();
    const creating=this.mode==='new',inserting=this.mode==='existing',editing=!!this.editor;
    this.dialog.classList.toggle('drawio-editor-dialog',editing);
    this.resize.hidden=!editing;
    set(this.resize,'textContent',t(this.dialog.classList.contains('drawio-fill-window')?'drawio.restore_size':'drawio.fill_window'));
    set(this.heading,'textContent',t(creating?'drawio.new':inserting?'drawio.insert_existing':editing||this.direct?'drawio.edit':'drawio.files'));
    set(this.apply,'textContent',t(editing?'drawio.save':'drawio.replace'));
    this.stage.hidden=!editing;this.stage.inert=!!this.saving;this.manual.hidden=editing||creating;
    this.help.hidden=this.exportGuide.hidden=inserting;this.searchLabel.hidden=!inserting;
    this.details.hidden=!editing;this.nameLabel.hidden=!creating;this.list.hidden=this.direct;
    this.preview.hidden=!this.url||editing&&!this.reviewing;
    this.fileLabel.hidden=editing||creating||inserting||!writable;
    this.apply.hidden=inserting||!writable||creating&&!editing;
    this.edit.hidden=editing||creating||!writable||!this.editorEndpoint||!this.info?.editable;
    this.edit.disabled=this.busy||this.stale||this.uncertain;
    this.fileInput.disabled=this.busy||!writable;this.name.disabled=this.alt.disabled=this.busy;
    this.apply.disabled=this.busy||f.loading||!writable||this.stale||this.uncertain
      ||(editing?!this.editorReady||!creating&&!this.info?.editable:!this.fileInfo?.editable||!this.info?.editable);
    this.download.hidden=editing||creating||inserting;
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
    this.dialog.classList.remove('drawio-fill-window');
    this.editorAbort?.abort();this.editorAbort=null;this.editor=null;this.editorReady=false;this.reviewing=false;
  }
  cancelDialog(){
    if(this.saving)return;
    const dirty=this.editor?.changed||this.editorReady&&this.alt.value.trim()!==this.originalDescription;
    if(dirty&&!confirm(t('drawio.discard_edit')))return;
    if(!dirty||!this.editor||this.mode==='new'||this.direct||this.uncertain||this.stale){this.dialog.close();return;}
    this.stopEditor();this.clearFile();this.busy=false;this.status.textContent='';this.paint();
    (this.stale?this.reload:this.uncertain?this.check:this.edit).focus();
  }
  async openEditor(blob){
    if(!this.editorEndpoint||this.editor||this.field.readOnly||this.busy||!this.field.resources.canReplaceAsset?.()
      ||this.mode!=='new'&&(!blob||!this.info?.editable))return;
    const cycle=this.generation;
    this.clearFile();this.editorAbort=new AbortController();
    this.originalDescription=this.info?.description||'';
    this.alt.value=this.originalDescription;
    const editor=new DrawioEditor(this.editorEndpoint,this.editorAbort.signal,()=>this.cancelDialog());
    this.editor=editor;this.stage.append(editor.frame);this.busy=true;this.error.hidden=true;
    this.status.textContent=t('drawio.loading_editor');this.paint();this.cancel.focus();
    try{
      await editor.load(blob);
      if(!this.active(cycle)||this.editor!==editor)return;
      this.editorReady=true;this.status.textContent='';this.busy=false;this.paint();
      editor.frame.focus();
    }catch(error){
      if(!this.active(cycle)||this.editor!==editor)return;
      this.stopEditor();this.failure(new Error(t('drawio.editor_unavailable')));this.status.textContent='';
      this.busy=false;this.paint();this.cancel.focus();
    }
  }
  async open(mode,reference=null){
    const f=this.field,current=this.current(),creating=mode==='new';
    if(!current?.session||this.dialog.open||f.loading||creating&&this.insert.disabled
      ||reference&&(f.readOnly||!f.resources.canReplaceAsset?.()))return;
    this.fileLabel.append(this.fileInput);this.bound=current;this.mode=mode;this.context=null;this.info=null;
    this.direct=!!reference;this.reference=reference;
    this.stale=this.uncertain=false;this.clearFile();this.alt.value='';this.name.value='';
    this.error.hidden=true;this.status.textContent='';this.savedName.textContent='';this.clearPreview();this.list.replaceChildren();
    this.abort=new AbortController();const cycle=++this.generation;
    this.bookmarkEditor=creating||mode==='existing'?f.editor:null;
    this.bookmark=this.bookmarkEditor?.bookmark();
    this.selection={start:f.text.selectionStart,end:f.text.selectionEnd};
    this.dialog.showModal();this.paint();
    if(creating){void this.openEditor();return;}
    if(mode==='existing'){void this.loadDiagrams(cycle);return;}
    if(reference){
      await this.loadAsset();
      if(this.active(cycle))void this.openEditor(this.context?.blob);
      return;
    }
    this.busy=true;this.status.textContent=t('drawio.loading');this.paint();
    try{
      const source=splitMarkdown(this.field.value()).body;parser??=import('./assets/milkdown/markdown-preview.js');const images=(await parser).markdownImages(source);
      if(!this.active(cycle))return;
      const references=images.filter(value=>pngReference(value.reference.split('|')[0])&&(value.kind==='wiki'||this.field.resources.allowsAsset?.(value.reference)));
      for(const value of references){const item=node('li'),pick=button(value.alt||value.reference,()=>{this.reference=value;this.clearFile();void this.loadAsset();});pick.classList.add('text-start');item.append(pick);this.list.append(item);}
      this.status.textContent=references.length?'':t('drawio.no_diagrams');
    }catch(error){if(this.active(cycle))this.failure(error);}
    finally{if(this.active(cycle)){this.busy=false;this.paint();this.list.querySelector('button')?.focus();}}
  }
  async loadDiagrams(cycle){
    this.diagrams=null;this.search.value='';
    this.busy=true;this.status.textContent=t('drawio.loading');this.paint();
    this.search.focus();
    try{
      const diagrams=await this.field.resources.listDiagrams({signal:this.abort.signal});
      if(this.active(cycle)){this.diagrams=diagrams;this.paintDiagrams();}
    }catch(error){if(this.active(cycle)&&error.name!=='AbortError'){this.status.textContent='';this.failure(error);}}
    finally{if(this.active(cycle)){this.busy=false;this.paint();}}
  }
  paintDiagrams(){
    if(!this.diagrams)return;
    const query=this.search.value.trim().normalize('NFC').toLocaleLowerCase();
    this.list.replaceChildren();
    for(const {entry,description} of this.diagrams){
      if(![entry.path,description].some(text=>text.normalize('NFC').toLocaleLowerCase().includes(query)))continue;
      const label=description||entry.path.split('/').at(-1);
      const item=node('li'),pick=button(label,()=>void this.insertReference(entry,label));
      pick.classList.add('text-start');item.append(pick);
      if(description)item.append(node('div',entry.path,'drawio-filename hint'));
      this.list.append(item);
    }
    this.status.textContent=this.list.children.length?'':t('drawio.no_matches');
  }
  async insertReference(entry,label){
    const f=this.field,cycle=this.generation;
    if(!this.active(cycle)||this.busy||f.readOnly)return;
    this.busy=true;this.error.hidden=true;this.paint();
    try{
      const path=await f.resources.assetReference(entry);
      if(!this.active(cycle)||f.readOnly)return;
      const safeLabel=label.replace(/[\[\]\\\r\n]/g,' ').trim();
      if(this.bookmarkEditor){
        if(!this.bookmarkEditor.insertAt(this.bookmark,'image',{path,label:safeLabel}))throw new Error(t('markdown.upload_failed'));
      }else{
        const {start,end}=this.selection;
        f.insertSource(`![${safeLabel}](<${path}>)`,start,end);
      }
      this.dialog.close();
    }catch(error){if(this.active(cycle))this.failure(error);}
    finally{if(this.active(cycle)){this.busy=false;this.paint();}}
  }
  async selectFile(){
    const file=this.fileInput.files?.[0],cycle=this.generation;this.file=file;this.fileInfo=null;this.bytes=null;this.error.hidden=true;if(!file){this.paint();return;}
    this.busy=true;this.paint();
    try{
      if(file.size>5_000_000)throw new Error(t('markdown.asset_too_large'));
      const bytes=new Uint8Array(await file.arrayBuffer());if(!this.active(cycle)||this.file!==file)return;const info=drawioPngInfo(bytes);if(!info)throw new Error(t('drawio.invalid_png'));if(!info.editable)throw new Error(t('drawio.not_editable'));
      this.bytes=bytes;this.fileInfo=info;this.status.textContent=file.name;
    }catch(error){if(this.active(cycle))this.failure(error);}
    finally{if(this.active(cycle)){this.busy=false;this.paint();}}
  }
  async loadAsset(){
    if(!this.reference||this.busy)return;const cycle=this.generation,previous=this.context;this.busy=true;this.error.hidden=true;this.status.textContent=t('drawio.loading');this.paint();
    try{
      const context=await this.field.resources.readAsset(this.reference,{signal:this.abort.signal}),bytes=new Uint8Array(await context.blob.arrayBuffer());if(!this.active(cycle))return;
      if(this.editor&&previous?.entry.id!==context.entry.id)throw new Error(t('drawio.asset_moved'));
      this.context=context;this.info=drawioPngInfo(bytes);this.stale=this.uncertain=false;
      this.reviewing=!!this.editor;this.savedName.textContent=context.entry.path;
      this.status.textContent=this.info?.editable&&!this.editorEndpoint&&!this.field.readOnly
        ?t('drawio.editor_not_configured'):this.editor?'':this.file?.name||'';
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
    let name;
    if(creating){
      const supplied=this.name.value.trim();
      const base=supplied.replace(/(?:\.drawio)?\.png$/i,'').replace(/\.drawio$/i,'').trim();
      this.name.setCustomValidity(supplied&&!base||base==='.'||base==='..'||/[\\/\u0000-\u001f]/.test(base)?t('drawio.invalid_name'):'');
      if(!this.name.reportValidity())return;
      name=(base||'diagram-'+crypto.randomUUID())+'.drawio.png';
    }
    this.busy=this.saving=true;this.error.hidden=true;
    if(editor)this.status.textContent=t('drawio.saving');
    this.paint();
    try{
      if(editor){
        const png=await editor.exportPng();
        if(!this.active(cycle)||this.editor!==editor||f.readOnly)return;
        const bytes=withDrawioDescription(png,this.alt.value);
        if(!bytes)throw new Error(t('drawio.export_failed'));
        const info=drawioPngInfo(bytes);
        this.bytes=bytes;this.fileInfo=info;this.file=new File([bytes],name??this.context.entry.path.split('/').at(-1),{type:'image/png'});
      }
      if(creating){
        const success=await f.uploadFiles([this.file],this.bookmark,true,{label:this.alt.value.trim()||name,retry:false,selection:this.selection});
        if(this.active(cycle)){
          if(success)this.dialog.close();else this.failure(new Error(f.status.textContent||t('markdown.upload_failed')));
        }
        return;
      }
      if(!editor&&this.info.description&&!this.fileInfo.description){
        const bytes=withDrawioDescription(this.bytes,this.info.description);
        if(!bytes)throw new Error(t('markdown.asset_too_large'));
        this.bytes=bytes;
        this.file=new File([bytes],this.file.name,{type:'image/png'});
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

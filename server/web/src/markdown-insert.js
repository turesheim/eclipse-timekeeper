import {drawioPngInfo} from './drawio-png.js';
import {icon,iconButton} from './icons.js';
import {t} from './i18n.js';
import {wikiReference} from './wiki-links.js';

const node=(tag,className,text)=>{const value=document.createElement(tag);if(className)value.className=className;if(text)value.textContent=text;return value;};
const raster=/\.(png|jpe?g|gif|webp)$/i;

export class MarkdownInsert {
  constructor(field){
    this.field=field;
    const trigger=this.trigger=iconButton(node('button','markdown-insert'),'image',t('markdown.insert_options'));
    trigger.type='button';
    trigger.append(icon('down'));
    field.toolbar.append(trigger);
    const menu=this.menu=node('div','dropdown-menu markdown-actions-menu shadow');
    menu.id='markdown-insert-'+crypto.randomUUID();menu.popover='auto';
    menu.setAttribute('role','menu');menu.setAttribute('aria-label',t('markdown.insert_options'));
    trigger.setAttribute('aria-haspopup','menu');trigger.setAttribute('aria-controls',menu.id);trigger.setAttribute('aria-expanded','false');
    field.root.append(menu);

    const picker=(accept)=>{
      const input=node('input');input.type='file';input.hidden=true;if(accept)input.accept=accept;
      field.root.append(input);return input;
    };
    this.imagePicker=picker('image/png,image/jpeg,image/gif,image/webp');
    this.diagramPicker=picker('.drawio.png');
    this.attachmentPicker=picker('');
    for(const [input,asImage,diagram] of [[this.imagePicker,true,false],[this.diagramPicker,true,true],[this.attachmentPicker,false,false]]){
      input.addEventListener('change',()=>void this.upload(input,asImage,diagram));
      input.addEventListener('cancel',()=>this.releaseFile());
    }
    const choose=(input,target)=>{this.releaseFile();this.fileTarget=target;input.click();};
    this.entries=[
      ['markdown.insert_image','image',()=>!!field.onUpload,target=>choose(this.imagePicker,target)],
      ['drawio.insert','diagram-2',()=>!!field.onUpload&&field.drawio?.canEdit(),target=>void field.drawio.open('new',null,target),()=>!!field.drawio?.phone.matches],
      ['markdown.insert_asset','file-earmark-arrow-up',()=>!!field.resources?.complete,target=>this.openAssets(target)],
      ['drawio.import','diagram-3-fill',()=>!!field.onUpload&&!!field.drawio,target=>choose(this.diagramPicker,target)],
      ['markdown.attach_file','paperclip',()=>!!field.onUpload,target=>choose(this.attachmentPicker,target)]
    ].map(([label,glyph,enabled,run,hidden])=>{
      const button=node('button','dropdown-item');button.type='button';button.tabIndex=-1;
      button.setAttribute('role','menuitem');button.append(icon(glyph),node('span',null,t(label)));menu.append(button);
      button.addEventListener('click',()=>{if(button.disabled)return;const target=this.take();this.close();run(target);});
      return {button,enabled,hidden};
    });
    const options=()=>this.entries.map(entry=>entry.button).filter(button=>!button.disabled);
    const place=()=>{
      if(!menu.matches(':popover-open'))return;
      const viewport=window.visualViewport,left=viewport?.offsetLeft??0,top=viewport?.offsetTop??0;
      const right=left+(viewport?.width??innerWidth),bottom=top+(viewport?.height??innerHeight);
      const rect=trigger.getBoundingClientRect();
      menu.style.left=Math.max(left+8,Math.min(rect.left,right-menu.offsetWidth-8))+'px';
      menu.style.top=Math.max(top+8,Math.min(rect.bottom+4+menu.offsetHeight<=bottom-8?rect.bottom+4:rect.top-menu.offsetHeight-4,bottom-menu.offsetHeight-8))+'px';
    };
    const open=(last=false)=>{
      if(trigger.disabled)return;
      this.target??=field.captureInsertion();
      field.closeActions();field.closeBlockMenu();field.closeListMenu();field.closeCalloutMenu();
      if(!menu.matches(':popover-open'))menu.showPopover();
      place();const items=options();items[last?items.length-1:0]?.focus();
    };
    trigger.addEventListener('pointerdown',()=>{if(!menu.matches(':popover-open')){
      field.releaseInsertion(this.target);this.target=field.captureInsertion();
    }});
    trigger.addEventListener('pointercancel',()=>{field.releaseInsertion(this.target);this.target=null;});
    trigger.addEventListener('mousedown',event=>event.preventDefault());
    trigger.addEventListener('click',()=>menu.matches(':popover-open')?this.close():open());
    trigger.addEventListener('keydown',event=>{
      if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();open(event.key==='ArrowUp');}
    });
    menu.addEventListener('beforetoggle',event=>{
      const opened=event.newState==='open',method=opened?'addEventListener':'removeEventListener';
      trigger.setAttribute('aria-expanded',String(opened));
      window[method]('resize',place);document[method]('scroll',place,true);
      if(!opened){field.releaseInsertion(this.target);this.target=null;}
    });
    menu.addEventListener('keydown',event=>{
      const items=options(),index=items.indexOf(document.activeElement);
      if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){
        event.preventDefault();items[event.key==='Home'?0:event.key==='End'?items.length-1:(index+(event.key==='ArrowDown'?1:-1)+items.length)%items.length]?.focus();
      }else if(event.key==='Escape'||event.key==='Tab'){
        if(event.key==='Escape'){event.preventDefault();event.stopPropagation();}
        this.close();trigger.focus();
      }
    });
    this.buildAssets();
  }

  sync(){
    const f=this.field;
    const blocked=f.readOnly||f.loading||f.bannerBusy||!!document.activeElement?.closest('.markdown-source-fragment');
    this.trigger.hidden=!f.onUpload&&!f.resources?.complete||f.readOnly;
    for(const {button,enabled,hidden} of this.entries){
      button.hidden=!!hidden?.();
      button.disabled=blocked||button.hidden||!enabled();
    }
    this.trigger.disabled=this.entries.every(({button})=>button.disabled);
    if(this.trigger.disabled)this.close();
  }

  take(){const target=this.target??this.field.captureInsertion();this.target=null;return target;}
  close(){if(this.menu.matches(':popover-open'))this.menu.hidePopover();}
  releaseFile(){this.field.releaseInsertion(this.fileTarget);this.fileTarget=null;}
  reset(){this.close();this.field.releaseInsertion(this.target);this.target=null;this.releaseFile();this.assetDialog.close();}

  async upload(input,image,diagram){
    const f=this.field,file=input.files?.[0],target=this.fileTarget??f.captureInsertion();
    this.fileTarget=null;input.value='';
    let retrying=false;
    try{
      if(!file||!f.canInsertAt(target))return;
      let label;
      if(diagram){
        if(file.size>5_000_000)throw new Error(t('markdown.asset_too_large'));
        const info=drawioPngInfo(new Uint8Array(await file.arrayBuffer()));
        if(!info?.editable||!/\.drawio\.png$/i.test(file.name))throw new Error(t('drawio.not_editable'));
        label=info.description||file.name;
      }
      if(f.canInsertAt(target)){
        const saved=await f.uploadFiles([file],target.bookmark,image,{label,selection:target.selection});
        retrying=saved===false&&!!f.retryUpload;
      }
    }catch(error){if(f.canInsertAt(target))f.status.textContent=error.message;}
    finally{if(!retrying)f.releaseInsertion(target);}
  }

  buildAssets(){
    const title=t('markdown.insert_asset');
    const dialog=this.assetDialog=node('dialog','app-dialog markdown-asset-dialog');
    dialog.setAttribute('aria-label',title);
    this.search=node('input','form-control');this.search.type='search';this.search.setAttribute('aria-label',t('notes.search'));
    this.list=node('div','markdown-asset-list');this.status=node('p','hint');this.status.setAttribute('role','status');
    const cancel=node('button','btn btn-outline-secondary',t('ui.close'));cancel.type='button';cancel.addEventListener('click',()=>dialog.close());
    dialog.append(node('h2',null,title),this.search,this.list,this.status,cancel);this.field.root.append(dialog);
    this.search.addEventListener('input',()=>void this.listAssets());
    dialog.addEventListener('close',()=>{
      if(dialog.open)return;
      this.assetAbort?.abort();this.field.releaseInsertion(this.assetTarget);this.assetTarget=null;this.list.replaceChildren();
    });
  }

  openAssets(target){
    this.assetTarget=target;this.assetAbort=new AbortController();this.search.value='';
    this.assetDialog.showModal();this.search.focus();
    const resources=this.field.resources;
    this.diagrams=resources.listDiagrams?.({signal:this.assetAbort.signal}).catch(()=>[])??Promise.resolve([]);
    void this.listAssets();
  }

  async listAssets(){
    const version=this.assetRequest=(this.assetRequest??0)+1,target=this.assetTarget,query=this.search.value;
    const valid=()=>this.assetDialog.open&&this.assetRequest===version&&this.field.canInsertAt(target);
    this.status.textContent=t('markdown.loading');this.list.replaceChildren();
    try{
      const entries=await this.field.resources.complete(query,{assetsOnly:true});
      if(!valid())return;
      const diagrams=await this.diagrams;
      if(!valid())return;
      const folded=query.trim().normalize('NFC').toLocaleLowerCase();
      const choices=new Map(entries.map(entry=>[entry.id,{entry}]));
      for(const {entry,description} of diagrams){
        if(choices.has(entry.id)||[entry.path,description].some(value=>value.normalize('NFC').toLocaleLowerCase().includes(folded)))choices.set(entry.id,{entry,description});
      }
      for(const {entry,description} of choices.values()){
        const label=description||entry.path;
        const pick=node('button','btn btn-outline-secondary text-start',label);pick.type='button';
        if(description)pick.append(node('small','d-block text-body-secondary',entry.path));
        pick.addEventListener('click',()=>void this.insertAsset(entry,description,target));
        this.list.append(pick);
      }
      this.status.textContent=choices.size?'':t('notes.empty');
    }catch(error){if(valid())this.status.textContent=error.message;}
  }

  async insertAsset(entry,description,target){
    const f=this.field;
    if(this.inserting||!f.canInsertAt(target))return;
    this.inserting=true;
    try{
      const image=raster.test(entry.path);
      const wiki=!description&&!entry.path.toLowerCase().endsWith('.drawio.png')&&wikiReference(entry.path);
      const path=wiki?entry.path:await f.resources.assetReference(entry);
      if(!this.assetDialog.open||target!==this.assetTarget||!f.canInsertAt(target))return;
      const label=(description||entry.path.split('/').at(-1)).replace(/[\[\]\\\r\n]/g,' ').trim();
      if(target.editor){
        const command=wiki?(image?'wiki_embed':'wiki_link'):(image?'image':'asset_link');
        if(!target.editor.insertAt(target.bookmark,command,wiki?{value:path}:{path,label}))throw new Error(t('markdown.upload_failed'));
      }else{
        const {start,end}=target.selection;
        f.insertSource(wiki?`${image?'!':''}[[${path}]]`:`${image?'!':''}[${label}](<${path}>)`,start,end);
      }
      this.assetDialog.close();f.focus();
    }catch(error){if(this.assetDialog.open&&target===this.assetTarget)this.status.textContent=error.message;}
    finally{this.inserting=false;}
  }
}

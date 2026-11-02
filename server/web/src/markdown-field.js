import {buildExperience} from './markdown-experience.js';
import {t} from './i18n.js';
import {splitMarkdown,needsSource} from './markdown-document.js';
import {icon,iconButton} from './icons.js';

const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!==undefined)value.textContent=text;if(className)value.className=className;return value;};
const button=text=>{const value=node('button',text,'btn btn-outline-secondary');value.type='button';return value;};
let engine,attempt=0,stylePromise;
function loadStyle(){
  if(!stylePromise)stylePromise=new Promise((resolve,reject)=>{
    const link=document.createElement('link');link.rel='stylesheet';link.href='./assets/milkdown/milkdown.css';link.onload=resolve;
    link.onerror=()=>{link.remove();stylePromise=null;reject(new Error('Editor style unavailable'));};document.head.append(link);
  });
  return stylePromise;
}
async function loadEngine(){if(!engine)engine=await import('./assets/milkdown/milkdown.js'+(attempt++?'?attempt='+attempt:''));return engine;}

/** One in-memory Markdown field. Neither drafts nor bodies enter browser storage. */
export class MarkdownField {
  constructor(root,onChange,onState,prefix='journal',onUpload,resources){
    Object.assign(this,{root,onChange,onState,onUpload,resources,generation:0,readOnly:true});root.classList.add('markdown-editor');
    this.modes=node('div',undefined,'btn-group markdown-modes');this.modes.setAttribute('role','group');this.modes.setAttribute('aria-label',t('markdown.mode'));
    this.formatted=iconButton(button(),'file-richtext',t('markdown.formatted'));this.source=iconButton(button(),'markdown',t('markdown.source'));this.modes.append(this.formatted,this.source);
    this.status=node('p','','hint');this.status.setAttribute('role','status');
    this.normalization=node('p',t('journal.normalization'),'hint');this.normalization.id=prefix+'-markdown-normalization';
    this.metadata=node('details');this.metadata.hidden=true;this.frontmatterView=node('pre');this.metadata.append(node('summary',t('markdown.metadata')),this.frontmatterView);
    this.tools=node('div',undefined,'btn-group markdown-tools');this.tools.setAttribute('role','group');this.tools.setAttribute('aria-label',t('markdown.formatting'));
    this.blockTrigger=iconButton(button(),'paragraph',t('markdown.block_style'));this.blockTrigger.classList.add('markdown-block-trigger');
    this.blockTrigger.setAttribute('aria-haspopup','menu');this.blockTrigger.setAttribute('aria-expanded','false');this.blockTrigger.setAttribute('aria-controls',prefix+'-block-menu');
    this.blockMenu=node('div',undefined,'dropdown-menu markdown-block-menu shadow');this.blockMenu.id=prefix+'-block-menu';this.blockMenu.popover='auto';this.blockMenu.setAttribute('role','menu');this.blockMenu.setAttribute('aria-label',t('markdown.block_style'));
    for(let level=0;level<=6;level++){
      const choice=button();choice.className='dropdown-item';choice.dataset.level=String(level);choice.tabIndex=-1;choice.setAttribute('role','menuitemradio');choice.setAttribute('aria-checked','false');
      choice.append(icon(level?'type-h'+level:'paragraph'),node('span',level?t('markdown.heading_level',{level}):t('markdown.plain_text')));
      choice.addEventListener('mousedown',event=>event.preventDefault());choice.addEventListener('click',()=>{
        if(this.readOnly||this.loading||!this.editor)return;
        this.closeBlockMenu();this.editor.command(level?'heading':'paragraph',level||undefined);
      });this.blockMenu.append(choice);
    }
    this.blockTrigger.addEventListener('mousedown',event=>event.preventDefault());this.blockTrigger.addEventListener('click',()=>{
      if(this.blockMenu.matches(':popover-open'))this.closeBlockMenu();else this.showBlockMenu();
    });
    this.blockTrigger.addEventListener('keydown',event=>{if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();this.showBlockMenu(event.key==='ArrowUp');}});
    this.blockMenu.addEventListener('beforetoggle',event=>this.blockTrigger.setAttribute('aria-expanded',String(event.newState==='open')));
    this.blockMenu.addEventListener('keydown',event=>{
      const choices=[...this.blockMenu.children],current=choices.indexOf(document.activeElement);
      if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){
        event.preventDefault();choices[event.key==='Home'?0:event.key==='End'?choices.length-1:(current+(event.key==='ArrowDown'?1:-1)+choices.length)%choices.length].focus();
      }else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();this.closeBlockMenu();this.blockTrigger.focus();}
      else if(event.key==='Tab'){this.closeBlockMenu();this.blockTrigger.focus();}
    });
    document.addEventListener('scroll',()=>this.positionBlockMenu(),true);window.addEventListener('resize',()=>this.positionBlockMenu());
    this.tools.append(this.blockTrigger);
    for(const [name,glyph] of [
      ['bold','type-bold'],['italic','type-italic'],['strike','type-strikethrough'],
      ['inline_code','code'],['code_block','code-square'],['list','list-ul'],['ordered','list-ol'],['tasks','list-check'],
      ['link','link-45deg'],['quote','quote'],['table','table'],['hr','hr'],['undo','arrow-counterclockwise'],['redo','arrow-clockwise']
    ]){
      const action=iconButton(button(),glyph,t('markdown.'+name));action.dataset.command=name;const shortcuts={bold:'Mod+B',italic:'Mod+I',undo:'Mod+Z',redo:'Mod+Shift+Z'};if(shortcuts[name])action.title+=' ('+shortcuts[name]+')';
      action.addEventListener('mousedown',event=>event.preventDefault());action.addEventListener('click',()=>{
        if(this.readOnly||this.loading||!this.editor)return;
        if(name==='link'){
          const value=prompt(t('markdown.link_url'),this.editor.linkHref()||'https://');if(value===null)return;
          if(!this.editor.command(name,value.trim()))this.status.textContent=t('markdown.invalid_link');else this.status.textContent='';
        }else this.editor.command(name);
      });this.tools.append(action);
    }
    this.image=iconButton(button(),'image',t('markdown.insert_image'));
    this.attachment=iconButton(button(),'paperclip',t('markdown.attach_file'));
    this.imagePicker=node('input');this.imagePicker.type='file';this.imagePicker.accept='image/png,image/jpeg,image/gif,image/webp';this.imagePicker.hidden=true;
    this.attachmentPicker=node('input');this.attachmentPicker.type='file';this.attachmentPicker.hidden=true;
    this.image.addEventListener('click',()=>this.imagePicker.click());
    this.attachment.addEventListener('click',()=>this.attachmentPicker.click());
    this.imagePicker.addEventListener('change',()=>void this.upload(this.imagePicker,true));
    this.attachmentPicker.addEventListener('change',()=>void this.upload(this.attachmentPicker,false));
    this.tools.insertBefore(this.image,this.tools.children[9]);this.tools.insertBefore(this.attachment,this.tools.children[10]);
    this.rich=node('div',undefined,'journal-formatted');this.rich.id=prefix+'-formatted';
    this.plain=node('div',undefined,'journal-source');const label=node('label',t('markdown.body'),'form-label');label.htmlFor=prefix+'-body';
    this.text=node('textarea',undefined,'form-control');this.text.id=prefix+'-body';this.text.rows=14;this.text.spellcheck=false;this.text.setAttribute('aria-description',t('markdown.tab_help'));this.plain.append(label,this.text);
    this.formatted.setAttribute('aria-controls',this.rich.id);this.source.setAttribute('aria-controls',this.text.id);
    this.toolbar=node('div',undefined,'btn-toolbar flex-nowrap align-items-center gap-2 markdown-toolbar');this.toolbar.setAttribute('role','toolbar');this.toolbar.setAttribute('aria-label',t('markdown.formatting'));this.separator=node('span',undefined,'markdown-toolbar-separator');this.separator.setAttribute('role','separator');this.separator.setAttribute('aria-orientation','vertical');this.toolbar.append(this.modes,this.separator,this.tools);
    root.append(this.metadata,this.toolbar,this.normalization,this.status,this.rich,this.plain,this.imagePicker,this.attachmentPicker,this.blockMenu);
    this.rich.addEventListener('timekeeper:markdown-link-error',event=>this.status.textContent=event.detail);
    this.rich.addEventListener('focusin',()=>this.controls());this.rich.addEventListener('focusout',()=>queueMicrotask(()=>this.controls()));
    this.source.addEventListener('click',()=>void this.showSource());this.formatted.addEventListener('click',()=>void this.showFormatted());
    this.text.addEventListener('keydown',event=>{if(event.key==='Escape'){this.escapeTab=true;return;}if(event.key==='Tab'&&this.escapeTab){this.escapeTab=false;return;}this.escapeTab=false;if(event.key==='Tab'&&!this.readOnly){event.preventDefault();this.text.setRangeText('  ',this.text.selectionStart,this.text.selectionEnd,'end');this.text.dispatchEvent(new Event('input'));}});
    for(const name of ['paste','drop'])this.text.addEventListener(name,event=>{const files=[...(event.clipboardData?.files||event.dataTransfer?.files||[])];if(files.length&&!this.readOnly&&this.onUpload){event.preventDefault();void this.uploadFiles(files);}});
    buildExperience(this,loadEngine,()=>import('./assets/milkdown/source-editor.js'));
    this.text.addEventListener('input',()=>{this.unterminated=false;this.status.textContent='';this.onChange();this.refreshPreview();});this.clear();
  }
  insertSource(text,from=this.text.selectionStart,to=this.text.selectionEnd){
    if(this.readOnly)return null;if(this.codeEditor)return this.codeEditor.insert(text,from,to);
    const follow=this.text.selectionStart===from&&this.text.selectionEnd===to;this.text.setRangeText(text,from,to,'preserve');const end=from+text.length;if(follow)this.text.setSelectionRange(end,end);this.onChange();this.refreshPreview();return end;
  }
  rawBody(){return this.text.value===this.displayedBody?this.raw:this.text.value;}
  async upload(picker,image){
    const file=picker.files?.[0];picker.value='';if(file)await this.uploadFiles([file],this.editor?.bookmark(),image);
  }
  async uploadFiles(files,target,forceImage){
    if(!this.onUpload||this.readOnly||this.loading)return;
    if(this.codeEditor){const position=this.codeEditor.position();this.text.setSelectionRange(position.anchor,position.head);}
    const remaining=[...files];
    const generation=this.generation,editor=this.editor;let start=this.text.selectionStart,end=this.text.selectionEnd;
    this.loading=true;this.controls();this.retryButton.hidden=true;
    try{
      for(let index=0;index<files.length;index++){
        const file=files[index],image=forceImage??['image/png','image/jpeg','image/gif','image/webp'].includes(file.type);
        if(file.size>5_000_000)throw new Error(t('markdown.asset_too_large'));
        this.status.textContent=t('markdown.upload_progress',{name:file.name,current:index+1,total:files.length});this.status.setAttribute('aria-busy','true');
        const path=await this.onUpload(file,image);this.resources?.invalidate?.();if(generation!==this.generation)return;if(this.readOnly)throw new Error(t('markdown.upload_failed'));
        const label=file.name.replace(/[\[\]()\\\r\n]/g,' ').trim()||t('markdown.attachment');
        if(editor){if(!editor.insertAt(target,image?'image':'asset_link',{path,label}))throw new Error(t('markdown.upload_failed'));}
        else{const inserted=this.insertSource(image?`![${label}](${path})`:`[${label}](${path})`,start,end);start=end=inserted;}
        remaining.shift();
      }
      editor?.release(target);this.retryUpload=null;this.status.textContent='';
    }catch(error){if(generation===this.generation){this.status.textContent=error.message||t('markdown.upload_failed');this.retryUpload=()=>this.uploadFiles(remaining,target,forceImage);this.retryButton.hidden=false;}}
    finally{if(generation===this.generation){this.status.removeAttribute('aria-busy');this.loading=false;this.controls();}}
  }

  setRaw(value){this.raw=value;this.text.value=value;this.displayedBody=this.text.value;}
  value(){return (this.frontmatter||'')+(this.editor?this.editor.markdown():this.rawBody());}
  matches(value){
    if(typeof value!=='string')return false;
    if(!this.editor)return this.value()===value;
    const prefix=this.frontmatter||'';
    return value.startsWith(prefix)&&this.editor.matchesSource(value.slice(prefix.length));
  }
  setReadOnly(value){this.readOnly=value;this.editor?.setReadOnly(value);this.suspended?.editor.setReadOnly(value);this.codeEditor?.readOnly(value);this.controls();}
  paintBlockStyle(){
    const level=this.editor?.headingLevel()||0;
    if(level!==this.blockLevel){
      this.blockLevel=level;iconButton(this.blockTrigger,level?'type-h'+level:'paragraph',t('markdown.block_style'));this.blockTrigger.append(icon('down'));
      this.blockTrigger.title=t('markdown.block_style')+': '+(level?t('markdown.heading_level',{level}):t('markdown.plain_text'));
    }
    for(const choice of this.blockMenu.children)choice.setAttribute('aria-checked',String(Number(choice.dataset.level)===level));
  }
  showBlockMenu(last=false){
    if(this.readOnly||this.loading||!this.editor)return;
    this.paintBlockStyle();if(!this.blockMenu.matches(':popover-open'))this.blockMenu.showPopover();this.positionBlockMenu();
    (last?this.blockMenu.lastElementChild:this.blockMenu.querySelector('[aria-checked="true"]')||this.blockMenu.firstElementChild).focus();
  }
  positionBlockMenu(){
    if(!this.blockMenu.matches(':popover-open'))return;
    const rect=this.blockTrigger.getBoundingClientRect(),height=this.blockMenu.offsetHeight;
    this.blockMenu.style.left=Math.max(8,Math.min(rect.left,innerWidth-this.blockMenu.offsetWidth-8))+'px';
    this.blockMenu.style.top=Math.max(8,Math.min(rect.bottom+4+height<=innerHeight-8?rect.bottom+4:rect.top-height-4,innerHeight-height-8))+'px';
  }
  closeBlockMenu(){if(this.blockMenu.matches(':popover-open'))this.blockMenu.hidePopover();}
  controls(){
    if(this.retryButton)this.retryButton.disabled=this.readOnly||this.loading;this.updateExperience?.();this.text.readOnly=this.readOnly||this.loading;this.codeEditor?.readOnly(this.readOnly||this.loading);if(this.sourceExtras)this.sourceExtras.hidden=!this.rich.hidden;
    const fragmentFocus=this.rich.contains(document.activeElement)&&!!document.activeElement?.closest?.('.markdown-source-fragment');
    for(const action of this.tools.children)action.disabled=this.readOnly||this.loading||fragmentFocus||(!this.onUpload&&(action===this.image||action===this.attachment));
    for(const choice of this.blockMenu.children)choice.disabled=this.readOnly||this.loading||fragmentFocus;
    if(this.readOnly||this.loading||this.rich.hidden)this.closeBlockMenu();
    this.tools.hidden=this.rich.hidden||this.readOnly;this.separator.hidden=this.tools.hidden;
    this.normalization.hidden=this.rich.hidden||this.readOnly;
    this.formatted.disabled=this.loading;this.source.disabled=this.loading;this.onState?.();
  }
  mode(formatted){
    if(!formatted)this.closeBlockMenu();
    if(this.sourceExtras)this.sourceExtras.hidden=formatted;this.rich.hidden=!formatted;this.tools.hidden=!formatted;this.separator.hidden=!formatted;this.plain.hidden=formatted;
    this.normalization.hidden=!formatted||this.readOnly;
    this.formatted.classList.toggle('active',formatted);this.source.classList.toggle('active',!formatted);
    this.formatted.setAttribute('aria-pressed',String(formatted));this.source.setAttribute('aria-pressed',String(!formatted));
  }
  clear(){
    this.sourcePosition=null;this.enhance?.setAttribute('aria-pressed','false');this.split?.setAttribute('aria-pressed','false');this.plain.classList.remove('markdown-split');
    this.restoreGeneration=(this.restoreGeneration||0)+1;this.historyDialog?.close();this.assetDialog?.close();this.imageDialog?.close();
    this.suspended?.editor.destroy().catch(()=>{});this.suspended=null;this.codeEditor?.destroy();this.codeEditor=null;this.codeHost?.replaceChildren();if(this.codeHost)this.codeHost.hidden=true;this.text.hidden=false;this.previewEditor?.destroy();this.previewEditor=null;this.splitHost?.replaceChildren();if(this.splitHost)this.splitHost.hidden=true;this.retryUpload=null;if(this.retryButton)this.retryButton.hidden=true;
    ++this.generation;const previous=this.editor;this.editor=null;void previous?.destroy().catch(()=>{});
    this.rich.replaceChildren();this.toolbar.scrollLeft=0;this.frontmatter='';this.frontmatterView.textContent='';this.metadata.hidden=true;this.unterminated=false;this.setRaw('');this.loading=false;this.status.textContent='';this.paintBlockStyle();this.mode(false);this.controls();
  }
  setValue(value){
    this.resources?.invalidate?.();this.clear();const parts=splitMarkdown(value);this.frontmatter=parts.frontmatter;this.unterminated=parts.unterminated;this.setRaw(parts.body);this.text.setSelectionRange(0,0);this.text.scrollTop=0;
    this.frontmatterView.textContent=this.frontmatter;this.metadata.hidden=!this.frontmatter;
  }
  position(){return {formatted:!!this.editor,...(this.editor?this.editor.position():this.codeEditor?this.codeEditor.position():{anchor:this.text.selectionStart,head:this.text.selectionEnd,scrollTop:this.text.scrollTop})};}
  async restorePosition(position){if(!position)return;const generation=this.restoreGeneration;if(position.formatted)await this.showFormatted(false);if(generation!==this.restoreGeneration)return;if(this.editor)this.editor.restorePosition(position);else{this.text.setSelectionRange(Math.min(position.anchor,this.text.value.length),Math.min(position.head??position.anchor,this.text.value.length));this.text.scrollTop=position.scrollTop||0;}}
  async showSource(){
    if(this.loading||!this.editor)return;
    const previous=this.editor,position=previous.position(),body=previous.markdown();this.setRaw(body);this.suspended={editor:previous,body};this.editor=null;
    this.mode(false);this.controls();const normalized=position.anchor,head=position.head??normalized;this.text.setSelectionRange(Math.min(normalized,head),Math.max(normalized,head),head<normalized?'backward':'forward');this.text.scrollTop=position.scrollTop;if(this.codeEditor){this.codeEditor.replaceValue(body);this.codeHost.hidden=false;this.text.hidden=true;this.codeEditor.select(normalized,head);}else this.text.focus();
  }
  async showFormatted(focus=true){
    if(this.loading)return;if(this.editor){this.editor.focus();return;}
    this.sourcePosition=this.codeEditor?.position()||{anchor:this.text.selectionStart,head:this.text.selectionEnd,scrollTop:this.text.scrollTop};if(this.codeEditor)this.setRaw(this.codeEditor.value());
    if(!this.frontmatter){const parts=splitMarkdown(this.rawBody());this.frontmatter=parts.frontmatter;this.unterminated=parts.unterminated;this.setRaw(parts.body);this.frontmatterView.textContent=this.frontmatter;this.metadata.hidden=!this.frontmatter;
      // Frontmatter is deliberately opaque and outside both editor schemas.
      // A newly authored header changes that boundary; do not retain a body-only
      // history that could subsequently duplicate it into the Markdown body.
      if(parts.frontmatter&&this.codeEditor){this.codeEditor.destroy();this.codeEditor=null;this.codeHost.replaceChildren();this.codeHost.hidden=true;this.text.hidden=false;this.enhance.setAttribute('aria-pressed','false');}
    }
    if(new TextEncoder().encode(this.value()).length>200000){this.status.textContent=t('journal.too_large');return;}
    const richBody=this.rawBody();
    if(this.suspended){const suspended=this.suspended;this.suspended=null;if(!this.unterminated&&!splitMarkdown(richBody).frontmatter&&(suspended.body===richBody||suspended.editor.replaceMarkdown(richBody))){this.editor=suspended.editor;this.editor.setReadOnly(this.readOnly);this.mode(true);if(this.sourcePosition)this.editor.restorePosition(this.sourcePosition);this.controls();if(focus)this.editor.focus();return;}await suspended.editor.destroy();this.rich.replaceChildren();}
    if(this.unterminated||splitMarkdown(richBody).frontmatter){this.status.textContent=t('markdown.source_only');return;}
    const current=++this.generation;this.loading=true;this.status.textContent=t('markdown.loading');this.controls();
    const host=node('div');this.rich.replaceChildren(host);
    try{
      const [module]=await Promise.all([loadEngine(),loadStyle()]);if(current!==this.generation)return;
      const created=await module.createMarkdownEditor({root:host,value:richBody,label:t('markdown.body'),description:this.normalization.id,taskLabel:text=>t('markdown.task',{text}),resources:this.resources,uploadFiles:(files,target)=>void this.uploadFiles(files,target),readOnly:this.readOnly,changed:()=>{if(current===this.generation)this.onChange();},stateChanged:()=>{if(current===this.generation)this.paintBlockStyle();this.updateExperience();},escape:()=>this.source.focus()});
      if(current!==this.generation){await created?.destroy();return;}
      this.editor=created;
      if(!created){this.rich.replaceChildren();this.status.textContent=t('markdown.source_only');return;}
      this.paintBlockStyle();this.mode(true);if(this.sourcePosition)created.restorePosition(this.sourcePosition);this.updateExperience();this.status.textContent='';if(focus)created.focus();
    }catch{if(current===this.generation){this.rich.replaceChildren();this.mode(false);this.status.textContent=t('markdown.failed');}}
    finally{if(current===this.generation){this.loading=false;this.controls();}}
  }
  focus(){if(this.editor)this.editor.focus();else if(this.codeEditor)this.codeEditor.focus();else this.text.focus();}
}

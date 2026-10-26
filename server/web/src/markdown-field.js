import {t} from './i18n.js';
import {splitMarkdown,needsSource,normalizeEditorMarkdown} from './markdown-document.js';
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
  constructor(root,onChange,onState,prefix='journal'){
    Object.assign(this,{root,onChange,onState,generation:0,readOnly:true});root.classList.add('markdown-editor');
    this.modes=node('div',undefined,'markdown-modes');this.modes.setAttribute('role','group');this.modes.setAttribute('aria-label',t('markdown.mode'));
    this.formatted=iconButton(button(),'file-richtext',t('markdown.formatted'));this.source=iconButton(button(),'markdown',t('markdown.source'));this.modes.append(this.formatted,this.source);
    this.status=node('p','','hint');this.status.setAttribute('role','status');
    this.normalization=node('p',t('journal.normalization'),'hint');this.normalization.id=prefix+'-markdown-normalization';
    this.metadata=node('details');this.metadata.hidden=true;this.frontmatterView=node('pre');this.metadata.append(node('summary',t('markdown.metadata')),this.frontmatterView);
    this.tools=node('div',undefined,'markdown-tools');this.tools.setAttribute('role','group');this.tools.setAttribute('aria-label',t('markdown.formatting'));
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
      const action=iconButton(button(),glyph,t('markdown.'+name));
      action.addEventListener('mousedown',event=>event.preventDefault());action.addEventListener('click',()=>{
        if(this.readOnly||this.loading||!this.editor)return;
        if(name==='link'){
          const value=prompt(t('markdown.link_url'),this.editor.linkHref()||'https://');if(value===null)return;
          if(!this.editor.command(name,value.trim()))this.status.textContent=t('markdown.invalid_link');else this.status.textContent='';
        }else this.editor.command(name);
      });this.tools.append(action);
    }
    this.rich=node('div',undefined,'journal-formatted');this.rich.id=prefix+'-formatted';
    this.plain=node('div',undefined,'journal-source');const label=node('label',t('markdown.body'),'form-label');label.htmlFor=prefix+'-body';
    this.text=node('textarea',undefined,'form-control');this.text.id=prefix+'-body';this.text.rows=14;this.text.spellcheck=false;this.plain.append(label,this.text);
    this.formatted.setAttribute('aria-controls',this.rich.id);this.source.setAttribute('aria-controls',this.text.id);
    this.toolbar=node('div',undefined,'markdown-toolbar');this.separator=node('span',undefined,'markdown-toolbar-separator');this.separator.setAttribute('role','separator');this.separator.setAttribute('aria-orientation','vertical');this.toolbar.append(this.modes,this.separator,this.tools);
    root.append(this.metadata,this.toolbar,this.normalization,this.status,this.rich,this.plain,this.blockMenu);
    this.source.addEventListener('click',()=>void this.showSource());this.formatted.addEventListener('click',()=>void this.showFormatted());
    this.text.addEventListener('input',()=>{this.unterminated=false;this.status.textContent='';this.onChange();});this.clear();
  }
  rawBody(){return this.text.value===this.displayedBody?this.raw:this.text.value;}
  setRaw(value){this.raw=value;this.text.value=value;this.displayedBody=this.text.value;}
  value(){return (this.frontmatter||'')+(this.editor?this.editor.markdown():this.rawBody());}
  setReadOnly(value){this.readOnly=value;this.editor?.setReadOnly(value);this.controls();}
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
    this.text.readOnly=this.readOnly||this.loading;
    for(const action of this.tools.children)action.disabled=this.readOnly||this.loading;
    for(const choice of this.blockMenu.children)choice.disabled=this.readOnly||this.loading;
    if(this.readOnly||this.loading||this.rich.hidden)this.closeBlockMenu();
    this.tools.hidden=this.rich.hidden||this.readOnly;this.separator.hidden=this.tools.hidden;
    this.normalization.hidden=this.rich.hidden||this.readOnly;
    this.formatted.disabled=this.loading;this.source.disabled=this.loading;this.onState?.();
  }
  mode(formatted){
    if(!formatted)this.closeBlockMenu();
    this.rich.hidden=!formatted;this.tools.hidden=!formatted;this.separator.hidden=!formatted;this.plain.hidden=formatted;
    this.normalization.hidden=!formatted||this.readOnly;
    this.formatted.setAttribute('aria-pressed',String(formatted));this.source.setAttribute('aria-pressed',String(!formatted));
  }
  clear(){
    ++this.generation;const previous=this.editor;this.editor=null;void previous?.destroy().catch(()=>{});
    this.rich.replaceChildren();this.toolbar.scrollLeft=0;this.frontmatter='';this.frontmatterView.textContent='';this.metadata.hidden=true;this.unterminated=false;this.setRaw('');this.loading=false;this.status.textContent='';this.paintBlockStyle();this.mode(false);this.controls();
  }
  setValue(value){
    this.clear();const parts=splitMarkdown(value);this.frontmatter=parts.frontmatter;this.unterminated=parts.unterminated;this.setRaw(parts.body);
    this.frontmatterView.textContent=this.frontmatter;this.metadata.hidden=!this.frontmatter;
  }
  async showSource(){
    if(this.loading)return;
    const current=++this.generation,previous=this.editor;
    if(previous)this.setRaw(previous.markdown());this.editor=null;this.loading=true;this.controls();
    try{await previous?.destroy();}finally{
      if(current===this.generation){this.rich.replaceChildren();this.mode(false);this.loading=false;this.controls();this.text.focus();}
    }
  }
  async showFormatted(focus=true){
    if(this.loading)return;if(this.editor){this.editor.focus();return;}
    if(!this.frontmatter){const parts=splitMarkdown(this.rawBody());this.frontmatter=parts.frontmatter;this.unterminated=parts.unterminated;this.setRaw(parts.body);this.frontmatterView.textContent=this.frontmatter;this.metadata.hidden=!this.frontmatter;}
    if(new TextEncoder().encode(this.value()).length>200000){this.status.textContent=t('journal.too_large');return;}
    const richBody=normalizeEditorMarkdown(this.rawBody());
    if(this.unterminated||needsSource(richBody)||splitMarkdown(richBody).frontmatter){this.status.textContent=t('markdown.source_only');return;}
    const current=++this.generation;this.loading=true;this.status.textContent=t('markdown.loading');this.controls();
    const host=node('div');this.rich.replaceChildren(host);
    try{
      const [module]=await Promise.all([loadEngine(),loadStyle()]);if(current!==this.generation)return;
      const created=await module.createMarkdownEditor({root:host,value:richBody,label:t('markdown.body'),description:this.normalization.id,taskLabel:text=>t('markdown.task',{text}),readOnly:this.readOnly,changed:()=>{if(current===this.generation)this.onChange();},stateChanged:()=>{if(current===this.generation)this.paintBlockStyle();},escape:()=>this.source.focus()});
      if(current!==this.generation){await created?.destroy();return;}
      this.editor=created;
      if(!created){this.rich.replaceChildren();this.status.textContent=t('markdown.source_only');return;}
      this.paintBlockStyle();this.mode(true);this.status.textContent='';if(focus)created.focus();
    }catch{if(current===this.generation){this.rich.replaceChildren();this.mode(false);this.status.textContent=t('markdown.failed');}}
    finally{if(current===this.generation){this.loading=false;this.controls();}}
  }
  focus(){if(this.editor)this.editor.focus();else this.text.focus();}
}

import {t} from './i18n.js';
import {splitMarkdown,needsSource} from './markdown-document.js';

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
  constructor(root,onChange,onState){
    Object.assign(this,{root,onChange,onState,generation:0,readOnly:true});root.classList.add('markdown-editor');
    this.modes=node('div',undefined,'markdown-modes');this.modes.setAttribute('role','group');this.modes.setAttribute('aria-label',t('markdown.mode'));
    this.formatted=button(t('markdown.formatted'));this.source=button(t('markdown.source'));this.modes.append(this.formatted,this.source);
    this.status=node('p','','hint');this.status.setAttribute('role','status');
    this.help=node('p',t('markdown.help'),'hint');this.help.id='journal-markdown-help';
    this.helpDetails=node('details',undefined,'markdown-help');this.helpDetails.append(node('summary',t('journal.help')),node('p',t('markdown.normalization'),'hint'),this.help);
    this.metadata=node('details');this.metadata.hidden=true;this.frontmatterView=node('pre');this.metadata.append(node('summary',t('markdown.metadata')),this.frontmatterView);
    this.tools=node('div',undefined,'markdown-tools');this.tools.setAttribute('role','group');this.tools.setAttribute('aria-label',t('markdown.formatting'));
    for(const name of ['heading','bold','italic','list','quote','table','undo','redo']){
      const action=button(t('markdown.'+(name==='heading'?'heading_button':name)));
      action.addEventListener('mousedown',event=>event.preventDefault());action.addEventListener('click',()=>{if(!this.readOnly&&!this.loading)this.editor?.command(name);});this.tools.append(action);
    }
    this.rich=node('div',undefined,'journal-formatted');this.rich.id='journal-formatted';
    this.plain=node('div',undefined,'journal-source');const label=node('label',t('markdown.body'),'form-label');label.htmlFor='journal-body';
    this.text=node('textarea',undefined,'form-control');this.text.id='journal-body';this.text.rows=14;this.text.spellcheck=false;this.text.setAttribute('aria-describedby',this.help.id);this.plain.append(label,this.text);
    this.formatted.setAttribute('aria-controls',this.rich.id);this.source.setAttribute('aria-controls',this.text.id);
    root.append(this.metadata,this.modes,this.status,this.helpDetails,this.tools,this.rich,this.plain);
    this.source.addEventListener('click',()=>void this.showSource());this.formatted.addEventListener('click',()=>void this.showFormatted());
    this.text.addEventListener('input',()=>{this.unterminated=false;this.status.textContent='';this.onChange();});this.clear();
  }
  rawBody(){return this.text.value===this.displayedBody?this.raw:this.text.value;}
  setRaw(value){this.raw=value;this.text.value=value;this.displayedBody=this.text.value;}
  value(){return (this.frontmatter||'')+(this.editor?this.editor.markdown():this.rawBody());}
  setReadOnly(value){this.readOnly=value;this.editor?.setReadOnly(value);this.controls();}
  controls(){
    this.text.readOnly=this.readOnly||this.loading;
    for(const action of this.tools.children)action.disabled=this.readOnly||this.loading;
    this.tools.hidden=this.rich.hidden||this.readOnly;
    this.formatted.disabled=this.loading;this.source.disabled=this.loading;this.onState?.();
  }
  mode(formatted){
    this.rich.hidden=!formatted;this.tools.hidden=!formatted;this.plain.hidden=formatted;
    this.formatted.setAttribute('aria-pressed',String(formatted));this.source.setAttribute('aria-pressed',String(!formatted));
  }
  clear(){
    ++this.generation;const previous=this.editor;this.editor=null;void previous?.destroy().catch(()=>{});
    this.rich.replaceChildren();this.frontmatter='';this.frontmatterView.textContent='';this.metadata.hidden=true;this.unterminated=false;this.setRaw('');this.loading=false;this.status.textContent='';this.mode(false);this.controls();
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
    if(this.unterminated||needsSource(this.rawBody())||splitMarkdown(this.rawBody()).frontmatter){this.status.textContent=t('markdown.source_only');return;}
    const current=++this.generation;this.loading=true;this.status.textContent=t('markdown.loading');this.controls();
    const host=node('div');this.rich.replaceChildren(host);
    try{
      const [module]=await Promise.all([loadEngine(),loadStyle()]);if(current!==this.generation)return;
      const created=await module.createMarkdownEditor({root:host,value:this.rawBody(),label:t('markdown.body'),taskLabel:text=>t('markdown.task',{text}),readOnly:this.readOnly,changed:()=>{if(current===this.generation)this.onChange();},escape:()=>this.source.focus()});
      if(current!==this.generation){await created?.destroy();return;}
      this.editor=created;
      if(!created){this.rich.replaceChildren();this.status.textContent=t('markdown.source_only');return;}
      this.mode(true);this.status.textContent='';if(focus)created.focus();
    }catch{if(current===this.generation){this.rich.replaceChildren();this.mode(false);this.status.textContent=t('markdown.failed');}}
    finally{if(current===this.generation){this.loading=false;this.controls();}}
  }
  focus(){if(this.editor)this.editor.focus();else this.text.focus();}
}

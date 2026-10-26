import {t} from './i18n.js';
import {splitMarkdown,needsSource,normalizeEditorMarkdown} from './markdown-document.js';
const $=id=>document.getElementById(id),source=$('markdown-body'),rich=$('markdown-formatted'),status=$('markdown-status');
let frontmatter='',unterminated=false,editor=null,loading=false,dirty=false,rawBody='',displayedBody='';
let engine=null,attempt=0,stylePromise=null;
const body=()=>source.value===displayedBody?rawBody:source.value;
function setBody(value){rawBody=value;source.value=value;displayedBody=source.value;}
function loadStyle(){
  if(stylePromise)return stylePromise;
  stylePromise=new Promise((resolve,reject)=>{const css=document.createElement('link');css.rel='stylesheet';css.href='./assets/milkdown/milkdown.css';css.onload=resolve;css.onerror=()=>{css.remove();stylePromise=null;reject(new Error('Editor style unavailable'));};document.head.append(css);});
  return stylePromise;
}
async function loadEngine(){if(engine)return engine;const query=attempt++?'?attempt='+attempt:'';engine=await import('./assets/milkdown/milkdown.js'+query);return engine;}
function busy(value){loading=value;for(const id of ['markdown-rich','markdown-source','markdown-file','markdown-download','markdown-body'])$(id).disabled=value;}
// A synthetic fixture, not a saved day note. Imported notes never enter storage,
// URLs, logs or requests. Only the user-initiated download leaves this editor.
setBody('# Daily notes\n\nA **bold** idea and an *emphasis*.\n\n- First item\n- Second item\n\n1. Plan\n2. Review\n\n- [ ] Follow up\n- [x] Completed\n\n[Timekeeper](https://turesheim.github.io/eclipse-timekeeper/)\n\n> A useful quotation.\n\n```js\nconst day = "Monday";\n```\n\n| Topic | Status |\n| --- | --- |\n| Editor | Evaluating |\n');
function setMode(formatted){
  rich.hidden=!formatted;$('markdown-toolbar').hidden=!formatted;$('markdown-source-panel').hidden=formatted;
  $('markdown-rich').setAttribute('aria-pressed',String(formatted));$('markdown-source').setAttribute('aria-pressed',String(!formatted));
}
function capture(){if(editor){const value=editor.markdown();if(value!==body())dirty=true;setBody(value);}}
async function showSource(focus=true){
  if(loading)return;busy(true);
  try{capture();await editor?.destroy();editor=null;rich.replaceChildren();setMode(false);if(focus)source.focus();}
  finally{busy(false);if(focus)source.focus();}
}
$('markdown-source').addEventListener('click',()=>void showSource());
$('markdown-rich').addEventListener('click',async()=>{
  if(loading)return;if(editor){editor.focus();return;}
  // Frontmatter pasted in source is also separated before rich parsing.
  if(!frontmatter){const parts=splitMarkdown(body());frontmatter=parts.frontmatter;setBody(parts.body);unterminated=parts.unterminated;paintMetadata();}
  if(new TextEncoder().encode(body()).length>200000){status.textContent=t('markdown.too_large');return;}
  const richBody=normalizeEditorMarkdown(body());
  if(unterminated||needsSource(richBody)||splitMarkdown(richBody).frontmatter){status.textContent=t('markdown.source_only');return;}
  busy(true);
  status.textContent=t('markdown.loading');
  try{
    const [module]=await Promise.all([loadEngine(),loadStyle()]);
    editor=await module.createMarkdownEditor({root:rich,value:richBody,label:t('markdown.body'),taskLabel:text=>t('markdown.task',{text}),escape:()=>$('markdown-source').focus()});
    if(!editor){status.textContent=t('markdown.source_only');return;}
    setMode(true);status.textContent=t('markdown.normalization');editor.focus();
  }catch{rich.replaceChildren();status.textContent=t('markdown.failed');setMode(false);}
  finally{busy(false);if(editor)editor.focus();}
});
for(const name of ['heading','bold','italic','list','quote','table','undo','redo']){
  const button=document.createElement('button');button.type='button';button.className='btn btn-outline-secondary';button.textContent=t('markdown.'+(name==='heading'?'heading_button':name));
  button.addEventListener('mousedown',event=>event.preventDefault());button.addEventListener('click',()=>editor?.command(name));$('markdown-toolbar').append(button);
}
function paintMetadata(){$('markdown-metadata').hidden=!frontmatter;$('markdown-frontmatter').textContent=frontmatter;}
source.addEventListener('input',()=>{dirty=true;unterminated=false;status.textContent='';});
$('day-label').addEventListener('input',()=>{dirty=true;});
$('markdown-file').addEventListener('change',async event=>{
  const file=event.target.files[0];if(!file)return;
  if(file.size>200000){status.textContent=t('markdown.too_large');event.target.value='';return;}
  if((dirty||editor&&editor.markdown()!==body())&&!confirm(t('markdown.replace'))){event.target.value='';return;}
  try{const value=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(await file.arrayBuffer());await showSource(false);const parts=splitMarkdown(value);frontmatter=parts.frontmatter;setBody(parts.body);unterminated=parts.unterminated;paintMetadata();dirty=false;status.textContent=t('markdown.imported');source.focus();}
  catch{status.textContent=t('markdown.failed');}
  event.target.value='';
});
$('markdown-download').addEventListener('click',()=>{
  capture();const blob=new Blob([frontmatter+body()],{type:'text/markdown;charset=utf-8'}),url=URL.createObjectURL(blob),link=document.createElement('a');
  link.href=url;link.download='daily-note.md';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);dirty=false;status.textContent=t('markdown.downloaded');
});
window.addEventListener('beforeunload',event=>{if(dirty||editor&&editor.markdown()!==body()){event.preventDefault();event.returnValue='';}});

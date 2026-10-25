import {t} from './i18n.js';
import {splitMarkdown,needsSource} from './markdown-document.js';
const $=id=>document.getElementById(id),source=$('markdown-body'),rich=$('markdown-formatted'),status=$('markdown-status');
let frontmatter='',unterminated=false,editor=null,loading=false,dirty=false,rawBody='',displayedBody='';
const body=()=>source.value===displayedBody?rawBody:source.value;
function setBody(value){rawBody=value;source.value=value;displayedBody=source.value;}
// A synthetic fixture, not a saved day note. Imported notes never enter storage,
// URLs, logs or requests. Only the user-initiated download leaves this editor.
setBody('# Daily notes\n\nA **bold** idea and an *emphasis*.\n\n- First item\n- Second item\n\n1. Plan\n2. Review\n\n- [ ] Follow up\n- [x] Completed\n\n[Timekeeper](https://turesheim.github.io/eclipse-timekeeper/)\n\n> A useful quotation.\n\n```js\nconst day = "Monday";\n```\n\n| Topic | Status |\n| --- | --- |\n| Editor | Evaluating |\n');
function setMode(formatted){
  rich.hidden=!formatted;$('markdown-toolbar').hidden=!formatted;$('markdown-source-panel').hidden=formatted;
  $('markdown-rich').setAttribute('aria-pressed',String(formatted));$('markdown-source').setAttribute('aria-pressed',String(!formatted));
}
function capture(){if(editor){const value=editor.markdown();if(value!==body())dirty=true;setBody(value);}}
async function showSource(focus=true){
  capture();await editor?.destroy();editor=null;rich.replaceChildren();setMode(false);if(focus)source.focus();
}
$('markdown-source').addEventListener('click',()=>void showSource());
$('markdown-rich').addEventListener('click',async()=>{
  if(loading)return;if(editor){editor.focus();return;}
  // Frontmatter pasted in source is also separated before rich parsing.
  if(!frontmatter){const parts=splitMarkdown(body());frontmatter=parts.frontmatter;setBody(parts.body);unterminated=parts.unterminated;paintMetadata();}
  if(source.value.length>200000){status.textContent=t('markdown.too_large');return;}
  if(unterminated||needsSource(body())||splitMarkdown(body()).frontmatter){status.textContent=t('markdown.source_only');return;}
  loading=true;for(const id of ['markdown-rich','markdown-source','markdown-file','markdown-download','markdown-body'])$(id).disabled=true;
  status.textContent=t('markdown.loading');
  try{
    const module=await import('./assets/milkdown/milkdown.js');
    // Imported only on demand, with the matching locally bundled editor CSS.
    if(!document.querySelector('[data-markdown-style]')){const css=document.createElement('link');css.rel='stylesheet';css.href='./assets/milkdown/milkdown.css';css.dataset.markdownStyle='';document.head.append(css);}
    editor=await module.createMarkdownEditor({root:rich,value:body(),label:t('markdown.body'),taskLabel:text=>t('markdown.task',{text}),escape:()=>$('markdown-source').focus()});
    if(!editor){status.textContent=t('markdown.source_only');return;}
    setMode(true);status.textContent=t('markdown.normalization');editor.focus();
  }catch{rich.replaceChildren();status.textContent=t('markdown.failed');setMode(false);}
  finally{loading=false;for(const id of ['markdown-rich','markdown-source','markdown-file','markdown-download','markdown-body'])$(id).disabled=false;}
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

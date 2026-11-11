import {isPlantUml} from './plantuml.js';
import {editorLanguages} from '../src/editor-languages.js';
import {$prose} from '@milkdown/kit/utils';
import {Plugin,PluginKey,TextSelection} from '@milkdown/kit/prose/state';
import {Decoration,DecorationSet} from '@milkdown/kit/prose/view';
import {t} from '../src/i18n.js';
let highlighter;
const key=new PluginKey('timekeeper-code-highlights');
export function codeTools(readOnly){
  const controls=new Set(),cache=new WeakMap();
  const afterCode=(view,position)=>{
    if(readOnly())return false;
    const {state}=view,paragraph=state.schema.nodes.paragraph;let $after=state.doc.resolve(position);
    // A final fence may be inside a quote or list. Continue after that
    // container rather than adding an invisible paragraph inside its source.
    while(!$after.nodeAfter&&$after.depth)$after=state.doc.resolve($after.after());
    position=$after.pos;
    let transaction=state.tr;
    if(!$after.nodeAfter){
      if(!$after.parent.canReplaceWith($after.index(),$after.index(),paragraph))return false;
      // A place to put the caret is presentation until the user types. Keep it
      // out of undo history and omit the empty trailing paragraph from Markdown.
      transaction=transaction.insert(position,paragraph.create()).setMeta('addToHistory',false);
    }else if(!$after.nodeAfter.isTextblock)return false;
    view.dispatch(transaction.setSelection(TextSelection.create(transaction.doc,position+1)).scrollIntoView());view.focus();return true;
  };
  const decorations=doc=>{
    if(!highlighter)return DecorationSet.empty;
    const marks=[];doc.descendants((node,pos)=>{if(node.type.name!=='code_block'||(String(node.attrs.language).toLowerCase()==='mermaid'||isPlantUml(node.attrs.language)))return;let spans=cache.get(node);if(!spans){spans=highlighter(node.textContent,node.attrs.language);cache.set(node,spans);}for(const span of spans)marks.push(Decoration.inline(pos+1+span.from,pos+1+span.to,{class:span.className}));});return DecorationSet.create(doc,marks);
  };
  const plugin=$prose(()=>new Plugin({key,state:{init:(_,state)=>decorations(state.doc),apply:(transaction,previous)=>transaction.docChanged||transaction.getMeta(key)?decorations(transaction.doc):previous},props:{
    decorations:state=>key.getState(state),
    handleKeyDown:(view,event)=>{
      if(readOnly()||view.composing||event.isComposing||event.shiftKey||event.altKey||event.ctrlKey||event.metaKey||!['ArrowDown','ArrowRight'].includes(event.key))return false;
      const {$cursor}=view.state.selection;
      if(!$cursor||$cursor.parent.type.name!=='code_block')return false;
      if(event.key==='ArrowRight'?$cursor.parentOffset!==$cursor.parent.content.size:!view.endOfTextblock('down'))return false;
      return afterCode(view,$cursor.after());
    },
    handleClick:(view,_position,event)=>{
      if(event.target!==view.dom||event.button!==0||event.shiftKey||event.altKey||event.ctrlKey||event.metaKey)return false;
      const end=TextSelection.atEnd(view.state.doc).$from;if(end.parent.type.name!=='code_block')return false;
      const block=view.nodeDOM(end.before());
      if(!block||event.clientY<=block.getBoundingClientRect().bottom)return false;
      return afterCode(view,end.after());
    }
  },view:view=>{
    let disposed=false,loading=false;
    const load=()=>{let hasCode=false;view.state.doc.descendants(node=>{if(node.type.name==='code_block'&&node.attrs.language&&!['mermaid','plantuml','puml','text','plaintext'].includes(String(node.attrs.language).toLowerCase()))hasCode=true;});if(!hasCode||highlighter||loading)return;loading=true;
      void import(new URL('./code-highlight.js',import.meta.url).href).then(module=>{if(disposed)return;highlighter=module.highlightCode;view.dispatch(view.state.tr.setMeta(key,true));}).catch(()=>{});
    };load();return {update:load,destroy(){disposed=true;}};
  }}));
  const nodeView=(initial,view,getPos)=>{
    let node=initial,copyTimer;
    const dom=document.createElement('div'),tools=document.createElement('div'),languageButton=document.createElement('button'),language=document.createElement('input'),copy=document.createElement('button'),status=document.createElement('span'),pre=document.createElement('pre'),code=document.createElement('code'),popup=document.createElement('div');
    dom.className='markdown-code-block';tools.className='markdown-code-controls';tools.contentEditable='false';tools.setAttribute('role','group');tools.setAttribute('aria-label',t('markdown.code_language'));
    popup.className='markdown-code-language';popup.popover='auto';popup.id='code-language-'+crypto.randomUUID();popup.setAttribute('role','dialog');popup.setAttribute('aria-label',t('markdown.code_language'));
    language.value=node.attrs.language||'';language.placeholder='text';language.maxLength=64;language.setAttribute('aria-label',t('markdown.code_language'));const choices=document.createElement('datalist');choices.id=popup.id+'-choices';for(const name of [...editorLanguages,'mermaid','plantuml','puml']){const option=document.createElement('option');option.value=name;choices.append(option);}language.setAttribute('list',choices.id);
    const label=document.createElement('label');label.textContent=t('markdown.code_language');label.append(language);popup.append(label,choices);
    languageButton.type=copy.type='button';languageButton.setAttribute('aria-label',t('markdown.code_language'));languageButton.title=t('markdown.code_language');languageButton.setAttribute('aria-haspopup','dialog');languageButton.setAttribute('aria-controls',popup.id);languageButton.setAttribute('aria-expanded','false');
    copy.setAttribute('aria-label',t('markdown.copy_code'));copy.title=t('markdown.copy_code');const glyph=document.createElement('span');glyph.className='icon bi bi-copy';glyph.setAttribute('aria-hidden','true');copy.append(glyph);status.className='visually-hidden';status.setAttribute('role','status');tools.append(languageButton,copy,status,popup);pre.append(code);dom.append(tools,pre);
    const close=()=>{if(popup.matches(':popover-open'))popup.hidePopover();};
    const paint=()=>{language.disabled=languageButton.disabled=readOnly();const name=(node.attrs.language||'text').toUpperCase();languageButton.textContent=name;languageButton.setAttribute('aria-label',t('markdown.code_language')+': '+name);languageButton.title=t('markdown.code_language')+': '+name;if(readOnly())close();};controls.add(paint);paint();
    popup.addEventListener('beforetoggle',event=>languageButton.setAttribute('aria-expanded',String(event.newState==='open')));
    languageButton.addEventListener('click',()=>{if(readOnly())return;if(popup.matches(':popover-open')){close();return;}popup.showPopover();const rect=languageButton.getBoundingClientRect();popup.style.left=Math.max(8,Math.min(rect.right-popup.offsetWidth,innerWidth-popup.offsetWidth-8))+'px';popup.style.top=Math.max(8,Math.min(rect.bottom+4,innerHeight-popup.offsetHeight-8))+'px';language.focus();language.select();});
    const apply=()=>{if(readOnly())return;const pos=getPos();if(typeof pos==='number'&&language.value.trim()!==node.attrs.language)view.dispatch(view.state.tr.setNodeMarkup(pos,undefined,{...node.attrs,language:language.value.trim()}));close();view.focus();};
    language.addEventListener('change',apply);popup.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();apply();}else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close();languageButton.focus();}});
    copy.addEventListener('click',()=>{void navigator.clipboard.writeText(node.textContent).then(()=>{status.textContent=t('markdown.copied');glyph.className='icon bi bi-check2';clearTimeout(copyTimer);copyTimer=setTimeout(()=>glyph.className='icon bi bi-copy',2000);}).catch(()=>status.textContent=t('markdown.copy_failed'));});
    return {dom,contentDOM:code,stopEvent:event=>tools.contains(event.target),ignoreMutation:mutation=>tools.contains(mutation.target),update(next){if(next.type!==node.type||(String(next.attrs.language).toLowerCase()==='mermaid'||isPlantUml(next.attrs.language)))return false;node=next;if(document.activeElement!==language)language.value=node.attrs.language||'';paint();return true;},destroy(){controls.delete(paint);clearTimeout(copyTimer);close();}};
  };
  return {plugin,nodeView,setReadOnly:()=>controls.forEach(paint=>paint())};
}

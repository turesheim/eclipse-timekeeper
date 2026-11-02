import {$prose} from '@milkdown/kit/utils';
import {Plugin,PluginKey} from '@milkdown/kit/prose/state';
import {Decoration,DecorationSet} from '@milkdown/kit/prose/view';
import {t} from '../src/i18n.js';
let highlighter;
const key=new PluginKey('timekeeper-code-highlights');
export function codeTools(readOnly){
  const controls=new Set();
  const decorations=doc=>{
    if(!highlighter)return DecorationSet.empty;
    const marks=[];doc.descendants((node,pos)=>{if(node.type.name!=='code_block'||String(node.attrs.language).toLowerCase()==='mermaid')return;for(const span of highlighter(node.textContent,node.attrs.language))marks.push(Decoration.inline(pos+1+span.from,pos+1+span.to,{class:span.className}));});return DecorationSet.create(doc,marks);
  };
  const plugin=$prose(()=>new Plugin({key,state:{init:(_,state)=>decorations(state.doc),apply:(transaction,previous)=>transaction.docChanged||transaction.getMeta(key)?decorations(transaction.doc):previous},props:{decorations:state=>key.getState(state)},view:view=>{
    let disposed=false;
    const load=()=>{let hasCode=false;view.state.doc.descendants(node=>{if(node.type.name==='code_block')hasCode=true;});if(!hasCode||highlighter)return;
      void import(new URL('./source-editor.js',import.meta.url).href).then(module=>{if(disposed)return;highlighter=module.highlightCode;view.dispatch(view.state.tr.setMeta(key,true));}).catch(()=>{});
    };load();return {update:load,destroy(){disposed=true;}};
  }}));
  const nodeView=(initial,view,getPos)=>{
    let node=initial;const dom=document.createElement('div'),tools=document.createElement('div'),label=document.createElement('label'),language=document.createElement('input'),copy=document.createElement('button'),status=document.createElement('span'),pre=document.createElement('pre'),code=document.createElement('code');
    dom.className='markdown-code-block';tools.className='markdown-code-controls';tools.contentEditable='false';language.value=node.attrs.language||'';language.placeholder='text';language.maxLength=64;language.setAttribute('aria-label',t('markdown.code_language'));label.textContent=t('markdown.code_language');label.append(language);copy.type='button';copy.textContent=t('markdown.copy_code');status.setAttribute('role','status');tools.append(label,copy,status);pre.append(code);dom.append(tools,pre);
    const paint=()=>language.disabled=readOnly();controls.add(paint);paint();
    language.addEventListener('change',()=>{if(readOnly())return;const pos=getPos();if(typeof pos==='number')view.dispatch(view.state.tr.setNodeMarkup(pos,undefined,{...node.attrs,language:language.value.trim()}));});
    copy.addEventListener('click',()=>{void navigator.clipboard.writeText(node.textContent).then(()=>status.textContent=t('markdown.copied')).catch(()=>status.textContent=t('markdown.copy_failed'));});
    return {dom,contentDOM:code,stopEvent:event=>tools.contains(event.target),ignoreMutation:mutation=>tools.contains(mutation.target),update(next){if(next.type!==node.type||String(next.attrs.language).toLowerCase()==='mermaid')return false;node=next;if(document.activeElement!==language)language.value=node.attrs.language||'';paint();return true;},destroy(){controls.delete(paint);}};
  };
  return {plugin,nodeView,setReadOnly:()=>controls.forEach(paint=>paint())};
}

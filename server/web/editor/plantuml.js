import {$node,$prose} from '@milkdown/kit/utils';
import {Plugin,NodeSelection} from '@milkdown/kit/prose/state';
import {plantumlSourceAllowed,plantumlImage} from '../src/plantuml-svg.js';
import {t} from '../src/i18n.js';
export const isPlantUml=language=>['plantuml','puml'].includes(String(language).toLowerCase());
export const plantumlDiagram=$node('plantuml_diagram',()=>({group:'block',atom:true,selectable:true,
  attrs:{value:{default:'',validate:'string'},language:{default:'plantuml',validate:'string'}},leafText:node=>node.attrs.value,toDOM:()=>['div',{'data-plantuml-diagram':''}],
  parseMarkdown:{match:node=>node.type==='code'&&isPlantUml(node.lang),runner:(state,node,type)=>state.addNode(type,{value:node.value,language:node.lang})},
  toMarkdown:{match:node=>node.type.name==='plantuml_diagram',runner:(state,node)=>state.addNode('code',undefined,node.attrs.value,{lang:node.attrs.language})}
}));
export const plantumlPresentation=$prose(()=>new Plugin({appendTransaction:(transactions,_before,state)=>{
  if(!transactions.some(transaction=>transaction.docChanged))return null;const blocks=[];state.doc.descendants((node,pos)=>{if(node.type.name==='code_block'&&isPlantUml(node.attrs.language))blocks.push({node,pos});});if(!blocks.length)return null;
  const transaction=state.tr,selected=blocks.find(({node,pos})=>state.selection.from>=pos&&state.selection.to<=pos+node.nodeSize);
  for(const {node,pos}of blocks.reverse())transaction.replaceWith(pos,pos+node.nodeSize,state.schema.nodes.plantuml_diagram.create({value:node.textContent,language:node.attrs.language}));
  if(selected)transaction.setSelection(NodeSelection.create(transaction.doc,transaction.mapping.map(selected.pos)));return transaction;
}}));
let renderer;
export function plantumlBlock(initial){
  let node=initial,sourceKey,timer,generation=0,controller,url;
  const dom=document.createElement('div'),preview=document.createElement('div'),status=document.createElement('p'),image=document.createElement('img');
  dom.className='markdown-plantuml-block';dom.contentEditable='false';preview.className='markdown-diagram-preview';status.setAttribute('role','status');image.alt=t('markdown.plantuml_diagram');image.hidden=true;preview.append(status,image);dom.append(preview);
  const clear=()=>{controller?.abort();controller=null;clearTimeout(timer);image.removeAttribute('src');if(url)URL.revokeObjectURL(url);url=null;image.hidden=true;};
  const schedule=()=>{
    const value=node.attrs.value,dark=document.documentElement.dataset.bsTheme==='dark',key=String(dark)+'|'+value;if(key===sourceKey)return;sourceKey=key;const current=++generation;clear();
    if(!value.trim()){status.textContent='';return;}
    if(new TextEncoder().encode(value).length>10000){status.textContent=t('markdown.plantuml_too_large');return;}
    if(!plantumlSourceAllowed(value)){status.textContent=t('markdown.plantuml_external');return;}
    status.textContent=t('markdown.plantuml_loading');controller=new AbortController();const signal=controller.signal;
    timer=setTimeout(async()=>{
      try{
        renderer??=import(new URL('../../plantuml.js',import.meta.url).href);const {renderPlantUml}=await renderer;if(current!==generation||signal.aborted)return;
        const svg=await renderPlantUml(value,{dark,signal});if(current!==generation||signal.aborted)return;const blob=plantumlImage(svg);url=URL.createObjectURL(blob);image.src=url;image.hidden=false;status.textContent='';
      }catch{if(current===generation&&!signal.aborted)status.textContent=t('markdown.plantuml_invalid');}
    },200);
  };
  const theme=new MutationObserver(schedule);theme.observe(document.documentElement,{attributes:true,attributeFilter:['data-bs-theme']});schedule();
  return {dom,update(next){if(next.type!==node.type)return false;node=next;schedule();return true;},destroy(){++generation;clear();theme.disconnect();},ignoreMutation:()=>true};
}

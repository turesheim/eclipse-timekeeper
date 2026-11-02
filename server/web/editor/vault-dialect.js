import {$node,$remark} from '@milkdown/kit/utils';
import {wikiReference} from '../src/wiki-links.js';
import {t} from '../src/i18n.js';

export function vaultDialect({unsupported,resources,readOnly}){
  const wiki=$node('vault_wikilink',()=>({inline:true,group:'inline',atom:true,selectable:true,
    attrs:{value:{default:'',validate:'string'},embed:{default:false,validate:'boolean'}},
    leafText:node=>(node.attrs.embed?'!':'')+'[['+node.attrs.value+']]',
    toDOM:node=>['span',{'data-wiki-reference':node.attrs.value},wikiReference(node.attrs.value,node.attrs.embed)?.label||node.attrs.value],
    parseMarkdown:{match:node=>['vaultWikilink','vaultEmbed'].includes(node.type),runner:(state,node,type)=>state.addNode(type,{value:node.value,embed:node.type==='vaultEmbed'})},
    toMarkdown:{match:node=>node.type.name==='vault_wikilink',runner:(state,node)=>state.addNode(node.attrs.embed?'vaultEmbed':'vaultWikilink',undefined,node.attrs.value)}
  }));
  const fragment=$node('source_fragment',()=>({group:'block',atom:true,isolating:true,selectable:true,
    attrs:{value:{default:'',validate:'string'}},leafText:node=>node.attrs.value,
    toDOM:node=>['pre',{'data-source-fragment':''},node.attrs.value],
    parseMarkdown:{match:node=>node.type==='sourceFragment',runner:(state,node,type)=>state.addNode(type,{value:node.value})},
    toMarkdown:{match:node=>node.type.name==='source_fragment',runner:(state,node)=>state.addNode('sourceFragment',undefined,node.attrs.value)}
  }));
  const project=(tree,source)=>({...tree,children:tree.children.map(node=>unsupported(node)?{type:'sourceFragment',value:source.slice(node.position.start.offset,node.position.end.offset),position:node.position}:node)});
  const guard=$remark('timekeeperSourceFragments',()=>function(){
    (this.data().toMarkdownExtensions??=[]).push({handlers:{sourceFragment:node=>node.value}});
    return (tree,file)=>{tree.children=project(tree,String(file)).children;};
  });
  const nodeViews={
    source_fragment:(initial,view,getPos)=>{
      let node=initial;const dom=document.createElement('div'),caption=document.createElement('label'),text=document.createElement('textarea');dom.className='markdown-source-fragment';dom.contentEditable='false';caption.textContent=t('markdown.fragment');text.setAttribute('aria-label',t('markdown.fragment'));text.value=node.attrs.value;text.readOnly=readOnly();text.spellcheck=false;dom.append(caption,text);
      text.addEventListener('input',()=>{if(readOnly())return;const pos=getPos();if(typeof pos==='number')view.dispatch(view.state.tr.setNodeMarkup(pos,undefined,{value:text.value}));});
      return {dom,stopEvent:()=>true,ignoreMutation:()=>true,update(next){if(next.type!==node.type)return false;node=next;if(text.value!==node.attrs.value)text.value=node.attrs.value;text.readOnly=readOnly();return true;}};
    },
    vault_wikilink:node=>{
      const ref=wikiReference(node.attrs.value,node.attrs.embed),label=ref?.label||node.attrs.value;
      if(node.attrs.embed&&resources?.wikiImage){const result=resources.wikiImage(node.attrs.value,label);if(ref?.width)result.dom.style.width=ref.width+'px';if(ref?.height)result.dom.style.height=ref.height+'px';result.dom.dataset.wikiEmbed=node.attrs.value;return result;}
      const dom=document.createElement('button');dom.type='button';dom.className='markdown-wikilink';dom.contentEditable='false';dom.textContent=label;dom.dataset.wikiReference=node.attrs.value;dom.title=node.attrs.value;
      let disposed=false;
      if(resources?.resolveWiki)void resources.resolveWiki(node.attrs.value).then(entry=>{if(!disposed){dom.dataset.resolved='true';dom.title=entry.path;}}).catch(error=>{if(!disposed){dom.dataset.resolved='false';dom.title=error.message;dom.setAttribute('aria-label',label+' · '+error.message);}});
      dom.addEventListener('click',event=>{event.preventDefault();void resources?.openWiki?.(node.attrs.value).catch(error=>{dom.title=error.message;dom.dataset.resolved='false';});});
      return {dom,stopEvent:()=>true,ignoreMutation:()=>true,destroy(){disposed=true;}};
    }
  };
  return {plugins:[wiki,fragment,guard],project,nodeViews};
}

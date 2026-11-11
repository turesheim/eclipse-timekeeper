import {$node,$remark} from '@milkdown/kit/utils';
import {wikiReference} from '../src/wiki-links.js';
import {t} from '../src/i18n.js';

export function vaultDialect({unsupported,resources,readOnly,linkAllowed}){
  // Render the safe anchor subset of authored HTML through the normal link
  // schema. An inert template only reads attributes/text; no user DOM is mounted.
  const anchor=(html,closed=false)=>{
    if(!/^<a(?:\s|>)/i.test(html))return null;
    const template=document.createElement('template');template.innerHTML=html+(closed?'':'</a>');
    const element=template.content.firstChild;
    if(template.content.childNodes.length!==1||element?.nodeType!==1||element.localName!=='a'||[...element.childNodes].some(child=>child.nodeType!==3))return null;
    const url=element.getAttribute('href');if(!linkAllowed(url))return null;
    return {type:'link',url,title:element.getAttribute('title')||null,children:[{type:'text',value:element.textContent}]};
  };
  const inlineLinks=children=>{
    const result=[];
    for(let i=0;i<children.length;i++){
      const child=children[i],link=child.type==='html'?anchor(child.value):null;
      const end=link?children.findIndex((value,index)=>index>i&&value.type==='html'&&/^<\/a\s*>$/i.test(value.value)):-1;
      if(end>i){result.push({...link,children:children.slice(i+1,end),position:{start:child.position.start,end:children[end].position.end}});i=end;}else result.push(child);
    }
    return result;
  };
  const inlineBreak=$node('inline_break',()=>({inline:true,group:'inline',atom:true,
    leafText:()=> '\n',toDOM:()=>['br'],
    parseMarkdown:{match:node=>node.type==='inlineBreak',runner:(state,_node,type)=>state.addNode(type)},
    toMarkdown:{match:node=>node.type.name==='inline_break',runner:state=>state.addNode('html',undefined,'<br />')}
  }));
  const wiki=$node('vault_wikilink',()=>({inline:true,group:'inline',atom:true,selectable:true,
    attrs:{value:{default:'',validate:'string'},embed:{default:false,validate:'boolean'}},
    leafText:node=>(node.attrs.embed?'!':'')+'[['+node.attrs.value+']]',
    toDOM:node=>['span',{'data-wiki-reference':node.attrs.value},wikiReference(node.attrs.value,node.attrs.embed)?.label||node.attrs.value],
    parseMarkdown:{match:node=>['vaultWikilink','vaultEmbed'].includes(node.type),runner:(state,node,type)=>state.addNode(type,{value:node.value,embed:node.type==='vaultEmbed'})},
    toMarkdown:{match:node=>node.type.name==='vault_wikilink',runner:(state,node)=>state.addNode(node.attrs.embed?'vaultEmbed':'vaultWikilink',undefined,node.attrs.value)}
  }));
  const footnote=$node('footnote_reference',()=>({inline:true,group:'inline',atom:true,
    attrs:{identifier:{default:'',validate:'string'},label:{default:'',validate:'string'}},
    toDOM:node=>['sup',{},['button',{type:'button','data-footnote-reference':node.attrs.identifier,title:t('markdown.footnote')+' '+node.attrs.label,contenteditable:'false'},node.attrs.label]],
    parseMarkdown:{match:node=>node.type==='footnoteReference',runner:(state,node,type)=>state.addNode(type,{identifier:node.identifier,label:node.label||node.identifier})},
    toMarkdown:{match:node=>node.type.name==='footnote_reference',runner:(state,node)=>state.addNode('footnoteReference',undefined,undefined,{identifier:node.attrs.identifier,label:node.attrs.label})}
  }));
  const definition=$node('footnote_definition',()=>({group:'block',content:'block+',defining:true,
    attrs:{identifier:{default:'',validate:'string'},label:{default:'',validate:'string'}},
    toDOM:node=>['aside',{'data-footnote-definition':node.attrs.identifier,'aria-label':t('markdown.footnote')+' '+node.attrs.label},0],
    parseMarkdown:{match:node=>node.type==='footnoteDefinition',runner:(state,node,type)=>state.openNode(type,{identifier:node.identifier,label:node.label||node.identifier}).next(node.children).closeNode()},
    toMarkdown:{match:node=>node.type.name==='footnote_definition',runner:(state,node)=>state.openNode('footnoteDefinition',undefined,{identifier:node.attrs.identifier,label:node.attrs.label}).next(node.content).closeNode()}
  }));
  const callout=$node('callout',()=>({group:'block',content:'block+',defining:true,
    attrs:{marker:{default:'[!NOTE]',validate:'string'},title:{default:'',validate:'string'}},
    toDOM:node=>['blockquote',{'data-callout':node.attrs.marker},0],
    parseMarkdown:{match:node=>node.type==='callout',runner:(state,node,type)=>state.openNode(type,{marker:node.marker,title:node.title}).next(node.children).closeNode()},
    toMarkdown:{match:node=>node.type.name==='callout',runner:(state,node)=>state.openNode('callout',undefined,{marker:node.attrs.marker,title:node.attrs.title}).next(node.content).closeNode()}
  }));
  const convert=(node,block=false)=>{
    // GFM gives omitted trailing cells the same meaning as explicit empty cells.
    // Project complete rows for editing and comparison; retain authored source.
    if(node.type==='table')node={...node,children:node.children.map(row=>({...row,children:[...row.children,
      ...Array.from({length:Math.max(0,node.align.length-row.children.length)},()=>({type:'tableCell',children:[]}))]}))};
    if(block&&node.type==='html'&&/<\/a\s*>\s*$/i.test(node.value)){const link=anchor(node.value,true);if(link)return {type:'paragraph',children:[link],position:node.position};}
    if(node.children)node={...node,children:inlineLinks(node.children.map(child=>node.type==='paragraph'&&child.type==='html'&&/^<br\s*\/?>$/i.test(child.value.trim())
      ?{type:'inlineBreak',position:child.position}:convert(child)))};
    if(node.type!=='blockquote'||node.children?.[0]?.type!=='paragraph')return node;
    const first=node.children[0],text=first.children?.[0];if(text?.type!=='text')return node;
    const match=/^(\[![A-Za-z]+\][+-]?)(?:[ \t]+([^\r\n]*))?(?:\r?\n|$)/.exec(text.value);if(!match)return node;
    const remainder=text.value.slice(match[0].length),children=[...(remainder?[{...text,value:remainder}]:[]),...first.children.slice(1)];
    if(!remainder&&children[0]?.type==='break'&&children[0].data?.isInline)children.shift();
    const blocks=[...(children.length?[{...first,children}]:[]),...node.children.slice(1)];
    if(!blocks.length)return node;
    return {...node,type:'callout',marker:match[1],title:match[2]||'',children:blocks};
  };
  const fragment=$node('source_fragment',()=>({group:'block',atom:true,isolating:true,selectable:true,
    attrs:{value:{default:'',validate:'string'}},leafText:node=>node.attrs.value,
    toDOM:node=>['pre',{'data-source-fragment':''},node.attrs.value],
    parseMarkdown:{match:node=>node.type==='sourceFragment',runner:(state,node,type)=>state.addNode(type,{value:node.value})},
    toMarkdown:{match:node=>node.type.name==='source_fragment',runner:(state,node)=>state.addNode('sourceFragment',undefined,node.attrs.value)}
  }));
  const project=(tree,source)=>({...tree,children:tree.children.map(node=>convert(node,true)).map(node=>unsupported(node)?{type:'sourceFragment',value:source.slice(node.position.start.offset,node.position.end.offset),position:node.position}:node)});
  const guard=$remark('timekeeperSourceFragments',()=>function(){
    (this.data().toMarkdownExtensions??=[]).push({handlers:{sourceFragment:node=>node.value,callout:(node,_parent,state,info)=>{const body=state.containerFlow({type:'root',children:node.children},info).replace(/\n$/,'');return '> '+node.marker+(node.title?' '+node.title:'')+'\n'+body.split('\n').map(line=>'> '+line).join('\n');}}});
    return (tree,file)=>{tree.children=project(tree,String(file)).children;};
  });
  const nodeViews={
    callout:(initial,view,getPos)=>{
      let node=initial;const dom=document.createElement('aside'),header=document.createElement('div'),fold=document.createElement('button'),title=document.createElement('input'),contentDOM=document.createElement('div');dom.className='markdown-callout';header.contentEditable='false';fold.type='button';fold.textContent=node.attrs.marker.replace(/[\[\]!+-]/g,'');fold.setAttribute('aria-label',t('markdown.toggle_callout'));title.value=node.attrs.title;title.setAttribute('aria-label',t('markdown.callout_title'));title.readOnly=readOnly();header.append(fold,title);dom.append(header,contentDOM);let collapsed=node.attrs.marker.endsWith('-');contentDOM.hidden=collapsed;fold.setAttribute('aria-expanded',String(!collapsed));
      fold.addEventListener('click',()=>{collapsed=!collapsed;contentDOM.hidden=collapsed;fold.setAttribute('aria-expanded',String(!collapsed));});title.addEventListener('change',()=>{if(readOnly())return;const pos=getPos();if(typeof pos==='number')view.dispatch(view.state.tr.setNodeMarkup(pos,undefined,{...node.attrs,title:title.value}));});
      return {dom,contentDOM,stopEvent:event=>header.contains(event.target),ignoreMutation:mutation=>header.contains(mutation.target)||mutation.target===contentDOM&&mutation.type==='attributes',update(next){if(next.type!==node.type)return false;node=next;if(document.activeElement!==title)title.value=node.attrs.title;title.readOnly=readOnly();return true;}};
    },
    source_fragment:(initial,view,getPos)=>{
      let node=initial;const dom=document.createElement('div'),caption=document.createElement('label'),text=document.createElement('textarea');dom.className='markdown-source-fragment';dom.contentEditable='false';caption.textContent=t('markdown.fragment');text.setAttribute('aria-label',t('markdown.fragment'));text.value=node.attrs.value;text.readOnly=readOnly();text.spellcheck=false;dom.append(caption,text);
      text.addEventListener('input',()=>{if(readOnly())return;const pos=getPos();if(typeof pos==='number')view.dispatch(view.state.tr.setNodeMarkup(pos,undefined,{value:text.value}));});
      return {dom,stopEvent:()=>true,ignoreMutation:()=>true,update(next){if(next.type!==node.type)return false;node=next;if(text.value!==node.attrs.value)text.value=node.attrs.value;text.readOnly=readOnly();return true;}};
    },
    vault_wikilink:node=>{
      const ref=wikiReference(node.attrs.value,node.attrs.embed),label=ref?.label||node.attrs.value;
      if(node.attrs.embed&&/\.(png|jpe?g|gif|webp)$/i.test(ref?.path||'')&&resources?.wikiImage){const result=resources.wikiImage(node.attrs.value,label);if(ref?.width)result.dom.style.width=ref.width+'px';if(ref?.height)result.dom.style.height=ref.height+'px';result.dom.dataset.wikiEmbed=node.attrs.value;return result;}
      const dom=document.createElement('button');dom.type='button';dom.className='markdown-wikilink';dom.contentEditable='false';dom.textContent=label;dom.dataset.wikiReference=node.attrs.value;dom.title=node.attrs.value;
      let disposed=false,timer;
      const preview=()=>{if(!resources?.previewWiki)return;clearTimeout(timer);timer=setTimeout(()=>{void resources.previewWiki(node.attrs.value).then(text=>{if(!disposed)dom.title=text;}).catch(()=>{});},300);};dom.addEventListener('mouseenter',preview);dom.addEventListener('focus',preview);dom.addEventListener('mouseleave',()=>clearTimeout(timer));dom.addEventListener('blur',()=>clearTimeout(timer));
      if(resources?.resolveWiki)void resources.resolveWiki(node.attrs.value).then(entry=>{if(!disposed){dom.dataset.resolved='true';dom.title=entry.path;}}).catch(error=>{if(!disposed){dom.dataset.resolved='false';dom.title=error.message;dom.setAttribute('aria-label',label+' · '+error.message);}});
      dom.addEventListener('click',event=>{event.preventDefault();void resources?.openWiki?.(node.attrs.value,dom).catch(error=>{if(!disposed){dom.title=error.message;dom.dataset.resolved='false';dom.dispatchEvent(new CustomEvent('timekeeper:markdown-link-error',{bubbles:true,detail:error.message}));}});});
      return {dom,stopEvent:()=>true,ignoreMutation:()=>true,destroy(){disposed=true;clearTimeout(timer);}};
    }
  };
  return {guard,plugins:[inlineBreak,wiki,footnote,definition,callout,fragment],project,nodeViews};
}

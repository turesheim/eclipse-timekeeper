import {codeTools} from './code-tools.js';
// This build-time entry is bundled to one local native ES module. The application
// and source-mode fallback do not import Milkdown until rich mode is requested.
import {Editor,rootCtx,defaultValueCtx,editorViewCtx,editorViewOptionsCtx,remarkCtx,remarkStringifyOptionsCtx,serializerCtx,parserCtx} from '@milkdown/kit/core';
import {commonmark,toggleStrongCommand,toggleEmphasisCommand,wrapInHeadingCommand,wrapInBulletListCommand,wrapInOrderedListCommand,wrapInBlockquoteCommand,toggleInlineCodeCommand,createCodeBlockCommand,turnIntoTextCommand,toggleLinkCommand,updateLinkCommand,insertHrCommand} from '@milkdown/kit/preset/commonmark';
import {gfm,insertTableCommand,toggleStrikethroughCommand} from '@milkdown/kit/preset/gfm';
import {history,undoCommand,redoCommand} from '@milkdown/kit/plugin/history';
import {getMarkdown,callCommand,$prose,$node,$remark,$inputRule} from '@milkdown/kit/utils';
import {Plugin,TextSelection} from '@milkdown/kit/prose/state';
import {InputRule} from '@milkdown/kit/prose/inputrules';
import {dailyNoteDate,dailyNoteHref,dayNoteNavigation} from '../src/journal-links.js';
import {markdownSource} from './markdown-source.js';
import {structuredCommand} from './structured-commands.js';
import {wikiReference,headingAnchor} from '../src/wiki-links.js';
import {clipboardSlice,spreadsheetSlice} from './clipboard.js';
import {vaultDialect} from './vault-dialect.js';
import {remarkDailyWikilinks} from './remark-daily-wikilinks.js';
import {hasUnsupportedSyntax,normalizeEditorMarkdown} from '../src/markdown-document.js';
import {t} from '../src/i18n.js';
import '@milkdown/kit/prose/view/style/prosemirror.css';
import '@milkdown/kit/prose/tables/style/tables.css';

const commands={bold:toggleStrongCommand,italic:toggleEmphasisCommand,strike:toggleStrikethroughCommand,inline_code:toggleInlineCodeCommand,code_block:createCodeBlockCommand,paragraph:turnIntoTextCommand,heading:wrapInHeadingCommand,list:wrapInBulletListCommand,ordered:wrapInOrderedListCommand,quote:wrapInBlockquoteCommand,table:insertTableCommand,hr:insertHrCommand,undo:undoCommand,redo:redoCommand};
const dailyWikilink=$node('daily_wikilink',()=>({
  inline:true,group:'inline',atom:true,selectable:true,leafText:node=>'[['+node.attrs.date+']]',
  attrs:{date:{default:'',validate:'string'}},
  toDOM:node=>['a',{'data-day-note-date':node.attrs.date,href:dailyNoteHref(node.attrs.date),contenteditable:'false'},node.attrs.date],
  parseMarkdown:{match:node=>node.type==='dailyWikilink',runner:(state,node,type)=>state.addNode(type,{date:node.value})},
  toMarkdown:{match:node=>node.type.name==='daily_wikilink',runner:(state,node)=>state.addNode('dailyWikilink',undefined,node.attrs.date)}
}));
const dailyWikilinkInput=$inputRule(ctx=>new InputRule(/\[\[(\d{4}-\d{2}-\d{2})\]\]$/,(state,match,start,end)=>
  dailyNoteDate(match[1])?state.tr.replaceWith(start,end,dailyWikilink.type(ctx).create({date:match[1]})):null));
const dailyWikilinkRemark=$remark('timekeeperDailyWikilinks',()=>remarkDailyWikilinks);
const sourceNodes=new Set(['html','imageReference','linkReference','definition']);
const safeAsset=url=>typeof url==='string'&&/^assets\/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(url);
const safeLink=url=>{if(typeof url!=='string'||!url.trim())return false;try{return ['https:','http:','mailto:'].includes(new URL(url,location.origin).protocol);}catch{return false;}};
const safeDocument=(doc,assetAllowed=safeAsset,linkAllowed=safeLink)=>{
  let safe=true;
  doc.descendants(node=>{if(node.type.name==='image'&&!assetAllowed(node.attrs.src)||node.type.name==='daily_wikilink'&&!dailyNoteDate(node.attrs.date)||node.marks.some(mark=>mark.type.name==='link'&&!linkAllowed(mark.attrs.href)))safe=false;return safe;});
  return safe;
};
const unsupported=(node,assetAllowed=safeAsset,linkAllowed=safeLink)=>node.type==='code'&&!!node.meta||!['code','inlineCode'].includes(node.type)&&(sourceNodes.has(node.type)||node.type==='image'&&!assetAllowed(node.url)||node.type==='link'&&!linkAllowed(node.url)||node.type==='text'&&hasUnsupportedSyntax(node.value.replace(/\[![A-Za-z]+\][+-]?/g,''))||Boolean(node.children?.some(child=>unsupported(child,assetAllowed,linkAllowed))));
// Presentation choices (blank-line spacing, bullet markers) can normalize on an
// actual rich edit. All semantic fields must survive the initial round trip.
const semantic=value=>{
  if(Array.isArray(value))return value.map(semantic);
  if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([key])=>!['position','spread'].includes(key)).map(([key,entry])=>[key,semantic(entry)]));
  return typeof value==='string'?value.replace(/\r\n?/g,'\n'):value;
};
function taskItem(node,view,getPos,taskLabel){
  const dom=document.createElement('li'),contentDOM=document.createElement('div'),checkbox=document.createElement('input');
  checkbox.type='checkbox';checkbox.contentEditable='false';
  const paint=value=>{
    dom.dataset.task=value.attrs.checked===null?'false':'true';checkbox.hidden=value.attrs.checked===null;
    checkbox.checked=Boolean(value.attrs.checked);checkbox.setAttribute('aria-label',taskLabel(value.textContent));
  };
  paint(node);dom.append(checkbox,contentDOM);
  checkbox.addEventListener('change',()=>{
    const position=getPos();if(typeof position!=='number')return;
    const current=view.state.doc.nodeAt(position);view.dispatch(view.state.tr.setNodeMarkup(position,undefined,{...current.attrs,checked:checkbox.checked}));
  });
  return {dom,contentDOM,update(value){if(value.type!==node.type)return false;paint(value);return true;},stopEvent:event=>event.target===checkbox,ignoreMutation:mutation=>mutation.type==='attributes'&&(mutation.target===checkbox||mutation.target===dom)};
}
let diagrams;
function mermaidBlock(node){
  const dom=document.createElement('div'),pre=document.createElement('pre'),code=document.createElement('code'),preview=document.createElement('div'),status=document.createElement('p'),image=document.createElement('img');
  dom.className='markdown-mermaid-block';pre.dataset.language=node.attrs.language;pre.append(code);
  preview.className='markdown-mermaid-preview';status.setAttribute('role','status');
  image.alt=t('markdown.mermaid_diagram');image.hidden=true;
  preview.append(status,image);dom.append(pre,preview);
  let timer,generation=0,source;
  const schedule=value=>{
    if(value===source)return;source=value;const current=++generation;clearTimeout(timer);image.hidden=true;image.removeAttribute('src');
    if(!value.trim()){status.textContent='';return;}
    if(new TextEncoder().encode(value).length>10000){status.textContent=t('markdown.mermaid_too_large');return;}
    if(/(?:https?:\/\/|data:|file:|javascript:)/i.test(value)){status.textContent=t('markdown.mermaid_external');return;}
    status.textContent=t('markdown.mermaid_loading');
    timer=setTimeout(async()=>{
      try{
        diagrams??=import(new URL('../mermaid/mermaid.js',import.meta.url).href);
        const {renderDiagram}=await diagrams,svg=await renderDiagram(value);
        if(current!==generation)return;
        image.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(svg);
        image.hidden=false;status.textContent='';
      }catch{if(current===generation)status.textContent=t('markdown.mermaid_invalid');}
    },300);
  };
  schedule(node.textContent);
  return {dom,contentDOM:code,update(value){if(value.type!==node.type||String(value.attrs.language).toLowerCase()!=='mermaid')return false;schedule(value.textContent);return true;},destroy(){++generation;clearTimeout(timer);image.removeAttribute('src');},ignoreMutation:mutation=>preview.contains(mutation.target),stopEvent:event=>preview.contains(event.target)};
}
export async function createMarkdownEditor({root,value,label,taskLabel,escape,changed,stateChanged,description,readOnly=false,resources,uploadFiles}){
  const assetAllowed=url=>resources?resources.allowsAsset(url):safeAsset(url),linkAllowed=url=>safeLink(url)||!!resources?.allowsLink(url);
  const code=codeTools(()=>readOnly);
  const targets=new Set();let plainPaste=false;
  const bookmark=(position)=>{const view=editor.action(ctx=>ctx.get(editorViewCtx));const target={selection:(position===undefined?view.state.selection:TextSelection.near(view.state.doc.resolve(position))).getBookmark()};targets.add(target);return target;};
  const dialect=vaultDialect({unsupported:node=>unsupported(node,assetAllowed,linkAllowed),resources,readOnly:()=>readOnly});
  const changePlugin=$prose(()=>new Plugin({state:{init:()=>null,apply:transaction=>{for(const target of targets)target.selection=target.selection.map(transaction.mapping);return null;}},filterTransaction:transaction=>!transaction.docChanged||!readOnly&&safeDocument(transaction.doc,assetAllowed,linkAllowed),view:()=>({update:(view,previous)=>{
    const documentChanged=!view.state.doc.eq(previous.doc);if(documentChanged)changed?.();
    if(documentChanged||!view.state.selection.eq(previous.selection))stateChanged?.();
  }})}));
  const editor=Editor.make().config(ctx=>{
    ctx.set(rootCtx,root);ctx.set(defaultValueCtx,value);
    ctx.update(remarkStringifyOptionsCtx,options=>({...options,resourceLink:true,rule:'-',ruleRepetition:3,ruleSpaces:false}));
    ctx.set(editorViewOptionsCtx,{
      attributes:()=>({role:'textbox','aria-label':label,'aria-multiline':'true','aria-readonly':String(readOnly),...(!readOnly&&description?{'aria-describedby':description}:{}),tabindex:'0',spellcheck:'true'}),
      editable:()=>!readOnly,
      handleKeyDown:(_view,event)=>{
        if(event.key.toLowerCase()==='v'&&(event.metaKey||event.ctrlKey))plainPaste=event.shiftKey;

        const link=event.target.closest?.('a[data-day-note-date]');
        if(event.key==='Enter'&&dailyNoteDate(link?.dataset.dayNoteDate)){
          event.preventDefault();link.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,button:0,ctrlKey:event.ctrlKey,metaKey:event.metaKey}));return true;
        }
        if(event.key==='Escape'){escape();return true;}return false;
      },
      nodeViews:{...dialect.nodeViews,list_item:(node,view,getPos)=>taskItem(node,view,getPos,taskLabel),code_block:(node,view,getPos)=>String(node.attrs.language).toLowerCase()==='mermaid'?mermaidBlock(node):code.nodeView(node,view,getPos),image:node=>{
        if(resources)return resources.image(node.attrs.src,node.attrs.alt||'');
        const dom=document.createElement(safeAsset(node.attrs.src)?'img':'span');
        if(dom.tagName==='IMG'){dom.src='/api/v1/journal-assets/'+node.attrs.src.slice(7);dom.alt=node.attrs.alt||'';dom.loading='lazy';}
        else dom.textContent=node.attrs.alt||'';
        return {dom};
      }},
      // Ordinary clicks edit text. A platform modifier opens only web links,
      // including authenticated journal attachments, without changing the draft.
      handleDOMEvents:{click:(_view,event)=>{
        const foot=event.target.closest('[data-footnote-reference]');if(foot){event.preventDefault();const target=[...root.querySelectorAll('[data-footnote-definition]')].find(node=>node.dataset.footnoteDefinition===foot.dataset.footnoteReference);if(target){const pos=_view.posAtDOM(target,0);_view.dispatch(_view.state.tr.setSelection(TextSelection.near(_view.state.doc.resolve(pos))).scrollIntoView());}return true;}
        const link=event.target.closest('a[href]');if(!link)return false;
        const date=dailyNoteDate(link.dataset.dayNoteDate);
        if(date){
          event.preventDefault();
          if(event.button===0&&(event.metaKey||event.ctrlKey))window.open(dailyNoteHref(date),'_blank','noopener,noreferrer');
          else if(event.button===0){
            const navigation=new CustomEvent(dayNoteNavigation,{bubbles:true,cancelable:true,detail:{date,guards:[],leave:[]}});
            if(root.dispatchEvent(navigation))location.assign(dailyNoteHref(date));
          }
          return true;
        }
        event.preventDefault();const href=link.getAttribute('href');
        if(event.button===0&&(event.metaKey||event.ctrlKey)&&resources?.allowsLink(href)){resources.openLink(href);return true;}
        if(event.button===0&&(event.metaKey||event.ctrlKey)&&safeLink(href)){
          const url=new URL(safeAsset(href)?'/api/v1/journal-assets/'+href.slice(7):href,location.href);
          if(['http:','https:'].includes(url.protocol))window.open(url.href,'_blank','noopener,noreferrer');
        }
        return true;
      }},
      handlePaste:(view,event)=>{
        if(readOnly)return true;
        const files=[...(event.clipboardData?.files||[])];
        if(files.length&&uploadFiles){event.preventDefault();uploadFiles(files,bookmark());return true;}
        const text=event.clipboardData?.getData('text/plain');if(text===undefined)return false;event.preventDefault();
        const selection=view.state.selection,code=selection.$from.parent.type.spec.code||selection.$from.marks().some(mark=>mark.type.name==='inlineCode');
        if(!plainPaste&&!code&&!selection.empty&&/^https?:\/\/[^\s]+$/i.test(text)&&linkAllowed(text)){
          view.dispatch(view.state.tr.addMark(selection.from,selection.to,view.state.schema.marks.link.create({href:text})));return true;
        }
        const html=event.clipboardData?.getData('text/html');
        if(!plainPaste&&!code){
          const slice=html?clipboardSlice(view.state.schema,html,text,linkAllowed):spreadsheetSlice(view.state.schema,text);
          if(slice){view.dispatch(view.state.tr.replaceSelection(slice).scrollIntoView());return true;}
        }
        const match=/^\[\[(\d{4}-\d{2}-\d{2})\]\]$/.exec(text);
        if(match&&dailyNoteDate(match[1])&&!code)view.dispatch(view.state.tr.replaceSelectionWith(view.state.schema.nodes.daily_wikilink.create({date:match[1]}),true));
        else view.dispatch(view.state.tr.insertText(text));plainPaste=false;return true;
      },
      handleDrop:(view,event,slice,moved)=>{
        const files=[...(event.dataTransfer?.files||[])];if(readOnly)return true;
        if(files.length&&uploadFiles){event.preventDefault();uploadFiles(files,bookmark(view.posAtCoords({left:event.clientX,top:event.clientY})?.pos));return true;}
        // Native intra-editor moves retain schema and history; external HTML is
        // routed through the same sanitizer as paste.
        if(moved)return false;
        const text=event.dataTransfer?.getData('text/plain')||'',html=event.dataTransfer?.getData('text/html')||'';
        event.preventDefault();const at=view.posAtCoords({left:event.clientX,top:event.clientY})?.pos;if(at!==undefined)view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(at))));
        const safe=html?clipboardSlice(view.state.schema,html,text,linkAllowed):null;
        view.dispatch(safe?view.state.tr.replaceSelection(safe):view.state.tr.insertText(text));return true;
      }
    });
  }).use(commonmark).use(gfm).use(history).use(dailyWikilink).use(dailyWikilinkRemark).use(dailyWikilinkInput).use(dialect.plugins).use(code.plugin).use(changePlugin);
  try{
    await editor.create();
    const view=editor.action(ctx=>ctx.get(editorViewCtx)),original=view.state.doc;
    const serialized=normalizeEditorMarkdown(editor.action(getMarkdown()));
    const safe=editor.action(ctx=>{const parser=ctx.get(remarkCtx),tree=dialect.project(parser.parse(value),value);return JSON.stringify(semantic(tree))===JSON.stringify(semantic(dialect.project(parser.parse(serialized),serialized)));});
    if(!safe){await editor.destroy();return null;}
    const serialize=doc=>normalizeEditorMarkdown(editor.action(ctx=>ctx.get(serializerCtx)(doc)));
    const render=editor.action(ctx=>{
      const parser=ctx.get(remarkCtx);
      return markdownSource({source:value,tree:dialect.project(parser.parse(value),value),document:original,serialize,
        equivalent:(left,right)=>JSON.stringify(semantic(dialect.project(parser.parse(left),left)))===JSON.stringify(semantic(dialect.project(parser.parse(right),right)))});
    });
    const setReadOnly=value=>{readOnly=value;code.setReadOnly();view.setProps({editable:()=>!readOnly});for(const checkbox of root.querySelectorAll('input[type=checkbox]'))checkbox.disabled=readOnly;for(const text of root.querySelectorAll('.markdown-source-fragment textarea,.markdown-callout input'))text.readOnly=readOnly;};
    const command=(name,payload,focus=true)=>{
      if(readOnly)return false;
      if(name==='link'){
        if(!linkAllowed(payload))return false;
        if(!editor.action(callCommand(updateLinkCommand.key,{href:payload}))){
          const {from,empty}=view.state.selection;
          if(empty)view.dispatch(view.state.tr.insertText(payload,from).addMark(from,from+payload.length,view.state.schema.marks.link.create({href:payload})));
          else editor.action(callCommand(toggleLinkCommand.key,{href:payload}));
        }
      }else if(name==='image_properties'){
        const selected=view.state.selection.node;if(!selected)return false;
        if(selected.type.name==='image'&&!payload?.embed){view.dispatch(view.state.tr.setNodeMarkup(view.state.selection.from,undefined,{...selected.attrs,alt:String(payload?.alt||'').slice(0,2000)}));}
        else if(selected.type.name==='vault_wikilink'&&selected.attrs.embed||selected.type.name==='image'&&payload?.embed){
          const target=selected.type.name==='image'?payload.path:selected.attrs.value.split('|')[0],width=payload.width?Number(payload.width):null,height=payload.height?Number(payload.height):null;
          if(width&&(!Number.isInteger(width)||width<1||width>2000)||height&&(!Number.isInteger(height)||height<1||height>2000)||height&&!width)return false;
          const value=target+(width?'|'+width+(height?'x'+height:''):payload.alt?'|'+String(payload.alt).replace(/[\[\]\r\n|]/g,''): '');if(!wikiReference(value,true))return false;
          view.dispatch(view.state.tr.replaceSelectionWith(view.state.schema.nodes.vault_wikilink.create({value,embed:true})));
        }else return false;
      }else if(name==='wiki_embed'||name==='wiki_link'){
        if(!wikiReference(payload?.value,name==='wiki_embed'))return false;view.dispatch(view.state.tr.replaceSelectionWith(view.state.schema.nodes.vault_wikilink.create({value:payload.value,embed:name==='wiki_embed'})));
      }else if(name==='image'||name==='asset_link'){
        if(!assetAllowed(payload?.path))return false;
        if(name==='image')view.dispatch(view.state.tr.replaceSelectionWith(view.state.schema.nodes.image.create({src:payload.path,alt:payload.label})));
        else{
          const {from,to}=view.state.selection,label=payload.label||payload.path;
          view.dispatch(view.state.tr.insertText(label,from,to).addMark(from,from+label.length,view.state.schema.marks.link.create({href:payload.path})));
        }
      }else if(name==='tasks'){
        editor.action(callCommand(wrapInBulletListCommand.key));
        const {from,to,$from,empty}=view.state.selection;let transaction=view.state.tr;
        if(empty){
          for(let depth=$from.depth;depth>0;depth--){const item=$from.node(depth);if(item.type.name==='list_item'){transaction=transaction.setNodeMarkup($from.before(depth),undefined,{...item.attrs,checked:item.attrs.checked??false});break;}}
        }else{
          view.state.doc.nodesBetween(from,to,(item,position)=>{if(item.type.name==='list_item')transaction=transaction.setNodeMarkup(position,undefined,{...item.attrs,checked:item.attrs.checked??false});});
        }
        if(transaction.docChanged)view.dispatch(transaction);
      }else{
        const action=commands[name];if(!action){const applied=structuredCommand(view,name);if(applied&&focus)view.focus();return applied;}
        const level=name==='heading'?(payload??2):undefined;
        if(name==='heading'&&(!Number.isInteger(level)||level<1||level>6))return false;
        if(name==='hr'&&!view.state.selection.empty)view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(view.state.selection.to),-1)));
        editor.action(callCommand(action.key,level));
      }
      if(focus)view.focus();return true;
    };
    let snapshot=original,snapshotText=value,comparisonText,comparisonDocument;
    const markdown=()=>{const current=view.state.doc,result=render(current);snapshot=current;snapshotText=result;return result;};
    const matchesSource=text=>{
      const current=view.state.doc;
      if(text===value&&current.eq(original)||text===snapshotText&&current.eq(snapshot))return true;
      if(text===value||text===snapshotText)return false;
      if(text!==comparisonText){
        comparisonText=text;comparisonDocument=null;
        try{comparisonDocument=editor.action(ctx=>ctx.get(parserCtx)(text));}catch{/* Unknown source stays conservatively dirty. */}
      }
      // A different document cannot be the saved source. Only an equal parse
      // needs exact serialization, e.g. returning to a saved state after a mode switch.
      return !!comparisonDocument&&current.eq(comparisonDocument)&&markdown()===text;
    };
    setReadOnly(readOnly);
    return {
      markdown,
      bookmark,
      insertAt:(target,name,payload)=>{if(readOnly||!target)return false;const insertion=target.selection.resolve(view.state.doc),follow=view.state.selection.eq(insertion),restore={selection:view.state.selection.getBookmark()};if(!follow)targets.add(restore);try{view.dispatch(view.state.tr.setSelection(insertion));const applied=command(name,payload,false);if(!follow)view.dispatch(view.state.tr.setSelection(restore.selection.resolve(view.state.doc)));return applied;}finally{targets.delete(restore);}},
      release:target=>targets.delete(target),
      matchesSource,
      focus:()=>view.focus(),
      setReadOnly,
      command,
      imageDetails:()=>{const node=view.state.selection.node;if(node?.type.name==='image')return {path:node.attrs.src,alt:node.attrs.alt||'',embed:false};if(node?.type.name==='vault_wikilink'&&node.attrs.embed){const ref=wikiReference(node.attrs.value,true);return {...ref,alt:ref?.width?'':ref?.label||'',embed:true};}return null;},
      position:()=>{const source=markdown(),block=view.state.selection.$from.index(0),tree=editor.action(ctx=>ctx.get(remarkCtx).parse(source)),start=tree.children[block]?.position?.start.offset||0;return {anchor:start+view.state.selection.$from.parentOffset,scrollTop:view.dom.scrollTop};},
      restorePosition:position=>{const source=markdown(),tree=editor.action(ctx=>ctx.get(remarkCtx).parse(source));let index=tree.children.findIndex(node=>(node.position?.end.offset??0)>=position.anchor);if(index<0)index=view.state.doc.childCount-1;const block=view.state.doc.child(index);let start=0;for(let i=0;i<index;i++)start+=view.state.doc.child(i).nodeSize;const offset=Math.min(Math.max(0,position.anchor-(tree.children[index]?.position?.start.offset||0)),Math.max(0,block.nodeSize-2));view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(Math.min(view.state.doc.content.size,start+1+offset)))));view.dom.scrollTop=position.scrollTop||0;},
      context:()=>({table:Array.from({length:view.state.selection.$from.depth},(_,index)=>view.state.selection.$from.node(index+1).type.spec.tableRole).includes('cell')||Array.from({length:view.state.selection.$from.depth},(_,index)=>view.state.selection.$from.node(index+1).type.spec.tableRole).includes('header_cell'),block:view.state.selection.$from.parent.type.name}),
      selectedText:()=>view.state.doc.textBetween(view.state.selection.from,view.state.selection.to,'\n'),
      selectionRect:()=>{try{return view.coordsAtPos(view.state.selection.to);}catch{return null;}},
      active:name=>{const type=view.state.schema.marks[{bold:'strong',italic:'emphasis',strike:'strike_through',inline_code:'inlineCode',link:'link'}[name]||name];return !!type&&!!type.isInSet(view.state.storedMarks||view.state.selection.$from.marks());},
      outline:()=>{const values=[];view.state.doc.descendants((node,pos)=>{if(node.type.name==='heading')values.push({text:node.textContent,level:node.attrs.level,pos,anchor:headingAnchor(node.textContent)});});return values;},
      scrollTo:(position)=>{view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(Math.min(position+1,view.state.doc.content.size)))).scrollIntoView());view.focus();},
      slash:()=>{const {$from,empty}=view.state.selection;if(!empty||$from.parent.type.name!=='paragraph')return null;const match=/^\/([\w-]*)$/.exec($from.parent.textBetween(0,$from.parentOffset));return match?{query:match[1],from:$from.start(),to:$from.pos}:null;},
      replaceSlash:(name,payload)=>{const {$from}=view.state.selection;view.dispatch(view.state.tr.delete($from.start(),$from.pos));return command(name,payload);},
      wikiQuery:()=>{const {$from,empty}=view.state.selection;if(!empty||$from.parent.type.spec.code)return null;const match=/\[\[([^\]\n]*)$/.exec($from.parent.textBetween(0,$from.parentOffset));return match?{query:match[1],from:$from.pos-match[0].length,to:$from.pos}:null;},
      insertWiki:(range,path)=>{if(readOnly)return false;view.dispatch(view.state.tr.replaceWith(range.from,range.to,view.state.schema.nodes.vault_wikilink.create({value:path})));view.focus();return true;},
      headingLevel:()=>{
        const {from,to,$from}=view.state.selection;
        if($from.parent.isTextblock)return $from.parent.type.name==='heading'?$from.parent.attrs.level:0;
        let level=0,found=false;
        view.state.doc.nodesBetween(from,to,node=>{if(found)return false;if(node.isTextblock){level=node.type.name==='heading'?node.attrs.level:0;found=true;return false;}return true;});
        return level;
      },
      linkHref:()=>view.state.selection.$from.marks().find(mark=>mark.type.name==='link')?.attrs.href||'',
      destroy:()=>editor.destroy()
    };
  }catch(error){await editor.destroy().catch(()=>{});throw error;}
}

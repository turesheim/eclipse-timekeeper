// This build-time entry is bundled to one local native ES module. The application
// and source-mode fallback do not import Milkdown until rich mode is requested.
import {Editor,rootCtx,defaultValueCtx,editorViewCtx,editorViewOptionsCtx,remarkCtx} from '@milkdown/kit/core';
import {commonmark,toggleStrongCommand,toggleEmphasisCommand,wrapInHeadingCommand,wrapInBulletListCommand,wrapInOrderedListCommand,wrapInBlockquoteCommand,toggleInlineCodeCommand,createCodeBlockCommand,turnIntoTextCommand,toggleLinkCommand,updateLinkCommand} from '@milkdown/kit/preset/commonmark';
import {gfm,insertTableCommand,toggleStrikethroughCommand} from '@milkdown/kit/preset/gfm';
import {history,undoCommand,redoCommand} from '@milkdown/kit/plugin/history';
import {getMarkdown,callCommand,$prose} from '@milkdown/kit/utils';
import {Plugin} from '@milkdown/kit/prose/state';
import {hasUnsupportedSyntax} from '../src/markdown-document.js';
import '@milkdown/kit/prose/view/style/prosemirror.css';
import '@milkdown/kit/prose/tables/style/tables.css';

const commands={bold:toggleStrongCommand,italic:toggleEmphasisCommand,strike:toggleStrikethroughCommand,inline_code:toggleInlineCodeCommand,code_block:createCodeBlockCommand,paragraph:turnIntoTextCommand,heading:wrapInHeadingCommand,list:wrapInBulletListCommand,ordered:wrapInOrderedListCommand,quote:wrapInBlockquoteCommand,table:insertTableCommand,undo:undoCommand,redo:redoCommand};
const sourceNodes=new Set(['html','image','imageReference','linkReference','definition','footnoteReference','footnoteDefinition']);
const safeLink=url=>{if(typeof url!=='string'||!url.trim())return false;try{return ['https:','http:','mailto:'].includes(new URL(url,location.origin).protocol);}catch{return false;}};
const safeDocument=doc=>{
  let safe=true;
  doc.descendants(node=>{if(node.type.name==='image'||node.marks.some(mark=>mark.type.name==='link'&&!safeLink(mark.attrs.href)))safe=false;return safe;});
  return safe;
};
const unsupported=node=>!['code','inlineCode'].includes(node.type)&&(sourceNodes.has(node.type)||node.type==='link'&&!safeLink(node.url)||node.type==='text'&&hasUnsupportedSyntax(node.value)||Boolean(node.children?.some(unsupported)));
// Presentation choices (blank-line spacing, bullet markers) can normalize on an
// actual rich edit. All semantic fields must survive the initial round trip.
const semantic=value=>{
  if(Array.isArray(value))return value.map(semantic);
  if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([key])=>!['position','spread'].includes(key)).map(([key,entry])=>[key,semantic(entry)]));
  return value;
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
export async function createMarkdownEditor({root,value,label,taskLabel,escape,changed,description,readOnly=false}){
  const changePlugin=$prose(()=>new Plugin({filterTransaction:transaction=>!transaction.docChanged||!readOnly&&safeDocument(transaction.doc),view:()=>({update:(view,previous)=>{if(!view.state.doc.eq(previous.doc))changed?.();}})}));
  const editor=Editor.make().config(ctx=>{
    ctx.set(rootCtx,root);ctx.set(defaultValueCtx,value);
    ctx.set(editorViewOptionsCtx,{
      attributes:()=>({role:'textbox','aria-label':label,'aria-multiline':'true','aria-readonly':String(readOnly),...(!readOnly&&description?{'aria-describedby':description}:{}),tabindex:'0',spellcheck:'true'}),
      editable:()=>!readOnly,
      handleKeyDown:(_view,event)=>{if(event.key==='Escape'){escape();return true;}return false;},
      nodeViews:{list_item:(node,view,getPos)=>taskItem(node,view,getPos,taskLabel),image:node=>{const dom=document.createElement('span');dom.textContent=node.attrs.alt||'';return {dom};}},
      // Links are text-editable; clicking never navigates or executes a URI.
      handleClick:(_view,_pos,event)=>Boolean(event.target.closest('a')&&(event.preventDefault(),true)),
      // Prototype accepts pasted text only, so HTML/images cannot fetch remote
      // assets or introduce nodes outside the evaluated Markdown schema.
      handlePaste:(view,event)=>{const text=event.clipboardData?.getData('text/plain');if(text===undefined)return false;event.preventDefault();view.dispatch(view.state.tr.insertText(text));return true;},
      handleDrop:()=>true
    });
  }).use(commonmark).use(gfm).use(history).use(changePlugin);
  try{
    await editor.create();
    const view=editor.action(ctx=>ctx.get(editorViewCtx)),original=view.state.doc;
    const serialized=editor.action(getMarkdown());
    const safe=editor.action(ctx=>{const parser=ctx.get(remarkCtx),tree=parser.parse(value);return !unsupported(tree)&&JSON.stringify(semantic(tree))===JSON.stringify(semantic(parser.parse(serialized)));});
    if(!safe){await editor.destroy();return null;}
    const setReadOnly=value=>{readOnly=value;view.setProps({editable:()=>!readOnly});for(const checkbox of root.querySelectorAll('input[type=checkbox]'))checkbox.disabled=readOnly;};
    const command=(name,payload)=>{
      if(readOnly)return false;
      if(name==='link'){
        if(!safeLink(payload))return false;
        if(!editor.action(callCommand(updateLinkCommand.key,{href:payload}))){
          const {from,empty}=view.state.selection;
          if(empty)view.dispatch(view.state.tr.insertText(payload,from).addMark(from,from+payload.length,view.state.schema.marks.link.create({href:payload})));
          else editor.action(callCommand(toggleLinkCommand.key,{href:payload}));
        }
      }else if(name==='tasks'){
        editor.action(callCommand(wrapInBulletListCommand.key));
        const {$from}=view.state.selection;
        for(let depth=$from.depth;depth>0;depth--){
          const list=$from.node(depth);if(!['bullet_list','ordered_list'].includes(list.type.name))continue;
          const start=$from.before(depth)+1;let transaction=view.state.tr;
          list.descendants((node,position)=>{if(node.type.name==='list_item')transaction=transaction.setNodeMarkup(start+position,undefined,{...node.attrs,checked:node.attrs.checked??false});});
          view.dispatch(transaction);break;
        }
      }else{
        const action=commands[name];if(!action)return false;
        editor.action(callCommand(action.key,name==='heading'?2:undefined));
      }
      view.focus();return true;
    };
    setReadOnly(readOnly);
    return {
      markdown:()=>view.state.doc.eq(original)?value:editor.action(getMarkdown()),
      focus:()=>view.focus(),
      setReadOnly,
      command,
      linkHref:()=>view.state.selection.$from.marks().find(mark=>mark.type.name==='link')?.attrs.href||'',
      destroy:()=>editor.destroy()
    };
  }catch(error){await editor.destroy().catch(()=>{});throw error;}
}

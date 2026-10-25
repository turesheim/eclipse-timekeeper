// This build-time entry is bundled to one local native ES module. The application
// and source-mode fallback do not import Milkdown until rich mode is requested.
import {Editor,rootCtx,defaultValueCtx,editorViewCtx,editorViewOptionsCtx,remarkCtx} from '@milkdown/kit/core';
import {commonmark,toggleStrongCommand,toggleEmphasisCommand,wrapInHeadingCommand,wrapInBulletListCommand,wrapInBlockquoteCommand} from '@milkdown/kit/preset/commonmark';
import {gfm,insertTableCommand} from '@milkdown/kit/preset/gfm';
import {history,undoCommand,redoCommand} from '@milkdown/kit/plugin/history';
import {getMarkdown,callCommand} from '@milkdown/kit/utils';
import '@milkdown/kit/prose/view/style/prosemirror.css';
import '@milkdown/kit/prose/tables/style/tables.css';

const commands={bold:toggleStrongCommand,italic:toggleEmphasisCommand,heading:wrapInHeadingCommand,list:wrapInBulletListCommand,quote:wrapInBlockquoteCommand,table:insertTableCommand,undo:undoCommand,redo:redoCommand};
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
export async function createMarkdownEditor({root,value,label,taskLabel,escape}){
  const editor=Editor.make().config(ctx=>{
    ctx.set(rootCtx,root);ctx.set(defaultValueCtx,value);
    ctx.set(editorViewOptionsCtx,{
      attributes:{role:'textbox','aria-label':label,'aria-multiline':'true',spellcheck:'true'},
      handleKeyDown:(_view,event)=>{if(event.key==='Escape'){escape();return true;}return false;},
      nodeViews:{list_item:(node,view,getPos)=>taskItem(node,view,getPos,taskLabel)},
      // Links are text-editable; clicking never navigates or executes a URI.
      handleClick:(_view,_pos,event)=>Boolean(event.target.closest('a')&&(event.preventDefault(),true)),
      // Prototype accepts pasted text only, so HTML/images cannot fetch remote
      // assets or introduce nodes outside the evaluated Markdown schema.
      handlePaste:(view,event)=>{const text=event.clipboardData?.getData('text/plain');if(text===undefined)return false;event.preventDefault();view.dispatch(view.state.tr.insertText(text));return true;},
      handleDrop:()=>true
    });
  }).use(commonmark).use(gfm).use(history);
  try{
    await editor.create();
    const view=editor.action(ctx=>ctx.get(editorViewCtx)),original=view.state.doc;
    const serialized=editor.action(getMarkdown());
    const safe=editor.action(ctx=>{const parser=ctx.get(remarkCtx);return JSON.stringify(semantic(parser.parse(value)))===JSON.stringify(semantic(parser.parse(serialized)));});
    if(!safe){await editor.destroy();return null;}
    return {
      markdown:()=>view.state.doc.eq(original)?value:editor.action(getMarkdown()),
      focus:()=>view.focus(),
      command:(name)=>{const command=commands[name];if(command)editor.action(callCommand(command.key,name==='heading'?2:undefined));view.focus();},
      destroy:()=>editor.destroy()
    };
  }catch(error){await editor.destroy().catch(()=>{});throw error;}
}

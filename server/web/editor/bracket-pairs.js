import {$prose} from '@milkdown/kit/utils';
import {Plugin,PluginKey,TextSelection} from '@milkdown/kit/prose/state';
import {escapedBracket} from '../src/markdown-brackets.js';

/** Only tracked generated closers are skipped or removed with their opener. */
export function bracketPairs(readOnly,complete){
  const key=new PluginKey('timekeeper-bracket-pairs');
  const literal=view=>{const {$from}=view.state.selection;return readOnly()||view.composing||$from.parent.type.spec.code||(view.state.storedMarks||$from.marks()).some(mark=>['inlineCode','link'].includes(mark.type.name));};
  const plugin=$prose(()=>new Plugin({key,state:{init:()=>[],apply:(transaction,previous)=>{
    const pairs=[];
    for(const pair of previous){
      const open=transaction.mapping.mapResult(pair.open,1),close=transaction.mapping.mapResult(pair.close,1);
      if(!open.deleted&&!close.deleted&&transaction.doc.textBetween(open.pos,open.pos+1)==='['&&transaction.doc.textBetween(close.pos,close.pos+1)===']')pairs.push({open:open.pos,close:close.pos});
    }
    const change=transaction.getMeta(key);if(change?.drop!==undefined){const index=pairs.findIndex(pair=>pair.close===change.drop);if(index>=0)pairs.splice(index,1);}
    if(change?.add)pairs.push(change.add);return pairs.slice(-128);
  }}}));
  const input=(view,from,to,text)=>{
    if(literal(view)||!['[',']'].includes(text))return false;
    const selection=view.state.selection;if(selection.$from.parent!==selection.$to.parent)return false;
    const before=selection.$from.parent.textBetween(0,selection.$from.parentOffset);if(escapedBracket(before,before.length)||text==='['&&before.endsWith('[')&&escapedBracket(before,before.length-1))return false;
    const transaction=view.state.tr;
    if(text==='['){
      transaction.insertText(']',to).insertText('[',from).setMeta(key,{add:{open:from,close:to+1}});
      transaction.setSelection(TextSelection.create(transaction.doc,from+1,to+1));view.dispatch(transaction.scrollIntoView());return true;
    }
    if(from!==to||!(key.getState(view.state)||[]).some(pair=>pair.close===from))return false;
    transaction.setMeta(key,{drop:from});transaction.setSelection(TextSelection.create(transaction.doc,from+1));complete(view,transaction,from+1);view.dispatch(transaction.scrollIntoView());return true;
  };
  const keyDown=(view,event)=>{
    if(event.key!=='Backspace'||event.isComposing||event.ctrlKey||event.metaKey||event.altKey||literal(view)||!view.state.selection.empty)return false;
    const at=view.state.selection.from,pair=(key.getState(view.state)||[]).find(pair=>pair.open===at-1&&pair.close===at);if(!pair)return false;
    view.dispatch(view.state.tr.delete(pair.open,pair.close+1).scrollIntoView());return true;
  };
  return {plugin,input,keyDown};
}

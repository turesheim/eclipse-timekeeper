import {$prose} from '@milkdown/kit/utils';
import {Plugin,PluginKey} from '@milkdown/kit/prose/state';
import {wikiReference} from '../src/wiki-links.js';
import {dailyNoteDate} from '../src/journal-links.js';
import {escapedBracket} from '../src/markdown-brackets.js';

export const wikiLiteralInput='timekeeper:wiki-literal-input';
export const wikiCompositionEnd='timekeeper:wiki-composition-end';
export const wikiInputComplete='timekeeper:wiki-input-complete';

const references=doc=>{
  const values=[];
  doc.descendants((node,pos)=>{
    if(node.type.spec.code)return false;
    if(!node.isText||node.marks.some(mark=>['inlineCode','link'].includes(mark.type.name)))return;
    for(const match of node.text.matchAll(/(!?)\[\[([^\[\]\n]{1,1024})\]\]/g)){
      if(wikiReference(match[2],!!match[1]))values.push({from:pos+match.index,to:pos+match.index+match[0].length,value:match[2],embed:!!match[1],marks:node.marks,escaped:escapedBracket(node.text,match.index)});
    }
  });
  return values;
};

/** Native DOM edits and composition can bypass input rules. Keep authored literals literal. */
export function nativeWikiInput(readOnly,pendingClosers,composing){
  const key=new PluginKey('timekeeper-native-wikilinks');
  const convert=(state,finish=false)=>{
    const protectedValues=key.getState(state)||[],values=references(state.doc).filter(value=>!value.escaped
      &&(finish||!pendingClosers(state,value.from,value.to))&&!protectedValues.some(previous=>previous.from<=value.from&&previous.to>=value.to));
    if(!values.length)return null;
    const transaction=state.tr;
    for(const value of values.reverse()){
      const date=!value.embed&&dailyNoteDate(value.value),node=date?state.schema.nodes.daily_wikilink.create({date}):state.schema.nodes.vault_wikilink.create({value:value.value,embed:value.embed});
      transaction.replaceWith(value.from,value.to,node.mark(value.marks));
    }
    return transaction;
  };
  const plugin=$prose(()=>new Plugin({key,state:{
    init:(_config,state)=>references(state.doc),
    apply:(transaction,previous,before)=>{
      if(transaction.getMeta(wikiLiteralInput)||transaction.getMeta('uiEvent')==='drop'||before.doc.attrs.sourceEpoch!==transaction.doc.attrs.sourceEpoch)return references(transaction.doc);
      return previous.flatMap(value=>{
        const from=transaction.mapping.mapResult(value.from,1),to=transaction.mapping.mapResult(value.to,-1);
        return from.deleted||to.deleted||from.pos>=to.pos?[]:[{from:from.pos,to:to.pos}];
      });
    }
  },appendTransaction:(transactions,_before,state)=>{
    if(readOnly()||composing()||!transactions.some(transaction=>transaction.docChanged||transaction.selectionSet||transaction.getMeta(wikiCompositionEnd)||transaction.getMeta(wikiInputComplete)))return null;
    return convert(state,transactions.some(transaction=>transaction.getMeta(wikiInputComplete)));
  }}));
  // A generated closing pair is already valid Markdown during typing. Project
  // it for saving without replacing the live text or moving the editing caret.
  return {plugin,project:state=>composing()?state.doc:convert(state,true)?.doc||state.doc};
}

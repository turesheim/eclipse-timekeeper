import {$mark,$remark,$command,$inputRule} from '@milkdown/kit/utils';
import {toggleMark} from '@milkdown/kit/prose/commands';
import {InputRule} from '@milkdown/kit/prose/inputrules';
import {markRule} from '@milkdown/kit/prose';
import {remarkInlineStyles} from './remark-inline-styles.js';

const definitions=[['highlight','mark',/(?<!=)==([^=\n]+)==$/],['subscript','sub',/(?<!~)~([^~\s]+)~$/],['superscript','sup',/(?<!\^)\^([^\^\s]+)\^$/]];
const plugins=[$remark('timekeeperInlineStyles',()=>remarkInlineStyles)];
export const inlineStyleCommands={};
for(const [name,tag,pattern]of definitions){
  const schema=$mark(name,()=>({
    excludes:name==='highlight'?'highlight':'subscript superscript',
    parseDOM:[{tag}],toDOM:()=>[tag,0],
    parseMarkdown:{match:node=>node.type===name,runner:(state,node,type)=>state.openMark(type).next(node.children).closeMark(type)},
    toMarkdown:{match:mark=>mark.type.name===name,runner:(state,mark)=>state.withMark(mark,name)}
  }));
  const command=$command('Toggle'+name,ctx=>()=>toggleMark(schema.type(ctx)));
  const input=$inputRule(ctx=>{
    const rule=markRule(pattern,schema.type(ctx));
    return new InputRule(pattern,(state,match,start,end)=>{
      const {$from}=state.selection,before=$from.parent.textBetween(0,start-$from.start());
      if($from.parent.type.spec.code||$from.marks().some(mark=>['inlineCode','link'].includes(mark.type.name))||(before.match(/\\+$/)?.[0].length||0)%2)return null;
      return rule.handler(state,match,start,end);
    });
  });
  inlineStyleCommands[name]=command;plugins.push(schema,command,input);
}
export const inlineStyles=plugins;

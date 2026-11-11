import {$node,$remark,$inputRule} from '@milkdown/kit/utils';
import {InputRule} from '@milkdown/kit/prose/inputrules';
import {emojiFor} from '../src/emoji.js';

// A Markdown token, rather than a text replacement, retains authored shortcodes
// and lets the normal parser exclude escaped colons, code and URL destinations.
export function remarkEmoji(){
  const data=this.data();
  const tokenize=function(effects,ok,nok){let name='';return start;
    function start(code){effects.enter('emojiShortcode');effects.consume(code);return body;}
    function body(code){
      if(code===58){if(!emojiFor(name))return nok(code);effects.consume(code);effects.exit('emojiShortcode');return ok;}
      if(code===null||code<0||name.length>=80||!/[a-z0-9_+-]/.test(String.fromCodePoint(code)))return nok(code);
      name+=String.fromCodePoint(code);effects.consume(code);return body;
    }
  };
  (data.micromarkExtensions??=[]).push({text:{58:{name:'emojiShortcode',tokenize}}});
  (data.fromMarkdownExtensions??=[]).push({enter:{emojiShortcode(token){
    if(this.stack.some(node=>['link','linkReference'].includes(node.type)))this.config.enter.data.call(this,token);
    else this.enter({type:'emojiShortcode',value:''},token);
  }},exit:{emojiShortcode(token){
    if(this.stack.at(-1).type==='text')this.config.exit.data.call(this,token);
    else{this.stack.at(-1).value=this.sliceSerialize(token).slice(1,-1);this.exit(token);}
  }}});
  (data.toMarkdownExtensions??=[]).push({handlers:{emojiShortcode:node=>':'+node.value+':'},unsafe:[{character:':',inConstruct:'phrasing'}]});
}
const schema=$node('emoji_shortcode',()=>({inline:true,group:'inline',atom:true,
  attrs:{name:{default:'',validate:'string'}},leafText:node=>emojiFor(node.attrs.name)||':'+node.attrs.name+':',
  toDOM:node=>['span',{'data-emoji':node.attrs.name,role:'img','aria-label':':'+node.attrs.name+':'},emojiFor(node.attrs.name)||':'+node.attrs.name+':'],
  parseMarkdown:{match:node=>node.type==='emojiShortcode',runner:(state,node,type)=>state.addNode(type,{name:node.value})},
  toMarkdown:{match:node=>node.type.name==='emoji_shortcode',runner:(state,node)=>state.addNode('emojiShortcode',undefined,node.attrs.name)}
}));
const input=$inputRule(()=>new InputRule(/:([a-z0-9_+-]{1,80}):$/,(state,match,start,end)=>{
  if(!emojiFor(match[1])||state.selection.$from.parent.type.spec.code||state.selection.$from.marks().some(mark=>['inlineCode','link'].includes(mark.type.name)))return null;
  const before=state.selection.$from.parent.textBetween(0,start-state.selection.$from.start());if((before.match(/\\+$/)?.[0].length||0)%2)return null;
  return state.tr.replaceWith(start,end,state.schema.nodes.emoji_shortcode.create({name:match[1]}).mark(state.storedMarks||state.selection.$from.marks()));
}));
export const emojiPlugin=[schema,$remark('timekeeperEmoji',()=>remarkEmoji),input];

import {dailyNoteDate} from '../src/journal-links.js';
import {wikiReference} from '../src/wiki-links.js';

function tokenizer(embed){return function(effects,ok,nok){
  let value='',brackets=0;return start;
  function start(code){effects.enter('vaultWikilink');effects.consume(code);return embed?first:second;}
  function first(code){if(code!==91)return nok(code);effects.consume(code);return second;}
  function second(code){if(code!==91)return nok(code);effects.consume(code);return body;}
  function body(code){
    if(code===93){effects.consume(code);brackets++;return end;}
    if(code===null||code<0||code===91||value.length>=1024)return nok(code);
    value+=String.fromCharCode(code);effects.consume(code);return body;
  }
  function end(code){if(code!==93||!brackets||!wikiReference(value,embed))return nok(code);effects.consume(code);effects.exit('vaultWikilink');return ok;}
};}
export function remarkDailyWikilinks(){
  const data=this.data();
  (data.micromarkExtensions??=[]).push({text:{91:{name:'vaultWikilink',tokenize:tokenizer(false)},33:{name:'vaultEmbed',tokenize:tokenizer(true)}}});
  (data.fromMarkdownExtensions??=[]).push({enter:{vaultWikilink(token){
    if(this.stack.some(node=>['link','linkReference','image','imageReference'].includes(node.type)))this.config.enter.data.call(this,token);
    else this.enter({type:'vaultWikilink',value:''},token);
  }},exit:{vaultWikilink(token){
    if(this.stack.at(-1).type==='text')this.config.exit.data.call(this,token);
    else{const raw=this.sliceSerialize(token),embed=raw.startsWith('!'),value=raw.slice(embed?3:2,-2),node=this.stack.at(-1);node.type=!embed&&dailyNoteDate(value)?'dailyWikilink':embed?'vaultEmbed':'vaultWikilink';node.value=value;this.exit(token);}
  }}});
  (data.toMarkdownExtensions??=[]).push({handlers:{dailyWikilink:node=>'[['+node.value+']]',vaultWikilink:node=>'[['+node.value+']]',vaultEmbed:node=>'![['+node.value+']]'}});
}

import {dailyNoteDate} from '../src/journal-links.js';

// A syntax extension, rather than a text replacement: escaped brackets and code
// are handled by CommonMark itself and never become active journal links.
function tokenize(effects,ok,nok){
  let date='';
  return start;
  function start(code){effects.enter('dailyWikilink');effects.consume(code);return second;}
  function second(code){if(code!==91)return nok(code);effects.consume(code);return value;}
  function value(code){
    if(date.length===10){if(code!==93||!dailyNoteDate(date))return nok(code);effects.consume(code);return end;}
    if(date.length===4||date.length===7){if(code!==45)return nok(code);}
    else if(code===null||code<48||code>57)return nok(code);
    date+=String.fromCharCode(code);effects.consume(code);return value;
  }
  function end(code){if(code!==93)return nok(code);effects.consume(code);effects.exit('dailyWikilink');return ok;}
}
export function remarkDailyWikilinks(){
  const data=this.data();
  (data.micromarkExtensions??=[]).push({text:{91:{name:'dailyWikilink',tokenize}}});
  (data.fromMarkdownExtensions??=[]).push({
    enter:{dailyWikilink(token){this.enter({type:'dailyWikilink',value:''},token);}},
    exit:{dailyWikilink(token){this.stack.at(-1).value=this.sliceSerialize(token).slice(2,-2);this.exit(token);}}
  });
  (data.toMarkdownExtensions??=[]).push({handlers:{dailyWikilink:node=>'[['+node.value+']]'}});
  // An existing Markdown link owns its label. Do not create nested HTML anchors.
  return tree=>{
    function visit(node,insideLink=false){
      if(!node.children)return;
      const nested=insideLink||node.type==='link'||node.type==='linkReference';
      node.children=node.children.map(child=>nested&&child.type==='dailyWikilink'?{type:'text',value:'[['+child.value+']]'}:child);
      node.children=node.children.reduce((children,child)=>{
        if(child.type==='text'&&children.at(-1)?.type==='text')children.at(-1).value+=child.value;
        else children.push(child);return children;
      },[]);
      node.children.forEach(child=>visit(child,nested));
    }
    visit(tree);
  };
}

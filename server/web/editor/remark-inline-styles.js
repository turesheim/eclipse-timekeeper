const styles=[['highlight',61,2],['subscript',126,1],['superscript',94,1]];

/** Inline tokens keep escapes, code and URL destinations under Markdown's parser. */
function construct(name,marker,size){
  return {name,tokenize:function(effects,ok,nok){
    let opening=0,length=0,escaped=false,codeTicks=0,ticks=0,previous;
    return start;
    function start(code){effects.enter(name);effects.enter(name+'Sequence');return open(code);}
    function open(code){
      if(opening<size){if(code!==marker)return nok(code);opening++;effects.consume(code);return open;}
      if(code===marker||code===null||code<0||/\s/.test(String.fromCodePoint(code)))return nok(code);
      effects.exit(name+'Sequence');effects.enter('chunkText',{contentType:'text'});return body(code);
    }
    function body(code){
      if(code===null||code<0||code===10||code===13||length++>65536)return nok(code);
      if(ticks){if(code===96){ticks++;effects.consume(code);return body;}if(!codeTicks)codeTicks=ticks;else if(ticks===codeTicks)codeTicks=0;ticks=0;}
      if(!escaped&&code===96){ticks=1;effects.consume(code);return body;}
      if(!codeTicks&&!escaped&&code===marker){
        if(previous===undefined||/\s/.test(String.fromCodePoint(previous)))return consume(code);
        return effects.attempt({tokenize:close},done,consume)(code);
      }
      if(size===1&&!codeTicks&&!escaped&&/\s/.test(String.fromCodePoint(code)))return nok(code);
      return consume(code);
    }
    function consume(code){escaped=!escaped&&code===92;previous=code;effects.consume(code);return body;}
    function close(inner,closed,rejected){
      let count=0;return step;
      function step(code){
        if(!count)inner.exit('chunkText');
        if(count<size){if(code!==marker)return rejected(code);if(!count)inner.enter(name+'Sequence');count++;inner.consume(code);return step;}
        if(code===marker)return rejected(code);inner.exit(name+'Sequence');inner.exit(name);return closed(code);
      }
    }
    function done(code){return ok(code);}
  }};
}

export function remarkInlineStyles(){
  const data=this.data(),text={},enter={},exit={},handlers={};
  for(const [name,marker,size]of styles){
    text[marker]=construct(name,marker,size);
    enter[name]=function(token){this.enter({type:name,children:[]},token);};
    exit[name]=function(token){this.exit(token);};
    handlers[name]=(node,_parent,state,info)=>{
      const delimiter=String.fromCodePoint(marker).repeat(size),leave=state.enter(name);
      const value=delimiter+state.containerPhrasing(node,{...info,before:delimiter,after:delimiter})+delimiter;leave();return value;
    };
  }
  (data.micromarkExtensions??=[]).push({text});
  (data.fromMarkdownExtensions??=[]).push({enter,exit});
  (data.toMarkdownExtensions??=[]).push({handlers,unsafe:[{character:'=',after:'=',inConstruct:'phrasing'},{character:'~',inConstruct:'phrasing'},{character:'^',inConstruct:'phrasing'}]});
}

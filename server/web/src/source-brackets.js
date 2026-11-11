import {literalBracketContext,wikiCompletion} from './markdown-brackets.js';

/** Native textarea editing, retaining the browser's undo history when supported. */
export function sourceBrackets(field){
  const text=field.text;let pairs=[],before=null,editing=false,composing=false;
  const update=()=>queueMicrotask(()=>field.updateExperience?.());
  const allowed=()=>!field.editor&&!field.readOnly&&!field.loading&&!field.plain.hidden&&!composing;
  const replace=(value,from,to,anchor=from+value.length,head=anchor)=>{
    if(!allowed())return false;text.focus();text.setSelectionRange(from,to);before={from,to,length:text.value.length};editing=true;
    try{let inserted=false;try{inserted=typeof document.execCommand==='function'&&document.execCommand('insertText',false,value);}catch{}
      if(!inserted){text.setRangeText(value,from,to,'end');text.dispatchEvent(new Event('input',{bubbles:true}));}}
    finally{editing=false;}text.setSelectionRange(anchor,head);update();return true;
  };
  text.addEventListener('beforeinput',event=>{
    if(editing)return;before={from:text.selectionStart,to:text.selectionEnd,length:text.value.length};
    if(!allowed()||event.isComposing||!event.cancelable||event.inputType!=='insertText'||!['[',']'].includes(event.data)||literalBracketContext(text.value,text.selectionStart))return;
    const from=text.selectionStart,to=text.selectionEnd;
    if(event.data==='['){event.preventDefault();const selected=text.value.slice(from,to);replace('['+selected+']',from,to,from+1,to+1);pairs.push({open:from,close:to+1});pairs=pairs.slice(-128);}
    else if(from===to&&text.value[from]===']'&&pairs.some(pair=>pair.close===from)){event.preventDefault();pairs=pairs.filter(pair=>pair.close!==from);text.setSelectionRange(from+1,from+1);before=null;update();}
  });
  text.addEventListener('input',event=>{
    if(before&&!event.inputType?.startsWith('history')){
      let {from,to,length}=before;const delta=text.value.length-length;
      if(from===to&&delta<0){if(event.inputType?.endsWith('Backward'))from+=delta;else to-=delta;}
      const map=position=>position<from?position:position>=to?position+delta:null;
      pairs=pairs.map(pair=>({open:map(pair.open),close:map(pair.close)})).filter(pair=>pair.open!==null&&pair.close!==null&&text.value[pair.open]==='['&&text.value[pair.close]===']');
    }else pairs=[];
    before=null;update();
  });
  text.addEventListener('keydown',event=>{
    if(!allowed()||event.isComposing||event.key!=='Backspace'||event.ctrlKey||event.metaKey||event.altKey||text.selectionStart!==text.selectionEnd)return;
    const at=text.selectionStart,pair=pairs.find(pair=>pair.open===at-1&&pair.close===at);if(!pair)return;
    event.preventDefault();replace('',pair.open,pair.close+1,pair.open);
  });
  text.addEventListener('compositionstart',()=>{composing=true;update();});text.addEventListener('compositionend',()=>{composing=false;update();});
  for(const type of ['select','keyup','click','focus','blur','scroll'])text.addEventListener(type,update);
  return {
    reset:()=>{pairs=[];before=null;composing=false;},
    query:()=>{if(!allowed()||text.selectionStart!==text.selectionEnd)return null;const range=wikiCompletion(text.value,text.selectionStart);return range&&!literalBracketContext(text.value,range.from)?range:null;},
    insert:(range,path)=>replace((range.embed?'!':'')+'[['+path+']]',range.from,range.to)
  };
}

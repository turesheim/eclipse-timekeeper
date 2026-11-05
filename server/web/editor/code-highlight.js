import hljs from 'highlight.js';

// Highlight decorations never replace ProseMirror's editable DOM or source.
// Keep unusually large fences responsive; their complete text remains editable.
const maximum=200_000;
const entities={amp:'&',lt:'<',gt:'>',quot:'"','#x27':"'"};
const decode=value=>value.replace(/&(amp|lt|gt|quot|#x27);/g,(_,name)=>entities[name]);
export function highlightCode(text,name){
  const language=String(name||'').trim().split(/\s+/)[0].toLowerCase();
  if(!language||!hljs.getLanguage(language)||text.length>maximum)return [];
  try{
    const html=hljs.highlight(text,{language,ignoreIllegals:true}).value,spans=[],stack=[];
    let offset=0,last=0;
    const append=end=>{const length=decode(html.slice(last,end)).length,classes=[...new Set(stack.flat())];if(length&&classes.length)spans.push({from:offset,to:offset+length,className:classes.join(' ')});offset+=length;};
    // Only consume highlight.js's generated span tags. User HTML is escaped by
    // the highlighter, and never parsed or inserted as markup. This also keeps
    // CRLF and UTF-16 offsets intact for selections and backspace in code blocks.
    for(const match of html.matchAll(/<(\/?)(?:span)(?: class="([\w -]+)")?>/g)){
      append(match.index);if(match[1])stack.pop();else stack.push((match[2]||'').split(/\s+/).filter(value=>/^(?:hljs-[\w-]+|[\w-]+_)$/.test(value)));last=match.index+match[0].length;
    }
    append(html.length);return offset===text.length?spans:[];
  }catch{return [];}
}

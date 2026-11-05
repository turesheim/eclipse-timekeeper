/** Syntax is a literal, aria-hidden mirror; the native textarea owns editing. */
export function cssEditor(editor){
  const root=document.createElement('div'),mirror=document.createElement('pre');
  root.className='css-editor';mirror.className='css-editor-highlight';mirror.setAttribute('aria-hidden','true');mirror.hidden=true;
  editor.dir='ltr';root.append(mirror,editor);
  let highlight,disposed=false,composing=false;
  const sync=()=>{mirror.scrollTop=editor.scrollTop;mirror.scrollLeft=editor.scrollLeft;};
  const layout=()=>{mirror.style.width=editor.clientWidth+'px';mirror.style.height=editor.clientHeight+'px';mirror.style.padding=getComputedStyle(editor).padding;mirror.style.insetInlineStart=editor.clientLeft+'px';mirror.style.insetBlockStart=editor.clientTop+'px';sync();};
  const plain=()=>{root.classList.remove('is-highlighted');mirror.hidden=true;mirror.replaceChildren();};
  const refresh=()=>{
    if(disposed)return;if(!highlight||composing||editor.value.length>200000){plain();return;}
    const text=editor.value,fragment=document.createDocumentFragment();let offset=0;
    for(const range of highlight(text,'css')){
      fragment.append(document.createTextNode(text.slice(offset,range.from)));
      const token=document.createElement('span');token.className=range.className;token.textContent=text.slice(range.from,range.to);fragment.append(token);offset=range.to;
    }
    fragment.append(document.createTextNode(text.slice(offset)+(text.endsWith('\n')?' ':'')));
    mirror.replaceChildren(fragment);mirror.hidden=false;root.classList.add('is-highlighted');layout();
  };
  const start=()=>{composing=true;plain();},end=()=>{composing=false;refresh();};
  editor.addEventListener('input',refresh);editor.addEventListener('scroll',sync);
  editor.addEventListener('compositionstart',start);editor.addEventListener('compositionend',end);
  const resize=new ResizeObserver(layout);resize.observe(editor);
  void import('./assets/milkdown/code-highlight.js').then(module=>{if(!disposed){highlight=module.highlightCode;refresh();}}).catch(()=>{if(!disposed)plain();});
  return {root,refresh,destroy(){disposed=true;resize.disconnect();editor.removeEventListener('input',refresh);editor.removeEventListener('scroll',sync);editor.removeEventListener('compositionstart',start);editor.removeEventListener('compositionend',end);plain();editor.value='';}};
}

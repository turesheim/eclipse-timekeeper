const keyboardHeightThreshold=120;
const caretGap=12;

function scrollDelta(rect,top,bottom){
  if(rect.bottom>bottom-caretGap)return rect.bottom-bottom+caretGap;
  if(rect.top<top+caretGap)return rect.top-top-caretGap;
  return 0;
}

/** Fit phone journal editing to the visible viewport above the software keyboard. */
export class TodayJournalViewport {
  constructor(root,phone,markdown,isVisible){
    Object.assign(this,{root,phone,markdown,isVisible,viewport:window.visualViewport});
    this.navigation=document.getElementById('workspace-navigation');
    const schedule=()=>this.schedule();
    const onViewportScroll=()=>{
      if(this.offsetTop!==this.viewport.offsetTop)schedule();
    };
    this.viewport?.addEventListener('resize',schedule);
    this.viewport?.addEventListener('scroll',onViewportScroll);
    window.addEventListener('resize',schedule);
    phone.addEventListener('change',schedule);
    for(const event of ['focusin','focusout','input','keyup'])markdown.root.addEventListener(event,schedule);
    document.addEventListener('selectionchange',()=>{if(this.open)schedule();});
  }
  get open(){return this.root.hasAttribute('data-journal-keyboard');}
  get field(){
    const active=document.activeElement;
    if(this.markdown.root.contains(active)&&active?.matches('.ProseMirror[contenteditable="true"], textarea:not([readonly])'))return active;
    return null;
  }
  schedule(){
    if(this.isVisible()&&!this.frame)this.frame=requestAnimationFrame(()=>{
      this.frame=null;
      this.update();
    });
  }
  reset(){
    cancelAnimationFrame(this.frame);this.frame=null;
    this.root.removeAttribute('data-journal-keyboard');
    this.root.style.removeProperty('--today-keyboard-height');
    this.root.style.removeProperty('--today-keyboard-inset');
  }
  update(){
    const viewport=this.viewport,wasOpen=this.open;
    if(!this.isVisible()||!this.phone.matches||!viewport){if(wasOpen)this.reset();return;}
    const field=this.field;
    this.offsetTop=viewport.offsetTop;
    // Normalise for zoom so magnifying the page does not count as a keyboard.
    const open=innerHeight-viewport.height*viewport.scale>=keyboardHeightThreshold&&(field!==null||wasOpen);
    if(open){
      if(!wasOpen)this.root.setAttribute('data-journal-keyboard','');
      this.root.style.setProperty('--today-keyboard-height',viewport.height+'px');
      this.root.style.setProperty('--today-keyboard-inset',Math.max(0,innerHeight-viewport.height)+'px');
    }else if(wasOpen)this.reset();
    if(field&&(open||wasOpen))this.revealCaret(field,open);
  }
  caretRect(field){
    if(field instanceof HTMLTextAreaElement){
      if(field.selectionStart!==field.selectionEnd)return null;
      const rect=this.markdown.caret.sourceRect(field);
      return rect&&{top:rect.top,bottom:rect.top+rect.height};
    }
    const selection=document.getSelection();
    if(!selection?.rangeCount||!selection.isCollapsed||!field.contains(selection.focusNode))return null;
    const rect=selection.getRangeAt(0).getBoundingClientRect();
    return rect.height?rect:this.markdown.editor?.selectionRect();
  }
  revealCaret(field,keyboardOpen){
    let rect=this.caretRect(field);
    if(!rect)return;
    const containers=[field,field.parentElement.closest('.ProseMirror')].filter(Boolean);
    for(const container of containers){
      const bounds=container.getBoundingClientRect();
      const delta=scrollDelta(rect,bounds.top,bounds.bottom);
      if(delta){container.scrollTop+=delta;rect=this.caretRect(field)||rect;}
    }
    const top=this.viewport.offsetTop;
    const bottom=keyboardOpen?top+this.viewport.height:Math.min(top+this.viewport.height,this.navigation.getBoundingClientRect().top);
    const delta=scrollDelta(rect,top,bottom);
    if(delta)window.scrollBy({top:delta,behavior:'instant'});
  }
}

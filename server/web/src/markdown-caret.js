import {cursorPreferenceChanged} from './editor-preferences.js';

const shapes={default:'auto',line:'bar',underscore:'underscore',solid:'block'};
const fontProperties=['fontFamily','fontSize','fontStyle','fontWeight','fontStretch','fontVariant','fontKerning','fontFeatureSettings','fontVariationSettings','letterSpacing','lineHeight','wordSpacing','textTransform','whiteSpace','overflowWrap','wordBreak','tabSize','textIndent','direction','textAlign'];

/** A paint-only fallback. It never changes editor content, selection or key handling. */
export class MarkdownCaret {
  constructor(field){
    this.field=field;this.overlay=document.createElement('span');this.overlay.className='markdown-caret';this.overlay.setAttribute('aria-hidden','true');this.overlay.hidden=true;field.root.append(this.overlay);
    const update=()=>this.update();
    for(const name of ['selectionchange','input','keyup','pointerup','focusin','focusout','scroll','visibilitychange'])document.addEventListener(name,update,true);
    window.addEventListener('resize',update);window.visualViewport?.addEventListener('resize',update);window.visualViewport?.addEventListener('scroll',update);
    for(const name of [cursorPreferenceChanged,'timekeeper-typography-change'])window.addEventListener(name,update);
    field.root.addEventListener('compositionstart',()=>{this.composing=true;this.hide();},true);field.root.addEventListener('compositionend',()=>{this.composing=false;this.update();},true);
    this.resize=new ResizeObserver(update);for(const value of [field.root,field.text,field.rich])this.resize.observe(value);
  }
  update(){if(!this.frame)this.frame=requestAnimationFrame(()=>{this.frame=null;this.paint();});}
  hide(){this.target?.removeAttribute('data-markdown-caret-fallback');this.target=null;this.overlay.hidden=true;this.position=null;}
  clear(){this.hide();this.composing=false;if(this.mirror)this.mirror.replaceChildren();this.value=null;this.styleKey=null;}
  glyph(text,position,style){
    this.canvas??=document.createElement('canvas');const context=this.canvas.getContext('2d');context.font=style.font||`${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
    const character=position<text.length?String.fromCodePoint(text.codePointAt(position)):' ';return Math.max(4,context.measureText(/[\r\n]/.test(character)?' ':character).width);
  }
  sourceRect(source){
    const style=getComputedStyle(source);
    if(!this.mirror){this.mirror=document.createElement('div');this.mirror.className='markdown-caret-mirror';this.mirror.inert=true;this.mirror.setAttribute('aria-hidden','true');this.field.root.append(this.mirror);}
    const key=[source.clientWidth,...fontProperties.map(name=>style[name]),style.padding].join('|');
    if(key!==this.styleKey){for(const name of fontProperties)this.mirror.style[name]=style[name];this.mirror.style.padding=style.padding;this.mirror.style.width=source.clientWidth+'px';this.styleKey=key;}
    if(this.value!==source.value||!this.mirror.firstChild){this.mirror.textContent=source.value+'\u200b';this.value=source.value;}
    const range=document.createRange(),position=source.selectionStart;range.setStart(this.mirror.firstChild,position);range.collapse(true);const caret=range.getBoundingClientRect(),box=source.getBoundingClientRect();
    if(!caret.height)return null;let width=this.glyph(source.value,position,style),left=caret.left;
    if(position<source.value.length&&!/[\r\n]/.test(source.value[position])){range.setEnd(this.mirror.firstChild,position+String.fromCodePoint(source.value.codePointAt(position)).length);const next=range.getBoundingClientRect();if(next.width>0&&Math.abs(next.top-caret.top)<2){width=next.width;left=next.left;}}
    return {left:box.left+source.clientLeft+left-source.scrollLeft,top:box.top+source.clientTop+caret.top-source.scrollTop,height:caret.height,width,color:style.color,box};
  }
  richRect(){
    const f=this.field,selection=document.getSelection();if(!selection?.isCollapsed||!f.rich.contains(selection.anchorNode))return null;
    const rect=f.editor?.selectionRect();if(!rect||rect.bottom<=rect.top)return null;
    const parent=selection.anchorNode.nodeType===Node.TEXT_NODE?selection.anchorNode.parentElement:selection.anchorNode,style=getComputedStyle(parent),text=selection.anchorNode.textContent||'',position=selection.anchorOffset;
    let left=rect.left,width=this.glyph(text,position,style);
    if(selection.anchorNode.nodeType===Node.TEXT_NODE&&position<text.length){const range=document.createRange();range.setStart(selection.anchorNode,position);range.setEnd(selection.anchorNode,position+String.fromCodePoint(text.codePointAt(position)).length);const next=range.getBoundingClientRect();if(next.width>0&&Math.abs(next.top-rect.top)<2){width=next.width;left=next.left;}}
    return {left,top:rect.top,height:rect.bottom-rect.top,width,color:style.color,box:f.rich.getBoundingClientRect()};
  }
  paint(){
    const f=this.field,shape=document.documentElement.dataset.editorCursor||'default',active=document.activeElement;
    if(f.readOnly||f.loading||this.composing||f.editor?.composing()||document.hidden||!f.root.isConnected||!['underscore','solid'].includes(shape)||CSS.supports('caret-shape',shapes[shape])){this.hide();return;}
    const source=active instanceof HTMLTextAreaElement&&f.root.contains(active)&&(active===f.text||active.closest('.markdown-source-fragment'));
    if(source&&(active.readOnly||active.selectionStart!==active.selectionEnd)||!source&&(!f.editor||!f.rich.contains(active)||active.matches('input,textarea,button,select'))){this.hide();return;}
    let rect;try{rect=source?this.sourceRect(active):this.richRect();}catch{this.hide();return;}
    if(!rect){this.hide();return;}const root=f.root.getBoundingClientRect();
    if(rect.left<Math.max(0,root.left,rect.box.left)-1||rect.left+rect.width>Math.min(innerWidth,root.right,rect.box.right)+1||rect.top<Math.max(0,root.top,rect.box.top)-1||rect.top+rect.height>Math.min(innerHeight,root.bottom,rect.box.bottom)+1){this.hide();return;}
    const target=source?active:active.closest('.ProseMirror');if(!target){this.hide();return;}
    if(this.target!==target){this.target?.removeAttribute('data-markdown-caret-fallback');this.target=target;}target.setAttribute('data-markdown-caret-fallback','');
    const height=shape==='underscore'?2:rect.height,top=shape==='underscore'?rect.top+rect.height-height:rect.top,key=[shape,rect.left,top,rect.width,height].join('|');
    Object.assign(this.overlay.style,{left:rect.left+'px',top:top+'px',width:rect.width+'px',height:height+'px',color:rect.color});this.overlay.dataset.shape=shape;this.overlay.hidden=false;
    if(key!==this.position){for(const animation of this.overlay.getAnimations())animation.currentTime=0;this.position=key;}
  }
}

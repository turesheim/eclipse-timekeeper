/** Resize a sidebar without replacing its content or the adjacent editor. */
export class SidebarResize {
  constructor(root,panel,{side='left',variable,label,width=224,minimum=180,maximum=480,breakpoint=1050,changed=()=>{},focusFallback=()=>null,className=''}={}){
    const separator=document.createElement('div');this.element=separator;this.panel=panel;
    separator.className='sidebar-resize '+className;separator.tabIndex=0;
    for(const [name,value]of Object.entries({role:'separator','aria-orientation':'vertical','aria-label':label,'aria-controls':panel.id,'aria-valuemin':minimum,'aria-valuemax':maximum}))separator.setAttribute(name,String(value));
    const apply=value=>{this.width=Math.max(minimum,Math.min(maximum,value));root.style.setProperty(variable,this.width+'px');separator.setAttribute('aria-valuenow',String(Math.round(this.width)));changed();};
    const direction=side==='right'?-1:1;let drag=null,focused=false;
    separator.addEventListener('pointerdown',event=>{if(event.button!==0)return;event.preventDefault();separator.focus({preventScroll:true});drag={x:event.clientX,width:panel.getBoundingClientRect().width};separator.setPointerCapture(event.pointerId);});
    separator.addEventListener('pointermove',event=>{if(drag&&separator.hasPointerCapture(event.pointerId))apply(drag.width+(event.clientX-drag.x)*direction);});
    for(const type of ['pointerup','pointercancel'])separator.addEventListener(type,event=>{if(separator.hasPointerCapture(event.pointerId))separator.releasePointerCapture(event.pointerId);drag=null;});
    separator.addEventListener('keydown',event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();apply(event.key==='Home'?minimum:event.key==='End'?maximum:this.width+(event.key==='ArrowRight'?20:-20)*direction);}});
    document.addEventListener('focusin',event=>{focused=event.target===separator;});
    matchMedia(`(max-width:${breakpoint}px)`).addEventListener('change',event=>{if(event.matches&&root.offsetParent&&(document.activeElement===separator||focused&&document.activeElement===document.body))focusFallback()?.focus({preventScroll:true});});
    apply(width);
  }
}

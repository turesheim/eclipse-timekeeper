import Tooltip from './assets/bootstrap/tooltip.js';

// Content factories construct application-owned DOM. All fields remain literal
// except calendar descriptions, sanitized by tooltip-description.js.
const content=new WeakMap();
export const TOOLTIP_SHOW_DELAY=800;
export const TOOLTIP_FOCUS_DELAY=5000;
let showTimer,pending=null;
function cancelPending(){clearTimeout(showTimer);pending=null;}
function schedule(node,focus=null){
  if(!node){cancelPending();return;}
  if(active?.node===node){show(node,focus);return;}
  if(pending===node)return;cancelPending();pending=node;
  showTimer=setTimeout(()=>{pending=null;if(visible(node))show(node,focus);},TOOLTIP_SHOW_DELAY);
}
let active=null,installed=false,pointerFocus=false,leaveTimer,focusTimer,dismissed=null,pointer=null;
function cancelFocus(){clearTimeout(focusTimer);focusTimer=null;}
const visible=node=>!!node?.isConnected&&!node.closest('[hidden]')&&node.getClientRects().length>0;
export function hideTooltip(cancel=true) {
  if(cancel)cancelPending();cancelFocus();clearTimeout(leaveTimer);if(!active)return;
  const previous=active;active=null;previous.instance.dispose();
  for(const [node,value] of previous.descriptions)value===null?node.removeAttribute('aria-describedby'):node.setAttribute('aria-describedby',value);
}
function refreshTooltip() {
  if(!active)return;
  if(!visible(active.node)){hideTooltip();return;}
  // Keep a focused link/selection and scroll position stable through live clocks
  // and refreshes. Reopening gets the newest content from the factory.
  if(active.interactive){active.instance.update();return;}
  active.tip.querySelector('.tooltip-inner').replaceChildren(content.get(active.node)());active.instance.update();
}
function focusTooltip(){
  cancelFocus();if(!active||!visible(active.node))return;
  const {tip,node}=active;
  if(!active.interactive){
    active.previousFocus=document.activeElement;active.interactive=true;
    tip.classList.add('is-interactive');tip.setAttribute('role','dialog');
    tip.querySelector('.tooltip-inner').tabIndex=0;
    tip.setAttribute('aria-label',tip.querySelector('.tooltip-heading,.tooltip-caption')?.textContent||node.getAttribute('aria-label')||node.textContent);
  }
  tip.querySelector('.tooltip-inner').focus({preventScroll:true});
}
function dwell(){
  clearTimeout(leaveTimer);cancelPending();
  if(active?.interactive||focusTimer)return;
  const current=active;
  focusTimer=setTimeout(()=>{focusTimer=null;if(active===current&&pointer?.pointerType==='mouse'&&!pointer.buttons&&overTooltip(pointer))focusTooltip();},TOOLTIP_FOCUS_DELAY);
}
export function richTooltip(node,render,{composite=true}={}) {
  node.removeAttribute('title');node.dataset.tkTooltip='';content.set(node,render);
  if(composite)node.dataset.tkTooltipComposite='';else delete node.dataset.tkTooltipComposite;
  if(active?.node===node)refreshTooltip();return node;
}
function plainTooltip(node) {
  // ProseMirror owns authored link/image titles. Changing those DOM attributes
  // can change the saved Markdown, so only application UI hints are enhanced.
  if(node.closest('.ProseMirror, [contenteditable="true"]'))return;
  const title=node.getAttribute('title');if(title===null)return;
  // A composite card owns its whole surface, including icons and text.
  if(node.parentElement?.closest('[data-tk-tooltip-composite]')){node.removeAttribute('title');return;}
  node.removeAttribute('title');
  if(!title){delete node.dataset.tkTooltip;content.delete(node);if(active?.node===node)hideTooltip();return;}
  richTooltip(node,()=>{
    const value=document.createElement('div');value.className='tooltip-caption';
    const glyph=node.querySelector('.icon')?.cloneNode(true);if(glyph){glyph.removeAttribute('title');value.append(glyph);}
    const text=document.createElement('span');text.textContent=title;value.append(text);return value;
  },{composite:false});
}
function target(node,point=null) {
  if(!(node instanceof Element))return null;
  const titled=node.closest('[title]');if(titled)plainTooltip(titled);
  // Bootstrap disables pointer events on disabled controls. Resolve their
  // bounds from the parent event without adding layout-changing wrappers.
  const disabled=point&&[...node.querySelectorAll(':disabled[data-tk-tooltip]')].find(value=>{const box=value.getBoundingClientRect();return point.clientX>=box.left&&point.clientX<=box.right&&point.clientY>=box.top&&point.clientY<=box.bottom;});
  const candidate=disabled||node.closest('[data-tk-tooltip]');
  return candidate&&content.has(candidate)&&visible(candidate)?candidate:null;
}
function show(node,focus=null) {
  if(!node||node===dismissed)return;clearTimeout(leaveTimer);
  if(active?.node===node){
    if(focus&&!active.descriptions.has(focus)){const description=focus.getAttribute('aria-describedby');active.descriptions.set(focus,description);focus.setAttribute('aria-describedby',[description,active.tip.id].filter(Boolean).join(' '));}
    return;
  }
  hideTooltip();
  const container=node.closest('dialog[open]')||document.body;
  const descriptions=new Map([[node,node.getAttribute('aria-describedby')]]);
  if(focus&&focus!==node)descriptions.set(focus,focus.getAttribute('aria-describedby'));
  const instance=new Tooltip(node,{trigger:'manual',html:true,sanitize:true,animation:false,placement:'auto',container,boundary:container,
    customClass:'timekeeper-tooltip',popperConfig:{strategy:'fixed'},title:()=>content.get(node)()});
  instance.show();const tip=document.getElementById(node.getAttribute('aria-describedby'));
  if(!tip){instance.dispose();return;}
  active={node,instance,tip,descriptions};
  for(const [item,description] of descriptions)item.setAttribute('aria-describedby',[description,tip.id].filter(Boolean).join(' '));
}
function inside(node){return node instanceof Node&&active&&(active.node.contains(node)||active.tip.contains(node));}
function overTooltip(event){if(!active)return false;const box=active.tip.getBoundingClientRect();return event.clientX>=box.left&&event.clientX<=box.right&&event.clientY>=box.top&&event.clientY<=box.bottom;}
function leave(){cancelFocus();clearTimeout(leaveTimer);if(active?.tip.contains(document.activeElement))return;leaveTimer=setTimeout(()=>hideTooltip(false),100);}
export function installTooltips() {
  if(installed)return;installed=true;
  const scan=root=>{if(!(root instanceof Element))return;if(root.hasAttribute('title'))plainTooltip(root);root.querySelectorAll('[title]').forEach(plainTooltip);};
  scan(document.body);
  new MutationObserver(changes=>{
    for(const change of changes)if(change.type==='childList')change.addedNodes.forEach(scan);else if(change.attributeName==='title')plainTooltip(change.target);
    if(active&&!visible(active.node))hideTooltip();
  }).observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['title','hidden','aria-hidden','open']});
  // A passive overlay leaves calendar drags and controls underneath available.
  // Test its geometry first: pointer-events:none can target another hint below.
  const remember=event=>{pointer={clientX:event.clientX,clientY:event.clientY,pointerType:event.pointerType,buttons:event.buttons};};
  const hover=event=>{remember(event);if(event.pointerType!=='mouse'||event.buttons){cancelFocus();return;}if(overTooltip(event)){dwell();return;}cancelFocus();if(active?.tip.contains(document.activeElement))return;const node=target(event.target,event);if(node)schedule(node);else{cancelPending();if(active)leave();}};
  document.addEventListener('pointerover',hover);
  document.addEventListener('pointermove',hover);
  document.addEventListener('pointerout',event=>{if(pending&&!pending.contains(event.relatedTarget)){const box=pending.getBoundingClientRect();if(event.clientX<box.left||event.clientX>box.right||event.clientY<box.top||event.clientY>box.bottom)cancelPending();}if(dismissed&&!dismissed.contains(event.relatedTarget))dismissed=null;if(active&&!inside(event.relatedTarget)&&!overTooltip(event))leave();});
  document.addEventListener('focusin',event=>{if(active?.tip.contains(event.target)){clearTimeout(leaveTimer);return;}if(!pointerFocus)schedule(target(event.target),event.target);});
  document.addEventListener('focusout',event=>{cancelPending();if(dismissed&&!dismissed.contains(event.relatedTarget))dismissed=null;if(active&&!inside(event.relatedTarget))hideTooltip();});
  document.addEventListener('pointerdown',event=>{remember(event);pointerFocus=true;cancelFocus();if(active?.tip.contains(event.target))focusTooltip();else hideTooltip();},true);
  document.addEventListener('keydown',event=>{
    pointerFocus=false;cancelFocus();
    if(event.key==='F2'&&active&&inside(event.target)){event.preventDefault();focusTooltip();return;}
    if(event.key==='Escape'&&active){
      const previous=active,restore=previous.tip.contains(document.activeElement);dismissed=previous.node;hideTooltip();
      // Escape dismisses this popup before a containing native dialog.
      event.preventDefault();event.stopPropagation();
      if(restore){const focus=visible(previous.previousFocus)&&previous.previousFocus!==document.body?previous.previousFocus:previous.node;focus.focus({preventScroll:true});}
    }
  },true);
  document.addEventListener('click',event=>{if(!active?.tip.contains(event.target))hideTooltip();},true);
  document.addEventListener('scroll',event=>{if(active&&!active.tip.contains(event.target)){
    const bounds=active.node.getBoundingClientRect();
    if(bounds.bottom<0||bounds.top>innerHeight||bounds.right<0||bounds.left>innerWidth){
      hideTooltip();
      // Horizontal scrolling can move the old source away without a new
      // pointer event over the control now under the stationary mouse.
      requestAnimationFrame(()=>{if(pointer){const target=document.elementFromPoint(pointer.clientX,pointer.clientY);if(target)hover({...pointer,target});}});
    }else active.instance.update();
  }},true);
  document.addEventListener('wheel',event=>{if(!overTooltip(event)||active.interactive)return;const inner=active.tip.querySelector('.tooltip-inner');if(inner.scrollHeight<=inner.clientHeight)return;inner.scrollTop+=event.deltaY*(event.deltaMode===1?16:event.deltaMode===2?inner.clientHeight:1);event.preventDefault();},{capture:true,passive:false});
  window.addEventListener('resize',hideTooltip);window.addEventListener('blur',hideTooltip);window.addEventListener('pagehide',hideTooltip);
}

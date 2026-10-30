import Tooltip from './assets/bootstrap/tooltip.js';

// Content factories construct application-owned DOM with textContent. Never
// interpret a title, API field, Markdown body or translation as HTML.
const content=new WeakMap();
let active=null,installed=false,pointerFocus=false,leaveTimer,dismissed=null;
const visible=node=>node.isConnected&&!node.closest('[hidden]')&&node.getClientRects().length>0;
export function hideTooltip() {
  clearTimeout(leaveTimer);if(!active)return;
  const previous=active;active=null;previous.instance.dispose();
  for(const [node,value] of previous.descriptions)value===null?node.removeAttribute('aria-describedby'):node.setAttribute('aria-describedby',value);
}
function refreshTooltip() {
  if(!active)return;
  if(!visible(active.node)){hideTooltip();return;}
  active.tip.querySelector('.tooltip-inner').replaceChildren(content.get(active.node)());active.instance.update();
}
export function richTooltip(node,render) {
  node.removeAttribute('title');node.dataset.tkTooltip='';content.set(node,render);
  if(active?.node===node)refreshTooltip();return node;
}
function plainTooltip(node) {
  const title=node.getAttribute('title');if(title===null)return;
  // A composite card owns its whole surface, including icons and text.
  if(node.parentElement?.closest('[data-tk-tooltip]')){node.removeAttribute('title');return;}
  node.removeAttribute('title');
  if(!title){delete node.dataset.tkTooltip;content.delete(node);if(active?.node===node)hideTooltip();return;}
  richTooltip(node,()=>{
    const value=document.createElement('div');value.className='tooltip-caption';
    const glyph=node.querySelector('.icon')?.cloneNode(true);if(glyph){glyph.removeAttribute('title');value.append(glyph);}
    const text=document.createElement('span');text.textContent=title;value.append(text);return value;
  });
}
function target(node) {
  if(!(node instanceof Element))return null;
  const titled=node.closest('[title]');if(titled)plainTooltip(titled);
  const candidate=node.closest('[data-tk-tooltip]');
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
    customClass:'timekeeper-tooltip',title:()=>content.get(node)()});
  instance.show();const tip=document.getElementById(node.getAttribute('aria-describedby'));
  if(!tip){instance.dispose();return;}
  active={node,instance,tip,descriptions};
  for(const [item,description] of descriptions)item.setAttribute('aria-describedby',[description,tip.id].filter(Boolean).join(' '));
}
function inside(node){return node instanceof Node&&active&&(active.node.contains(node)||active.tip.contains(node));}
function overTooltip(event){if(!active)return false;const box=active.tip.getBoundingClientRect();return event.clientX>=box.left&&event.clientX<=box.right&&event.clientY>=box.top&&event.clientY<=box.bottom;}
function leave(){clearTimeout(leaveTimer);leaveTimer=setTimeout(hideTooltip,100);}
export function installTooltips() {
  if(installed)return;installed=true;
  const scan=root=>{if(!(root instanceof Element))return;if(root.hasAttribute('title'))plainTooltip(root);root.querySelectorAll('[title]').forEach(plainTooltip);};
  scan(document.body);
  new MutationObserver(changes=>{
    for(const change of changes)if(change.type==='childList')change.addedNodes.forEach(scan);else if(change.attributeName==='title')plainTooltip(change.target);
    if(active&&!visible(active.node))hideTooltip();
  }).observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['title','hidden','aria-hidden','open']});
  // The overlay never intercepts a calendar drag or a click on a control below
  // it. Hit-test its bounds for hover persistence instead of capturing presses.
  document.addEventListener('pointerover',event=>{if(event.pointerType!=='mouse')return;if(overTooltip(event)){clearTimeout(leaveTimer);return;}const node=target(event.target);if(node)show(node);else if(active)leave();});
  document.addEventListener('pointermove',event=>{if(active&&overTooltip(event))clearTimeout(leaveTimer);});
  document.addEventListener('pointerout',event=>{if(dismissed&&!dismissed.contains(event.relatedTarget))dismissed=null;if(active&&!inside(event.relatedTarget)&&!overTooltip(event))leave();});
  document.addEventListener('focusin',event=>{if(!pointerFocus)show(target(event.target),event.target);});
  document.addEventListener('focusout',event=>{if(dismissed&&!dismissed.contains(event.relatedTarget))dismissed=null;if(active&&!inside(event.relatedTarget))hideTooltip();});
  document.addEventListener('pointerdown',event=>{pointerFocus=true;if(!active?.tip.contains(event.target))hideTooltip();},true);
  document.addEventListener('keydown',event=>{pointerFocus=false;if(event.key==='Escape'&&active){dismissed=active.node;hideTooltip();if(event.target.closest('dialog[open]'))event.preventDefault();}},true);
  document.addEventListener('click',()=>hideTooltip(),true);
  document.addEventListener('scroll',event=>{if(active&&!active.tip.contains(event.target)){const bounds=active.node.getBoundingClientRect();if(bounds.bottom<0||bounds.top>innerHeight||bounds.right<0||bounds.left>innerWidth)hideTooltip();else active.instance.update();}},true);
  document.addEventListener('wheel',event=>{if(!overTooltip(event))return;const inner=active.tip.querySelector('.tooltip-inner');if(inner.scrollHeight<=inner.clientHeight)return;inner.scrollTop+=event.deltaY*(event.deltaMode===1?16:event.deltaMode===2?inner.clientHeight:1);event.preventDefault();},{capture:true,passive:false});
  window.addEventListener('blur',hideTooltip);window.addEventListener('pagehide',hideTooltip);
}

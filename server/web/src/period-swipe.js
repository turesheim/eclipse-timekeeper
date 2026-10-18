/** Navigate time periods without taking over scrolling, zooming or record editing. */
export function periodSwipe(root,{enabled,navigate}) {
  let gesture=null,suppressClickUntil=0;
  const cancel=()=>{gesture=null;};
  const available=()=>innerWidth<=767 && enabled();
  root.addEventListener('touchstart',event=>{
    cancel();suppressClickUntil=0;
    if(!available() || event.touches.length!==1 || event.defaultPrevented)return;
    const target=event.target,touch=event.touches[0];
    if(target.closest('input,select,textarea,a,summary,[contenteditable]:not([contenteditable="false"]),nav,dialog,[popover],.calendar-scroll') ||
       target.closest('button:not(.track-client-day)') || !getSelection().isCollapsed)return;
    // Leave the browser's edge navigation available. Wide tables keep their own pan.
    if(touch.clientX<24 || touch.clientX>innerWidth-24)return;
    for(let node=target;node && node!==root;node=node.parentElement) {
      if(node.scrollWidth>node.clientWidth+1 && /auto|scroll/.test(getComputedStyle(node).overflowX))return;
    }
    gesture={id:touch.identifier,x:touch.clientX,y:touch.clientY,at:performance.now(),scrollX,scrollY,locked:false};
  },{passive:true});
  root.addEventListener('touchmove',event=>{
    if(!gesture)return;
    if(!available() || event.touches.length!==1){cancel();return;}
    const touch=event.touches[0];
    if(touch.identifier!==gesture.id){cancel();return;}
    const dx=Math.abs(touch.clientX-gesture.x),dy=Math.abs(touch.clientY-gesture.y);
    if(!gesture.locked && dy>12 && dy>=dx){cancel();return;}
    if(dx>12 && dx>dy*2)gesture.locked=true;
    if(gesture.locked && event.cancelable)event.preventDefault();
  },{passive:false});
  root.addEventListener('touchend',event=>{
    const start=gesture;cancel();
    if(!start || !available() || event.touches.length || performance.now()-start.at>900 ||
       Math.abs(scrollX-start.scrollX)>5 || Math.abs(scrollY-start.scrollY)>5)return;
    const touch=[...event.changedTouches].find(touch=>touch.identifier===start.id);
    if(!touch)return;
    const dx=touch.clientX-start.x,dy=touch.clientY-start.y;
    if(Math.abs(dx)<Math.max(64,Math.min(96,innerWidth*.18)) || Math.abs(dx)<=Math.abs(dy)*2)return;
    if(event.cancelable)event.preventDefault();
    suppressClickUntil=performance.now()+700;
    navigate(dx<0 ? 1:-1);
  },{passive:false});
  root.addEventListener('touchcancel',cancel,{passive:true});
  // A new mouse press is independent input, rather than a touch compatibility click.
  root.addEventListener('pointerdown',event=>{if(event.pointerType==='mouse')suppressClickUntil=0;},true);
  root.addEventListener('click',event=>{
    if(event.detail && performance.now()<suppressClickUntil) {
      suppressClickUntil=0;event.preventDefault();event.stopImmediatePropagation();
    }
  },true);
  window.addEventListener('resize',cancel);
}

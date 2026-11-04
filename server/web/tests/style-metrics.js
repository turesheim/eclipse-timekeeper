/** Resolve a shared length token using the browser's CSS units and font base. */
export async function resolvedLength(page,property){
  return page.evaluate(property=>{
    const probe=document.createElement('span');probe.style.fontSize=`var(${property})`;
    probe.style.position='fixed';probe.style.top='0';probe.style.left='0';probe.style.visibility='hidden';document.body.append(probe);
    const value=getComputedStyle(probe).fontSize;probe.remove();return value;
  },property);
}

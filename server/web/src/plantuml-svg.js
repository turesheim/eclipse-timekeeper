const svgNamespace='http://www.w3.org/2000/svg';
const elements=new Set(['svg','defs','g','rect','a','line','path','text','tspan','title','desc','polygon','polyline','circle','ellipse','clipPath','linearGradient','radialGradient','stop','filter','feGaussianBlur','use','symbol']);
const attributes=new Set(['x','y','x1','y1','x2','y2','width','height','viewBox','d','points','r','rx','ry','cx','cy','fill','stroke','stroke-width','stroke-dasharray','stroke-linecap','stroke-linejoin','opacity','fill-opacity','stroke-opacity','font-family','font-size','font-weight','font-style','text-anchor','transform','id','preserveAspectRatio','version','xml:space','textLength','lengthAdjust','clip-path','filter','stop-color','stop-opacity','offset']);
const colors=/^(?:none|transparent|currentColor|#[0-9a-f]{3,8}|[a-z]+|rgba?\([0-9.,%\s+-]+\)|url\(#[\w:.-]+\))$/i;
export function plantumlSourceAllowed(source){return !/^\s*!(?:include\w*|import|theme)\b/im.test(source)&&!/<img\b/i.test(source);}
/** Parse as data and emit an inert image, never authored SVG in the page DOM. */
export function plantumlImage(svg){
  if(typeof svg!=='string'||svg.length>2_000_000||/<!DOCTYPE|<!ENTITY/i.test(svg))throw new Error('Invalid diagram');
  const document=new DOMParser().parseFromString(svg,'image/svg+xml'),root=document.documentElement;
  if(root?.localName!=='svg'||root.namespaceURI!==svgNamespace||document.querySelector('parsererror'))throw new Error('Invalid diagram');
  const labels=[...root.querySelectorAll('text')].slice(0,2).map(node=>node.textContent);
  // The pinned engine reports syntax/preprocessor errors as a welcome/error SVG,
  // rather than through its error callback. Keep those implementation details
  // out of the editor and leave the canonical source available for correction.
  if(/^PlantUML version /.test(labels[0]||'')&&/^\[From textarea \(line /.test(labels[1]||''))throw new Error('Invalid diagram');
  const box=root.getAttribute('viewBox')?.trim().split(/[ ,]+/).map(Number);
  if(box?.length!==4||box.some(value=>!Number.isFinite(value))||box[2]<=0||box[3]<=0||box[2]>8192||box[3]>8192)throw new Error('Invalid diagram bounds');
  const nodes=[root,...root.querySelectorAll('*')];if(nodes.length>20000)throw new Error('Diagram too large');
  for(const node of nodes){
    if(node.namespaceURI!==svgNamespace||!elements.has(node.localName))throw new Error('Unsupported diagram element');
    for(const attribute of [...node.attributes]){
      const {name,value}=attribute;
      if(name==='href'||name==='xlink:href'){if(node.localName!=='use'||!/^#[\w:.-]+$/.test(value))node.removeAttributeNode(attribute);continue;}
      if(!attributes.has(name)||['fill','stroke','stop-color'].includes(name)&&!colors.test(value)||['clip-path','filter'].includes(name)&&!/^url\(#[\w:.-]+\)$/.test(value))node.removeAttributeNode(attribute);
    }
    if(['text','tspan'].includes(node.localName))node.setAttribute('style','white-space:pre');
  }
  const instructions=document.createTreeWalker(root,NodeFilter.SHOW_PROCESSING_INSTRUCTION|NodeFilter.SHOW_COMMENT),remove=[];while(instructions.nextNode())remove.push(instructions.currentNode);for(const node of remove)node.remove();
  root.setAttributeNS('http://www.w3.org/2000/xmlns/','xmlns',svgNamespace);root.setAttributeNS('http://www.w3.org/2000/xmlns/','xmlns:xlink','http://www.w3.org/1999/xlink');root.setAttribute('width',String(box[2]));root.setAttribute('height',String(box[3]));
  return new Blob([new XMLSerializer().serializeToString(root)],{type:'image/svg+xml'});
}

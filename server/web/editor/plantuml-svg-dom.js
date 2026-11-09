/** Minimal inert SVG document for the pinned renderer in a real worker.
 * Canvas supplies font metrics. Unsupported DOM operations fail the preview;
 * this adapter never mounts user markup or loads resources.
 */
const names=new Set(['svg','defs','g','rect','a','line','path','text','tspan','title','desc','polygon','polyline','circle','ellipse','image','clipPath','linearGradient','radialGradient','stop','filter','feGaussianBlur','use','symbol']);
const xml=value=>String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
export function svgDocument(canvas){
  let nodes=0,characters=0;const metrics=canvas.getContext('2d');if(!metrics)throw new Error('Canvas unavailable');
  const account=value=>{characters+=value.length;if(characters>2_000_000)throw new Error('SVG budget exceeded');return value;};
  class Element{
    constructor(name){if(!names.has(name)||++nodes>20_000)throw new Error('Unsupported SVG');this.tagName=name;this.attributes=new Map();this.childNodes=[];this.style={};this.value='';this.ownerDocument=document;}
    setAttribute(name,value){if(!/^[A-Za-z_][\w:.-]*$/.test(name)||this.attributes.size>=64)throw new Error('Unsupported SVG attribute');this.attributes.set(name,account(String(value)));}
    setAttributeNS(_namespace,name,value){this.setAttribute(name,value);}
    appendChild(child){if(!child)throw new Error('Unsupported SVG tree');for(let parent=this;parent;parent=parent.parentNode)if(parent===child)throw new Error('Invalid SVG tree');child.parentNode?.removeChild(child);this.childNodes.push(child);child.parentNode=this;return child;}
    removeChild(child){const index=this.childNodes.indexOf(child);if(index<0)throw new Error('Invalid SVG child');this.childNodes.splice(index,1);child.parentNode=null;return child;}
    get firstChild(){return this.childNodes[0]||null;}
    get textContent(){return this.value+this.childNodes.map(child=>child.textContent).join('');}
    set textContent(value){this.value=account(String(value));for(const child of this.childNodes)child.parentNode=null;this.childNodes=[];}
    getBBox(){
      const size=Number(this.attributes.get('font-size'))||14;metrics.font=size+'px '+(this.attributes.get('font-family')||'sans-serif');const measured=metrics.measureText(this.textContent);
      return {x:-measured.actualBoundingBoxLeft,y:-measured.actualBoundingBoxAscent,width:measured.actualBoundingBoxLeft+measured.actualBoundingBoxRight,height:measured.actualBoundingBoxAscent+measured.actualBoundingBoxDescent};
    }
    serialize(depth=0){
      if(depth>128)throw new Error('SVG depth exceeded');const attributes=new Map(this.attributes);
      if(this.tagName==='svg'){attributes.set('xmlns','http://www.w3.org/2000/svg');attributes.set('xmlns:xlink','http://www.w3.org/1999/xlink');}
      if(Object.keys(this.style).length)attributes.set('style',Object.entries(this.style).map(([key,value])=>key.replace(/[A-Z]/g,char=>'-'+char.toLowerCase())+':'+value).join(';'));
      return '<'+this.tagName+[...attributes].map(([key,value])=>' '+key+'="'+xml(value)+'"').join('')+'>'+xml(this.value)+this.childNodes.map(child=>child.serialize(depth+1)).join('')+'</'+this.tagName+'>';
    }
  }
  const document={
    createElement:tag=>{if(tag==='canvas')return canvas;throw new Error('Unsupported DOM element');},
    createElementNS:(namespace,tag)=>{if(namespace!=='http://www.w3.org/2000/svg')throw new Error('Unsupported namespace');return new Element(tag);},
    // The renderer appends compressed source as a processing instruction.
    // Preview images need no copy of that private source in their metadata.
    createProcessingInstruction:()=>({textContent:'',serialize:()=>''}),
    body:{appendChild:node=>node,removeChild:node=>node}
  };
  return {document,serialize:element=>{const svg=element.serialize();if(svg.length>2_000_000)throw new Error('SVG budget exceeded');return svg;}};
}

import {svgDocument} from './plantuml-svg-dom.js';

// The engine is copied unchanged from the pinned official MIT distribution.
// No diagram source can select a network or filesystem loader.
globalThis.window=globalThis;
globalThis.PLANTUML_STDLIB_LOADER=(_url,_ok,error)=>{error('Unavailable resource');return true;};
globalThis.PLANTUML_FILE_LOADER=(_path,_from,_ok,error)=>error('Unavailable resource');
globalThis.fetch=()=>Promise.reject(new Error('Unavailable resource'));
globalThis.XMLHttpRequest=class{constructor(){throw new Error('Unavailable resource');}};
globalThis.WebSocket=class{constructor(){throw new Error('Unavailable resource');}};
globalThis.console={debug:()=>{},info:()=>{},log:()=>{},warn:()=>{},error:()=>{}};
const {renderToString}=await import('./plantuml-engine.js');

self.onmessage=event=>{
  const {source,dark}=event.data||{};
  if(typeof source!=='string'||new TextEncoder().encode(source).length>10_000){postMessage({error:true});return;}
  try{
    const adapter=svgDocument(new OffscreenCanvas(1,1));globalThis.document=adapter.document;globalThis.XMLSerializer=class{serializeToString(element){return adapter.serialize(element);}};
    renderToString(source.split(/\r\n|\r|\n/),svg=>postMessage(typeof svg==='string'&&svg.length<=2_000_000?{svg}:{error:true}),()=>postMessage({error:true}),{dark:!!dark,maxSvgSize:8192});
  }catch{postMessage({error:true});}
};
postMessage({ready:true});

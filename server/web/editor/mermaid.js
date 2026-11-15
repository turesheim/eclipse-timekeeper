import {render} from 'pele';

let sequence=0;
export function renderDiagram(source,{maxWidth}={}){
  // The editor supplies only layout width; authored config cannot enable I/O.
  const {svg}=render(source,{idPrefix:'timekeeper-mermaid-'+(++sequence),maxWidth,
    links:false,images:false,limit:10000,outputLimit:2000000,maxEdges:300});
  return svg;
}

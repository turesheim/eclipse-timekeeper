import mermaid from 'mermaid';

mermaid.initialize({startOnLoad:false,securityLevel:'strict',htmlLabels:false,suppressErrorRendering:true,maxTextSize:10000,maxEdges:300});

let sequence=0;
export async function renderDiagram(source){
  const {svg}=await mermaid.render('timekeeper-mermaid-'+(++sequence),source);
  return svg;
}

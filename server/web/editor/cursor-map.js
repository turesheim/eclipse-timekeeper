// Match textblocks in document order, then map visible characters to their
// authored source spans. Delimiters belong to source, not the rich selection.
export function cursorMap(tree,doc,source){
  const authored=[],visible=[];
  const atom='\uFFFC';
  const leaves=node=>{
    if(node.type==='text'||node.type==='inlineCode'||node.type==='code'){
      const start=node.position?.start.offset??0,end=node.position?.end.offset??start;
      const raw=source.slice(start,end),value=node.value||'',exact=raw.indexOf(value);
      if(exact>=0)return [...Array(value.length)].map((_,index)=>({char:value[index],from:start+exact+index,to:start+exact+index+1}));
      // Escapes/entities can change source length. Keep their complete span;
      // ordinary unescaped runs still map character for character.
      let offset=0;return [...Array(value.length)].map((_,index)=>{const char=value[index],at=raw.indexOf(char,offset),from=at>=0?at:offset;offset=Math.min(raw.length,from+1);return {char,from:start+from,to:start+offset};});
    }
    if(node.children)return node.children.flatMap(leaves);
    const from=node.position?.start.offset??0,to=node.position?.end.offset??from;
    return [{char:node.type==='break'?'\n':atom,from,to}];
  };
  const visit=node=>{
    if(node.type==='sourceFragment')return;
    if(['paragraph','heading','code','tableCell'].includes(node.type)){authored.push({node,characters:leaves(node)});return;}
    node.children?.forEach(visit);
  };visit(tree);
  doc.descendants((node,pos)=>{
    // Fenced diagrams occupy a block slot even though their preview is atomic.
    // Retain that slot so prose on either side still matches its source block.
    if(['mermaid_diagram','plantuml_diagram'].includes(node.type.name)){visible.push({node,pos,characters:[{char:atom,from:pos,to:pos+node.nodeSize}]});return false;}
    if(!node.isTextblock)return;
    const characters=[];node.forEach((child,offset)=>{
      if(child.isText)for(let i=0;i<child.text.length;i++)characters.push({char:child.text[i],from:pos+1+offset+i,to:pos+1+offset+i+1});
      else characters.push({char:child.type.name==='hardbreak'?'\n':atom,from:pos+1+offset,to:pos+1+offset+child.nodeSize});
    });visible.push({node,pos,characters});return false;
  });
  const pairs=[];
  if(authored.length===visible.length)authored.forEach((entry,index)=>{
    const rich=visible[index];if(entry.characters.map(x=>x.char).join('')!==rich.characters.map(x=>x.char).join(''))return;
    entry.characters.forEach((item,index)=>pairs.push({source:item,rich:rich.characters[index]}));
  });
  const map=(position,from,to,bias)=>{
    const boundary=bias<0?[...pairs].reverse().find(pair=>pair[from].to===position):pairs.find(pair=>pair[from].from===position);
    if(boundary)return boundary[to][bias<0?'to':'from'];
    const exact=pairs.find(pair=>position>=pair[from].from&&position<pair[from].to);
    if(exact)return exact[to][position===exact[from].from?'from':'to'];
    let closest;for(const pair of pairs)for(const edge of ['from','to']){
      const distance=Math.abs(position-pair[from][edge]);
      if(!closest||distance<closest.distance||distance===closest.distance&&bias>0)closest={distance,value:pair[to][edge]};
    }
    return closest?.value??0;
  };
  return {source:(position,bias=1)=>map(position,'rich','source',bias),rich:(position,bias=1)=>map(position,'source','rich',bias)};
}

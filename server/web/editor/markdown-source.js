/** Preserve authored spelling and separators for untouched top-level blocks.
 * ProseMirror nodes are immutable: unchanged siblings retain their identity.
 * Ambiguous projections keep the existing canonical serializer as a safe fallback.
 */
export function markdownSource({source,tree,document,serialize,equivalent}){
  const records=tree.children.map((block,index)=>({index,start:block.position?.start.offset,end:block.position?.end.offset,node:document.maybeChild(index)}));
  const mapped=new WeakMap();
  const valid=records.length===document.childCount&&records.every(record=>record.node&&Number.isInteger(record.start)&&Number.isInteger(record.end));
  if(valid)for(const record of records)mapped.set(record.node,record.index);
  let previous=document,previousText=source;
  return current=>{
    if(current===previous)return previousText;
    const remember=value=>{previous=current;previousText=value;return value;};
    if(current.eq(document))return remember(source);
    const canonical=serialize(current);
    // Clearing the document must clear its old trailing whitespace as well.
    if(!canonical||!valid||!records.length)return remember(canonical);
    const nodes=[];current.forEach(node=>nodes.push(node));
    // Match trackedDocument's serializer: a final empty paragraph is a caret
    // destination, not an authored block. Navigating out of code must preserve
    // the original fence spelling, line endings and trailing whitespace.
    if(nodes.length>1&&nodes.at(-1).type.name==='paragraph'&&!nodes.at(-1).content.size)nodes.pop();
    const indices=nodes.map(node=>mapped.get(node));
    // Equal-size edited runs between immutable anchors keep their original
    // block slots. Insertions/deletions never borrow another block's source.
    const anchors=[{at:-1,index:-1}];let last=-1;
    indices.forEach((index,at)=>{if(index!==undefined&&index>last){anchors.push({at,index});last=index;}});
    anchors.push({at:nodes.length,index:records.length});
    for(let a=1;a<anchors.length;a++){
      const left=anchors[a-1],right=anchors[a],count=right.at-left.at-1;
      if(count===right.index-left.index-1)for(let offset=1;offset<=count;offset++)
        if(indices[left.at+offset]===undefined)indices[left.at+offset]=left.index+offset;
    }
    const eol=source.match(/\r\n|\n|\r/)?.[0]||'\n';
    const unchanged=nodes.map((node,index)=>indices[index]!==undefined&&node.eq(records[indices[index]].node));
    const pieces=nodes.map((node,index)=>{
      const record=indices[index]===undefined?null:records[indices[index]];
      if(unchanged[index])return source.slice(record.start,record.end);
      const single=current.type.create(current.attrs,node);
      // Remove the serializer's document terminator, not authored whitespace.
      return serialize(single).replace(/\n$/,'').replace(/\r\n|\n/g,eol);
    });
    let candidate=source.slice(0,records[0].start);
    pieces.forEach((piece,index)=>{
      if(index){
        const before=indices[index-1],after=indices[index];let gap=eol+eol;
        if(before!==undefined&&after===before+1){
          const original=source.slice(records[before].end,records[after].start);
          // A changed heading/list may now need a separating blank line.
          if(unchanged[index-1]&&unchanged[index]||(original.match(/\r\n|\n|\r/g)||[]).length>=2)gap=original;
        }
        candidate+=gap;
      }
      candidate+=piece;
    });
    candidate+=source.slice(records.at(-1).end);
    // Reused spelling must not change Markdown meaning at a block boundary.
    return remember(equivalent(candidate,canonical)?candidate:canonical);
  };
}

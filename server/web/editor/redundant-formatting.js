const formatting=new Set(['strong','emphasis','delete','highlight','subscript','superscript']);
const ordinaryText=node=>node.type==='text'&&Object.keys(node).every(key=>['type','value','position'].includes(key));

/** ProseMirror stores one mark of each type; flatten redundant authored nesting. */
export function normalizeFormatting(tree){
  const visit=(node,active)=>{
    const mark=formatting.has(node.type),redundant=mark&&active.has(node.type)&&Object.keys(node).every(key=>['type','children','position'].includes(key));
    if(!node.children)return [node];
    const next=mark?new Set([...active,node.type]):active,children=[];
    for(const child of node.children.flatMap(child=>visit(child,next))){
      const previous=children.at(-1);
      if(previous&&ordinaryText(previous)&&ordinaryText(child))children[children.length-1]={...previous,value:previous.value+child.value,
        ...(previous.position&&child.position?{position:{start:previous.position.start,end:child.position.end}}:{})};
      else children.push(child);
    }
    return redundant?children:[{...node,children}];
  };
  return visit(tree,new Set())[0];
}

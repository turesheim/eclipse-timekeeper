import {defaultHandlers} from 'mdast-util-to-markdown';

// Autolinks need their own source spelling: resourceLink stays enabled for
// explicitly authored [label](destination) links throughout the editor.
function withAutolink(node,state,run){
  const options=state.options;
  if(node.data?.sourceAutolink)state.options={...options,resourceLink:false};
  try{return run();}finally{state.options=options;}
}
const link=(node,parent,state,info)=>withAutolink(node,state,()=>defaultHandlers.link(node,parent,state,info));
link.peek=(node,parent,state,info)=>withAutolink(node,state,()=>defaultHandlers.link.peek(node,parent,state,info));

export function remarkAutolinks(){
  const data=this.data();
  (data.toMarkdownExtensions??=[]).push({handlers:{link}});
  return (tree,file)=>{
    const source=String(file.value??'');
    const visit=node=>{
      if(node.type==='link'){
        const raw=source.slice(node.position?.start.offset,node.position?.end.offset);
        if(raw.startsWith('<')||/^https?:\/\//i.test(raw))node.data={...node.data,sourceAutolink:true};
      }
      node.children?.forEach(visit);
    };
    visit(tree);
  };
}

export function autolinkSchema(schema){
  return {...schema,inclusive:false,
    attrs:{...schema.attrs,sourceAutolink:{default:false,validate:'boolean'}},
    parseDOM:schema.parseDOM.map(rule=>({...rule,getAttrs:dom=>{
      const attrs=rule.getAttrs?.(dom);return attrs===false?false:{...attrs,sourceAutolink:dom.hasAttribute('data-source-autolink')};
    }})),
    toDOM:mark=>{
      const [tag,attributes,...content]=schema.toDOM(mark),{sourceAutolink,...attrs}=attributes;
      return [tag,{...attrs,tabindex:'0',...(sourceAutolink?{'data-source-autolink':''}:{})},...content];
    },
    parseMarkdown:{...schema.parseMarkdown,runner:(state,node,type)=>{
      state.openMark(type,{href:node.url,title:node.title,sourceAutolink:!!node.data?.sourceAutolink});state.next(node.children);state.closeMark(type);
    }},
    toMarkdown:{...schema.toMarkdown,runner:(state,mark)=>{
      state.withMark(mark,'link',undefined,{title:mark.attrs.title,url:mark.attrs.href,...(mark.attrs.sourceAutolink?{data:{sourceAutolink:true}}:{})});
    }}
  };
}

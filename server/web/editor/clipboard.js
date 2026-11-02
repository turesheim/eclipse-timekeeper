import {DOMParser,Slice} from '@milkdown/kit/prose/model';
const tags=new Set(['P','DIV','BR','H1','H2','H3','H4','H5','H6','STRONG','B','EM','I','S','DEL','CODE','PRE','UL','OL','LI','BLOCKQUOTE','TABLE','THEAD','TBODY','TR','TD','TH','HR','A']);
/** Copy only semantic elements into a fresh inert fragment. Never attach clipboard HTML. */
export function clipboardSlice(schema,html,text,linkAllowed){
  const input=document.createElement('template');input.innerHTML=html;
  const output=document.createElement('div');
  const copy=(source,parent)=>{
    if(source.nodeType===3){parent.append(document.createTextNode(source.textContent));return;}
    if(source.nodeType!==1||['SCRIPT','STYLE','IFRAME','OBJECT','SVG','MATH','IMG','VIDEO','AUDIO','LINK','META','BASE'].includes(source.tagName))return;
    let target=parent;
    if(tags.has(source.tagName)){
      target=document.createElement(source.tagName.toLowerCase());
      if(source.tagName==='A'&&linkAllowed(source.getAttribute('href')))target.setAttribute('href',source.getAttribute('href'));
      if(source.tagName==='OL'&&/^\d+$/.test(source.getAttribute('start')||''))target.setAttribute('start',source.getAttribute('start'));
      for(const name of ['colspan','rowspan'])if(['TD','TH'].includes(source.tagName)&&/^[1-9]\d?$/.test(source.getAttribute(name)||''))target.setAttribute(name,source.getAttribute(name));
      parent.append(target);
    }
    for(const child of source.childNodes)copy(child,target);
  };
  for(const child of input.content.childNodes)copy(child,output);
  if(!output.textContent&&!output.querySelector('hr,br'))return null;
  const parser=DOMParser.fromSchema(schema);
  // Preserve block boundaries when pasting headings, lists or multiple blocks.
  // A single ordinary paragraph remains open so inline paste joins the caret.
  const blocks=[...output.children];
  return blocks.length>1||blocks.some(node=>!['P','SPAN'].includes(node.tagName))?new Slice(parser.parse(output,{preserveWhitespace:true}).content,0,0):parser.parseSlice(output,{preserveWhitespace:true});
}
export function spreadsheetSlice(schema,text){
  if(!text.includes('\t'))return null;
  const rows=text.replace(/\r\n/g,'\n').trimEnd().split('\n').map(line=>line.split('\t'));
  if(rows.length>200||rows.some(row=>row.length>50))return null;
  const table=document.createElement('table');
  rows.forEach((cells,index)=>{const row=document.createElement('tr');cells.forEach(value=>{const cell=document.createElement(index?'td':'th');cell.textContent=value;row.append(cell);});table.append(row);});
  const holder=document.createElement('div');holder.append(table);
  return new Slice(DOMParser.fromSchema(schema).parse(holder).content,0,0);
}

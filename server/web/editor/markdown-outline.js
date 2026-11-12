import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import {splitMarkdown} from '../src/markdown-document.js';

const parser=unified().use(remarkParse).use(remarkGfm);
const label=node=>node.type==='html'?'':node.type==='image'?node.alt||'':node.children?node.children.map(label).join(''):node.value||'';

/** Parse actual Markdown headings, keeping their source positions for navigation. */
export function markdownOutline(source){
  const {body,frontmatter}=splitMarkdown(source),headings=[];
  const visit=node=>{
    if(node.type==='heading')headings.push({text:label(node),level:node.depth,offset:frontmatter.length+node.position.start.offset});
    for(const child of node.children||[])visit(child);
  };
  visit(parser.parse(body));return headings;
}

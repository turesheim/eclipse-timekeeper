import {$node} from '@milkdown/kit/utils';
/** The CommonMark document contract plus an in-memory source/history identity.
 * sourceEpoch never enters Markdown, HTML or persistent browser storage.
 */
export const trackedDocument=$node('doc',()=>({
  content:'block+',attrs:{sourceEpoch:{default:0,validate:'number'}},
  parseMarkdown:{match:node=>node.type==='root',runner:(state,node,type)=>state.injectRoot(node,type)},
  toMarkdown:{match:node=>node.type.name==='doc',runner:(state,node)=>{
    state.openNode('root');const last=node.lastChild;
    state.next(last?.type.name==='paragraph'&&!last.content.size?node.content.cut(0,node.content.size-last.nodeSize):node.content);
  }}
}));

import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import {remarkInlineStyles} from './remark-inline-styles.js';
import {emojiText} from '../src/emoji.js';
import {hashtagRanges} from '../src/hashtags.js';
import {clientMentionRanges} from '../src/client-mentions.js';

const parser=unified().use(remarkParse).use(remarkGfm,{singleTilde:false}).use(remarkInlineStyles);
const tags={strong:'strong',emphasis:'em',delete:'del',highlight:'mark',subscript:'sub',superscript:'sup',paragraph:'p',blockquote:'blockquote',listItem:'li',table:'table',tableRow:'tr',tableCell:'td'};
/** Read-only task presentation uses the editor dialect; authored HTML and images stay inert. */
export function renderMarkdown(source,{inline=false,links=true}={}){
  const fragment=document.createDocumentFragment(),tree=parser.runSync(parser.parse(source));
  const append=(host,value,literal=false)=>{
    if(literal){host.append(document.createTextNode(value));return;}
    let offset=0;const ranges=[...hashtagRanges(value).map(range=>({...range,className:'markdown-hashtag'})),...clientMentionRanges(value).map(range=>({...range,className:'markdown-client-mention'}))].sort((a,b)=>a.from-b.from);
    for(const range of ranges){host.append(document.createTextNode(emojiText(value.slice(offset,range.from))));const token=document.createElement('span');token.className=range.className;token.textContent=value.slice(range.from,range.to);host.append(token);offset=range.to;}
    host.append(document.createTextNode(emojiText(value.slice(offset))));
  };
  const render=(node,host,literal=false)=>{
    if(node.type==='text'){append(host,node.value,literal);return;}
    if(['html','image','imageReference'].includes(node.type)){host.append(document.createTextNode(node.value||node.alt||''));return;}
    if(['definition','footnoteDefinition'].includes(node.type))return;
    if(node.type==='break'){host.append(inline?document.createTextNode(' '):document.createElement('br'));return;}
    if(['inlineCode','code'].includes(node.type)){const code=document.createElement('code');code.textContent=node.value;if(node.type==='code'&&!inline){const pre=document.createElement('pre');pre.append(code);host.append(pre);}else host.append(code);return;}
    if(node.type==='thematicBreak'){if(!inline)host.append(document.createElement('hr'));return;}
    let tag=tags[node.type];
    if(node.type==='heading')tag='h'+node.depth;
    if(node.type==='list')tag=node.ordered?'ol':'ul';
    if(node.type==='link'){
      try{const url=new URL(node.url);if(links&&['http:','https:','mailto:'].includes(url.protocol)&&!url.username&&!url.password)tag='a';}catch{/* Unsafe/relative destinations retain their visible label only. */}
      literal=true;
    }
    if(inline&&!['strong','em','del','mark','sub','sup','a'].includes(tag))tag=null;
    const element=tag?document.createElement(tag):host;
    if(tag==='a'){element.href=node.url;element.target='_blank';element.rel='noopener noreferrer';}
    if(tag==='ol'&&node.start)element.start=node.start;
    for(const child of node.children||[])render(child,element,literal);
    if(element!==host)host.append(element);else if(['paragraph','heading','listItem'].includes(node.type))host.append(document.createTextNode(' '));
  };
  render(tree,fragment);return fragment;
}

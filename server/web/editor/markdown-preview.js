import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import {remarkInlineStyles} from './remark-inline-styles.js';
import {emojiText} from '../src/emoji.js';
import {hashtagRanges} from '../src/hashtags.js';
import {clientMentionRanges} from '../src/client-mentions.js';
import {wikiReference} from '../src/wiki-links.js';
import {escapedBracket} from '../src/markdown-brackets.js';

const parser=unified().use(remarkParse).use(remarkGfm,{singleTilde:false}).use(remarkInlineStyles);
const tags={strong:'strong',emphasis:'em',delete:'del',highlight:'mark',subscript:'sub',superscript:'sup',paragraph:'p',blockquote:'blockquote',listItem:'li',table:'table',tableRow:'tr',tableCell:'td'};
/** References in prose only; code, HTML and escaped examples remain inert. */
export function markdownImages(source){
  const tree=parser.runSync(parser.parse(source)),images=[],definitions=new Map(tree.children.filter(node=>node.type==='definition').map(node=>[node.identifier,node.url]));
  const visit=node=>{
    if(['code','inlineCode','html','link','linkReference','definition'].includes(node.type))return;
    if(node.type==='image'||node.type==='imageReference'){const reference=node.url||definitions.get(node.identifier);if(reference)images.push({reference,kind:'relative',alt:node.alt||''});return;}
    if(node.type==='text'&&node.position){const offset=node.position.start.offset,raw=source.slice(offset,node.position.end.offset);for(const match of raw.matchAll(/!\[\[([^\]\r\n]+)\]\]/g)){if(escapedBracket(source,offset+match.index))continue;const ref=wikiReference(match[1],true);if(ref)images.push({reference:match[1],kind:'wiki',alt:ref.label||ref.path.split('/').at(-1)});}}
    for(const child of node.children||[])visit(child);
  };visit(tree);return [...new Map(images.map(value=>[value.kind+'\0'+value.reference,value])).values()];
}
/** Read-only task presentation; images require an explicit authenticated resolver. */
export function renderMarkdown(source,{inline=false,links=true,image}={}){
  const fragment=document.createDocumentFragment(),tree=parser.runSync(parser.parse(source));
  const definitions=new Map(tree.children.filter(node=>node.type==='definition').map(node=>[node.identifier,node.url]));
  const append=(host,value,literal=false)=>{
    if(literal){host.append(document.createTextNode(value));return;}
    let offset=0;const ranges=[...hashtagRanges(value).map(range=>({...range,className:'markdown-hashtag'})),...clientMentionRanges(value).map(range=>({...range,className:'markdown-client-mention'}))].sort((a,b)=>a.from-b.from);
    for(const range of ranges){host.append(document.createTextNode(emojiText(value.slice(offset,range.from))));const token=document.createElement('span');token.className=range.className;token.textContent=value.slice(range.from,range.to);host.append(token);offset=range.to;}
    host.append(document.createTextNode(emojiText(value.slice(offset))));
  };
  const render=(node,host,literal=false)=>{
    if(node.type==='text'){append(host,node.value,literal);return;}
    if(['image','imageReference'].includes(node.type)&&image&&!inline){
      const preview=image(node.type==='image'?node.url:definitions.get(node.identifier),node.alt||'');if(preview)host.append(preview);return;
    }
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

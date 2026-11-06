import DOMPurify from './assets/dompurify/purify.js';
import {icon} from './icons.js';
import {t} from './i18n.js';

// Calendar descriptions are the only tooltip field that may contain HTML.
// Keep resource-bearing content inert until it has passed the HTML boundary.
const options={
  IN_PLACE:true,
  ALLOWED_TAGS:['div','span','p','br','hr','b','strong','i','em','u','s','strike','del','sub','sup','small','h1','h2','h3','h4','h5','h6','ul','ol','li','dl','dt','dd','blockquote','pre','code','a','table','thead','tbody','tfoot','tr','th','td','caption'],
  ALLOWED_ATTR:['href','title'],
  ALLOWED_NAMESPACES:['http://www.w3.org/1999/xhtml'],
  ALLOW_ARIA_ATTR:false,ALLOW_DATA_ATTR:false,
  ADD_FORBID_CONTENTS:['svg','math','template','iframe','object','embed','form','button','textarea','select'],
};
function link(node,url){node.href=url;node.target='_blank';node.rel='noopener noreferrer';return node;}
function meetingLinks(root,location=false){
  for(const anchor of root.querySelectorAll('a[href]')){
    let url;try{url=new URL(anchor.href);}catch{continue;}
    if(!['http:','https:'].includes(url.protocol)||url.username||url.password)continue;
    const host=url.hostname.toLowerCase();let provider;
    if(['teams.microsoft.com','teams.live.com','teams.cloud.microsoft'].includes(host))provider=['teams','microsoft-teams','Microsoft Teams'];
    else if(host==='meet.google.com')provider=['google-meet','google','Google Meet'];
    else if(host==='zoom.us'||host.endsWith('.zoom.us'))provider=['zoom','camera-video-fill','Zoom'];
    else if(location)provider=['video','camera-video',t('calendar.video_meeting')];
    if(!provider)continue;
    const [id,glyph,label]=provider;anchor.dataset.meetingProvider=id;anchor.classList.add('calendar-meeting-link');anchor.prepend(icon(glyph),' ',label+' · ');
  }
  return root;
}
/** Locations stay literal; their web links can represent a remote meeting. */
export function calendarLocation(text){const value=document.createElement('span');value.append(linkedText(text));return meetingLinks(value,true);}
export function linkedText(text){
  const fragment=document.createDocumentFragment();let offset=0;
  for(const match of text.matchAll(/https?:\/\/[^\s<>"']+/gi)){
    let url=match[0].replace(/[.,;!?]+$/,'');
    // Keep balanced parentheses in URLs, but leave surrounding prose outside.
    while(url.endsWith(')')&&(url.match(/\)/g)?.length||0)>(url.match(/\(/g)?.length||0))url=url.slice(0,-1);
    fragment.append(document.createTextNode(text.slice(offset,match.index)));
    const anchor=document.createElement('a');anchor.textContent=url;fragment.append(link(anchor,url));offset=match.index+url.length;
  }
  fragment.append(document.createTextNode(text.slice(offset)));return fragment;
}
export function calendarDescription(text){
  const description=document.createElement('div');description.className='tooltip-description';
  // Angle-bracketed plain URLs are not HTML tags.
  if(!/<\/?[a-z][a-z0-9-]*(?:\s[^>]*|\/?)>/i.test(text)){description.append(linkedText(text));return meetingLinks(description);}
  const template=document.createElement('template');template.innerHTML=text;
  const root=template.content.ownerDocument.createElement('div');root.append(template.content);
  DOMPurify.sanitize(root,options);
  for(const anchor of root.querySelectorAll('a')){
    const href=anchor.getAttribute('href');let url;
    try{url=new URL(href);}catch{}
    if(url&&['https:','http:','mailto:','tel:'].includes(url.protocol))link(anchor,href);
    else anchor.removeAttribute('href');
  }
  // Autolink text outside existing anchors without reinterpreting sanitized DOM.
  const walker=root.ownerDocument.createTreeWalker(root,NodeFilter.SHOW_TEXT),texts=[];
  while(walker.nextNode())if(!walker.currentNode.parentElement.closest('a'))texts.push(walker.currentNode);
  for(const text of texts)text.replaceWith(linkedText(text.textContent));
  description.classList.add('tooltip-description-html');description.append(...root.childNodes);return meetingLinks(description);
}

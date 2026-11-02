import {t} from '../src/i18n.js';
import {highlightTree,classHighlighter,tags} from '@lezer/highlight';
import {EditorState,Compartment} from '@codemirror/state';
import {EditorView,keymap,lineNumbers,highlightActiveLine,drawSelection} from '@codemirror/view';
import {defaultKeymap,history,historyKeymap,indentWithTab} from '@codemirror/commands';
import {searchKeymap,highlightSelectionMatches,search,openSearchPanel} from '@codemirror/search';
import {markdown} from '@codemirror/lang-markdown';
import {javascript} from '@codemirror/lang-javascript';
import {python} from '@codemirror/lang-python';
import {json} from '@codemirror/lang-json';
import {sql} from '@codemirror/lang-sql';
import {syntaxHighlighting,HighlightStyle} from '@codemirror/language';
const languages=[{name:'javascript',alias:['js','typescript','ts'],support:javascript()},{name:'python',alias:['py'],support:python()},{name:'json',support:json()},{name:'sql',support:sql()}];
const language=name=>languages.find(value=>value.name===name||value.alias?.includes(name))?.support;
export function createSourceEditor({root,value,label,readOnly=false,changed,selection,uploadFiles}){
  let raw=value;const access=new Compartment(),numbers=new Compartment();
  const offset=(text,index)=>{let rawIndex=0,normalized=0;while(rawIndex<text.length&&normalized<index){if(text[rawIndex]==='\r'&&text[rawIndex+1]==='\n')rawIndex++;rawIndex++;normalized++;}return rawIndex;};
  const view=new EditorView({parent:root,state:EditorState.create({doc:value.replace(/\r\n?/g,'\n'),selection,
    extensions:[EditorState.phrases.of(Object.fromEntries(['Find','Replace','next','previous','all','match case','regexp','by word','replace','replace all','close','Search','Replace with','current match','replaced $ matches','replaced $ match'].map(value=>[value,t('markdown.cm.'+value)]))),EditorView.domEventHandlers({paste:(event,view)=>{const files=[...(event.clipboardData?.files||[])];if(readOnly||!files.length||!uploadFiles)return false;event.preventDefault();uploadFiles(files);return true;},drop:(event,view)=>{const files=[...(event.dataTransfer?.files||[])];if(readOnly||!files.length||!uploadFiles)return false;event.preventDefault();const at=view.posAtCoords({x:event.clientX,y:event.clientY});if(at!==null)view.dispatch({selection:{anchor:at}});uploadFiles(files);return true;}}),history(),drawSelection(),highlightActiveLine(),highlightSelectionMatches(),search(),markdown({codeLanguages:language}),syntaxHighlighting(HighlightStyle.define([{tag:tags.heading,color:'var(--bs-primary-text-emphasis)',fontWeight:'bold'},{tag:tags.strong,fontWeight:'bold'},{tag:tags.emphasis,fontStyle:'italic'},{tag:tags.keyword,color:'var(--bs-primary-text-emphasis)'},{tag:tags.string,color:'var(--bs-success-text-emphasis)'},{tag:tags.number,color:'var(--bs-danger-text-emphasis)'},{tag:tags.comment,color:'var(--bs-secondary-color)'},{tag:tags.link,color:'var(--bs-link-color)',textDecoration:'underline'}])),
      keymap.of([...defaultKeymap,...historyKeymap,...searchKeymap,indentWithTab]),EditorView.lineWrapping,numbers.of(lineNumbers()),
      access.of([EditorState.readOnly.of(readOnly),EditorView.editable.of(!readOnly)]),EditorView.contentAttributes.of({'aria-label':label}),
      EditorView.updateListener.of(update=>{if(!update.docChanged)return;const edits=[],eol=raw.match(/\r\n|\n|\r/)?.[0]||'\n';update.changes.iterChanges((from,to,_a,_b,insert)=>edits.push({from:offset(raw,from),to:offset(raw,to),insert:insert.toString().replace(/\n/g,eol)}));for(const edit of edits.reverse())raw=raw.slice(0,edit.from)+edit.insert+raw.slice(edit.to);changed(raw);})]
  })});
  return {value:()=>raw,focus:()=>view.focus(),find:()=>openSearchPanel(view),
    lineNumbers:enabled=>view.dispatch({effects:numbers.reconfigure(enabled?lineNumbers():[])}),
    readOnly:value=>{readOnly=value;view.dispatch({effects:access.reconfigure([EditorState.readOnly.of(value),EditorView.editable.of(!value)])});},
    position:()=>({anchor:view.state.selection.main.anchor,head:view.state.selection.main.head,scrollTop:view.scrollDOM.scrollTop}),
    select:(anchor,head=anchor)=>{const size=view.state.doc.length;view.dispatch({selection:{anchor:Math.min(anchor,size),head:Math.min(head,size)},scrollIntoView:true});view.focus();},
    destroy:()=>view.destroy()};
}

// Shared with the rich editor's code decorations, loaded only for code content.
export function highlightCode(text,name){
  const support=language(String(name||'').toLowerCase());if(!support)return [];
  const spans=[];highlightTree(support.language.parser.parse(text),classHighlighter,(from,to,className)=>spans.push({from,to,className}));return spans;
}

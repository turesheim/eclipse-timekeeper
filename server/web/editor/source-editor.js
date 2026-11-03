import {autocompletion} from '@codemirror/autocomplete';
import {t} from '../src/i18n.js';
import {highlightTree,classHighlighter,tags} from '@lezer/highlight';
import {EditorState,Compartment,StateField,StateEffect} from '@codemirror/state';
import {EditorView,keymap,lineNumbers,highlightActiveLine,drawSelection} from '@codemirror/view';
import {defaultKeymap,history,historyKeymap,indentWithTab,invertedEffects,isolateHistory} from '@codemirror/commands';
import {searchKeymap,highlightSelectionMatches,search,openSearchPanel} from '@codemirror/search';
import {markdown,markdownLanguage} from '@codemirror/lang-markdown';
import {javascript} from '@codemirror/lang-javascript';
import {python} from '@codemirror/lang-python';
import {json} from '@codemirror/lang-json';
import {sql} from '@codemirror/lang-sql';
import {java} from '@codemirror/lang-java';
import {html} from '@codemirror/lang-html';
import {css} from '@codemirror/lang-css';
import {shell} from '@codemirror/legacy-modes/mode/shell';
import {yaml} from '@codemirror/legacy-modes/mode/yaml';
import {syntaxHighlighting,HighlightStyle,StreamLanguage} from '@codemirror/language';
const languages=[
  {name:'javascript',alias:['js','jsx'],language:javascript({jsx:true}).language},
  {name:'typescript',alias:['ts','tsx'],language:javascript({typescript:true,jsx:true}).language},
  {name:'java',language:java().language},{name:'python',alias:['py'],language:python().language},
  {name:'json',language:json().language},{name:'sql',language:sql().language},
  {name:'html',alias:['htm'],language:html().language},{name:'css',language:css().language},
  {name:'shell',alias:['bash','sh','zsh'],language:StreamLanguage.define(shell)},
  {name:'yaml',alias:['yml'],language:StreamLanguage.define(yaml)},{name:'markdown',alias:['md'],language:markdownLanguage}
];
const language=name=>{const key=String(name).trim().split(/\s+/)[0].toLowerCase();return languages.find(value=>value.name===key||value.alias?.includes(key))?.language||null;};
export function createSourceEditor({root,value,label,readOnly=false,changed,selection,uploadFiles,complete}){
  let raw=value;const access=new Compartment(),numbers=new Compartment();
  const preferred=value.match(/\r\n|\n|\r/)?.[0]||'\n',restoreEndings=StateEffect.define();
  const endings=StateField.define({create:()=>value.match(/\r\n|\n|\r/g)||[],update:(before,transaction)=>{
    const restored=transaction.effects.filter(effect=>effect.is(restoreEndings));if(restored.length)return restored.at(-1).value;
    if(!transaction.docChanged)return before;const edits=[];
    transaction.changes.iterChanges((from,to,_a,_b,insert)=>{const first=transaction.startState.doc.lineAt(from).number-1,last=transaction.startState.doc.lineAt(to).number-1;if(first!==last||insert.lines>1)edits.push({first,count:last-first,insert:Array(insert.lines-1).fill(preferred)});});
    if(!edits.length)return before;const next=[...before];for(const edit of edits.reverse())next.splice(edit.first,edit.count,...edit.insert);return next;
  }});
  const endingHistory=invertedEffects.of(transaction=>{let affected=false;transaction.changes.iterChanges((from,to,_a,_b,insert)=>{if(transaction.startState.doc.lineAt(from).number!==transaction.startState.doc.lineAt(to).number||insert.lines>1)affected=true;});return affected?[restoreEndings.of(transaction.startState.field(endings))]:[];});
  if(!globalThis.ShadowRoot||!('adoptedStyleSheets' in ShadowRoot.prototype))throw new Error('Constructed editor styles are unavailable');
  const surface=root.shadowRoot||root.attachShadow({mode:'open'});surface.replaceChildren();
  const theme=EditorView.theme({'&':{height:'100%',minHeight:'inherit',backgroundColor:'var(--bs-body-bg)',color:'var(--bs-body-color)'},'.cm-scroller':{fontFamily:'var(--bs-font-monospace)',fontSize:'.9rem',lineHeight:'1.6',overflow:'auto'},'.cm-gutters,.cm-panels':{backgroundColor:'var(--bs-tertiary-bg)',color:'var(--bs-body-color)',borderColor:'var(--bs-border-color)'},'.cm-activeLine,.cm-activeLineGutter':{backgroundColor:'var(--bs-primary-bg-subtle)'},'.cm-cursor':{borderLeftColor:'var(--bs-body-color)'},'.cm-search input':{maxWidth:'100%',backgroundColor:'var(--bs-body-bg)',color:'var(--bs-body-color)'},'.cm-tooltip':{backgroundColor:'var(--bs-body-bg)',color:'var(--bs-body-color)',borderColor:'var(--bs-border-color)'},'.cm-tooltip-autocomplete ul li[aria-selected]':{backgroundColor:'var(--bs-primary-bg-subtle)',color:'var(--bs-primary-text-emphasis)'},'&.cm-focused .cm-selectionBackground,.cm-selectionBackground,::selection':{backgroundColor:'var(--bs-primary-bg-subtle)'}});
  const view=new EditorView({parent:surface,state:EditorState.create({doc:value.replace(/\r\n?/g,'\n'),selection,
    extensions:[theme,endings,endingHistory,autocompletion({override:[async context=>{
      if(readOnly||!complete)return null;const match=context.matchBefore(/!?\[\[[^\]\n]*/);if(!match)return null;const embed=match.text.startsWith('!'),query=match.text.slice(embed?3:2);if(/[|#]/.test(query))return null;
      const entries=await complete(query,{assetsOnly:embed});if(readOnly)return null;return {from:match.from,to:context.pos,filter:false,options:entries.map(entry=>({label:entry.path,type:'text',apply:(view,_completion,from,to)=>{if(readOnly)return;const suffix=view.state.doc.sliceString(to,to+2)===']]'?2:0,text=(embed?'!':'')+'[['+entry.path.replace(/\.md$/i,'')+']]';view.dispatch({changes:{from,to:to+suffix,insert:text},selection:{anchor:from+text.length}});}}))};
    }]}),EditorState.phrases.of(Object.fromEntries(['Find','Replace','next','previous','all','match case','regexp','by word','replace','replace all','close','Search','Replace with','current match','replaced $ matches','replaced $ match','Completions'].map(value=>[value,t('markdown.cm.'+value)]))),EditorView.domEventHandlers({paste:(event,view)=>{const files=[...(event.clipboardData?.files||[])];if(readOnly||!files.length||!uploadFiles)return false;event.preventDefault();uploadFiles(files);return true;},drop:(event,view)=>{const files=[...(event.dataTransfer?.files||[])];if(readOnly||!files.length||!uploadFiles)return false;event.preventDefault();const at=view.posAtCoords({x:event.clientX,y:event.clientY});if(at!==null)view.dispatch({selection:{anchor:at}});uploadFiles(files);return true;}}),history(),drawSelection(),highlightActiveLine(),highlightSelectionMatches(),search(),markdown({base:markdownLanguage,codeLanguages:language}),syntaxHighlighting(HighlightStyle.define([{tag:tags.heading,color:'var(--bs-primary-text-emphasis)',fontWeight:'bold'},{tag:tags.strong,fontWeight:'bold'},{tag:tags.emphasis,fontStyle:'italic'},{tag:tags.keyword,color:'var(--bs-primary-text-emphasis)'},{tag:tags.string,color:'var(--bs-success-text-emphasis)'},{tag:tags.number,color:'var(--bs-danger-text-emphasis)'},{tag:tags.comment,color:'var(--bs-secondary-color)'},{tag:tags.link,color:'var(--bs-link-color)',textDecoration:'underline'}])),
      keymap.of([...defaultKeymap,...historyKeymap,...searchKeymap,indentWithTab]),EditorView.lineWrapping,numbers.of(lineNumbers()),
      access.of([EditorState.readOnly.of(readOnly),EditorView.editable.of(!readOnly)]),EditorView.contentAttributes.of({'aria-label':label,'aria-description':t('markdown.tab_help')}),
      EditorView.updateListener.of(update=>{if(!update.docChanged)return;const values=update.state.field(endings);let index=0;raw=update.state.doc.toString().replace(/\n/g,()=>values[index++]||preferred);changed(raw);})]
  })});
  let readyResolve;const ready=new Promise(resolve=>{readyResolve=resolve;view.requestMeasure({read:()=>null,write:()=>resolve()});});
  return {ready,scrollElement:view.scrollDOM,value:()=>raw,insert:(text,from=view.state.selection.main.from,to=view.state.selection.main.to)=>{const current=view.state.selection.main,follow=current.from===from&&current.to===to;view.dispatch({changes:{from,to,insert:text},...(follow?{selection:{anchor:from+text.length}}:{})});return from+text.length;},replaceValue:value=>{if(value===raw)return;view.dispatch({changes:{from:0,to:view.state.doc.length,insert:value.replace(/\r\n?/g,'\n')},effects:restoreEndings.of(value.match(/\r\n|\n|\r/g)||[]),annotations:isolateHistory.of('full')});},focus:()=>view.focus(),find:()=>openSearchPanel(view),
    lineNumbers:enabled=>view.dispatch({effects:numbers.reconfigure(enabled?lineNumbers():[])}),
    readOnly:value=>{if(readOnly===value)return;readOnly=value;view.dispatch({effects:access.reconfigure([EditorState.readOnly.of(value),EditorView.editable.of(!value)])});},
    position:()=>({anchor:view.state.selection.main.anchor,head:view.state.selection.main.head,scrollTop:view.scrollDOM.scrollTop}),
    select:(anchor,head=anchor)=>{const size=view.state.doc.length;view.dispatch({selection:{anchor:Math.min(anchor,size),head:Math.min(head,size)},scrollIntoView:true});view.focus();},
    destroy:()=>{readyResolve();view.destroy();}};
}

// Shared with the rich editor's code decorations, loaded only for code content.
export function highlightCode(text,name){
  const support=language(String(name||'').toLowerCase());if(!support)return [];
  const spans=[];highlightTree(support.parser.parse(text),classHighlighter,(from,to,className)=>spans.push({from,to,className}));return spans;
}

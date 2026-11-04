import {highlightTree,classHighlighter} from '@lezer/highlight';
import {StreamLanguage} from '@codemirror/language';
import {markdownLanguage} from '@codemirror/lang-markdown';
import {jsxLanguage,tsxLanguage} from '@codemirror/lang-javascript';
import {pythonLanguage} from '@codemirror/lang-python';
import {jsonLanguage} from '@codemirror/lang-json';
import {StandardSQL} from '@codemirror/lang-sql';
import {javaLanguage} from '@codemirror/lang-java';
import {htmlLanguage} from '@codemirror/lang-html';
import {cssLanguage} from '@codemirror/lang-css';
import {shell} from '@codemirror/legacy-modes/mode/shell';
import {yaml} from '@codemirror/legacy-modes/mode/yaml';
const languages=[
  {name:'javascript',alias:['js','jsx'],language:jsxLanguage},
  {name:'typescript',alias:['ts','tsx'],language:tsxLanguage},
  {name:'java',language:javaLanguage},{name:'python',alias:['py'],language:pythonLanguage},
  {name:'json',language:jsonLanguage},{name:'sql',language:StandardSQL.language},
  {name:'html',alias:['htm'],language:htmlLanguage},{name:'css',language:cssLanguage},
  {name:'shell',alias:['bash','sh','zsh'],language:StreamLanguage.define(shell)},
  {name:'yaml',alias:['yml'],language:StreamLanguage.define(yaml)},{name:'markdown',alias:['md'],language:markdownLanguage}
];
const language=name=>{const key=String(name).trim().split(/\s+/)[0].toLowerCase();return languages.find(value=>value.name===key||value.alias?.includes(key))?.language||null;};
// Shared with the rich editor's code decorations, loaded only for code content.
export function highlightCode(text,name){
  const support=language(String(name||'').toLowerCase());if(!support)return [];
  const spans=[];highlightTree(support.parser.parse(text),classHighlighter,(from,to,className)=>spans.push({from,to,className}));return spans;
}

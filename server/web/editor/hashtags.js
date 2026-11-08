import {$prose} from '@milkdown/kit/utils';
import {Plugin,PluginKey} from '@milkdown/kit/prose/state';
import {Decoration,DecorationSet} from '@milkdown/kit/prose/view';
import {hashtagRanges} from '../src/hashtags.js';
import {t} from '../src/i18n.js';

/** Decorations leave authored Markdown, marks, caret positions and history intact. */
export function hashtagPresentation(resources){
  const key=new PluginKey('timekeeper-hashtags');
  const build=doc=>{
    const decorations=[];
    doc.descendants((node,pos)=>{
      if(node.type.spec.code)return false;if(!node.isTextblock)return;
      let text='';node.forEach(child=>{text+=child.isText&&!child.marks.some(mark=>['inlineCode','link'].includes(mark.type.name))?child.text:' '.repeat(child.nodeSize);});
      for(const range of hashtagRanges(text))decorations.push(Decoration.inline(pos+1+range.from,pos+1+range.to,{class:'markdown-hashtag','data-hashtag':range.key,...(resources?.searchTag?{role:'link',tabindex:'0','aria-label':t('notes.search_tag',{tag:range.key}),title:t('notes.search_tag',{tag:range.key})}:{})}));
      return false;
    });return DecorationSet.create(doc,decorations);
  };
  const activate=event=>{const target=event.target?.closest?.('[data-hashtag]');if(!target||!resources?.searchTag)return false;event.preventDefault();resources.searchTag(target.dataset.hashtag,target);return true;};
  const keys=event=>{if(!event.isComposing&&['Enter',' '].includes(event.key)&&activate(event))event.stopPropagation();};
  const plugin=$prose(()=>new Plugin({key,state:{init:(_config,state)=>build(state.doc),apply:(transaction,previous)=>transaction.docChanged?build(transaction.doc):previous},props:{decorations:state=>key.getState(state)},view:view=>{
    // ProseMirror's editing key handler is disabled in a read-only view.
    // Links remain keyboard-operable there, including after a rejected save.
    view.dom.addEventListener('keydown',keys,true);return {destroy:()=>view.dom.removeEventListener('keydown',keys,true)};
  }}));
  return {plugin,click:event=>event.button===0&&!event.ctrlKey&&!event.metaKey&&activate(event)};
}

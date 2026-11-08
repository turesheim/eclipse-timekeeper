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
      for(const range of hashtagRanges(text))decorations.push(Decoration.inline(pos+1+range.from,pos+1+range.to,{class:'markdown-hashtag','data-hashtag':range.key,...(resources?.searchTag?{role:'link',tabindex:'0',title:t('notes.search_tag',{tag:range.key})}:{})}));
      return false;
    });return DecorationSet.create(doc,decorations);
  };
  const plugin=$prose(()=>new Plugin({key,state:{init:(_config,state)=>build(state.doc),apply:(transaction,previous)=>transaction.docChanged?build(transaction.doc):previous},props:{decorations:state=>key.getState(state)}}));
  const activate=event=>{const target=event.target?.closest?.('[data-hashtag]');if(!target||!resources?.searchTag)return false;event.preventDefault();resources.searchTag(target.dataset.hashtag,target);return true;};
  return {plugin,click:event=>event.button===0&&!event.ctrlKey&&!event.metaKey&&activate(event),keyDown:event=>!event.isComposing&&['Enter',' '].includes(event.key)&&activate(event)};
}

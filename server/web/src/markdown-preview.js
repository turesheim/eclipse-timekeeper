import {t} from './i18n.js';

/** Read-only split preview. Replacements never steal source focus or expose stale drafts. */
export function buildPreview(field,load){
  const f=field,ignored=new WeakMap();let version=0,progress=0,resize;
  const sourceScroll=()=>f.codeEditor?.scrollElement||f.text;
  const active=()=>!f.plain.hidden&&!f.splitHost.hidden;
  const fraction=node=>node.scrollTop/Math.max(1,node.scrollHeight-node.clientHeight);
  const move=(node,value)=>{const top=value*Math.max(0,node.scrollHeight-node.clientHeight);if(Math.abs(top-node.scrollTop)>1){ignored.set(node,top);node.scrollTop=top;}};
  const followPreview=()=>{if(active())move(f.splitHost,progress);};
  f.syncPreviewScroll=node=>{
    if(!active())return;const expected=ignored.get(node);ignored.delete(node);
    if(expected!==undefined&&Math.abs(expected-node.scrollTop)<2)return;
    progress=Math.max(0,Math.min(1,fraction(node)));
    move(node===f.splitHost?sourceScroll():f.splitHost,progress);
  };
  f.text.addEventListener('scroll',()=>f.syncPreviewScroll(f.text));
  f.splitHost.addEventListener('scroll',()=>f.syncPreviewScroll(f.splitHost));
  f.resetPreview=()=>{
    ++version;clearTimeout(f.previewTimer);resize?.disconnect();resize=null;progress=0;
    const previous=f.previewEditor;f.previewEditor=null;void previous?.destroy().catch(()=>{});f.splitHost.replaceChildren();
  };
  f.refreshPreview=()=>{
    const request=++version,generation=f.generation;clearTimeout(f.previewTimer);if(!active())return;
    const current=()=>request===version&&generation===f.generation&&active();
    f.previewTimer=setTimeout(async()=>{
      if(!current())return;let created;
      try{
        const source=f.rawBody();
        if(new TextEncoder().encode(source).length>200000)throw new Error(t('journal.too_large'));
        const module=await load();if(!current())return;
        const root=document.createElement('div');
        created=await module.createMarkdownEditor({root,value:source,label:t('markdown.preview'),taskLabel:text=>text,escape:()=>f.text.focus(),readOnly:true,resources:f.resources});
        if(!current()){await created?.destroy();return;}
        if(!created)throw new Error(t('markdown.source_only'));
        const previous=f.previewEditor;f.previewEditor=created;created=null;
        resize?.disconnect();f.splitHost.replaceChildren(root);followPreview();
        resize=new ResizeObserver(followPreview);resize.observe(root);
        void previous?.destroy().catch(()=>{});
      }catch(error){
        await created?.destroy();if(!current())return;
        const previous=f.previewEditor;f.previewEditor=null;resize?.disconnect();resize=null;
        const message=document.createElement('p');message.textContent=error.message||t('markdown.failed');f.splitHost.replaceChildren(message);void previous?.destroy().catch(()=>{});
      }
    },250);
  };
}

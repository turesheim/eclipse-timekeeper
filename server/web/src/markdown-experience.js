import {lineComparison} from './note-revisions.js';
import {t} from './i18n.js';
import {iconButton} from './icons.js';
import {headingAnchor} from './wiki-links.js';
const el=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
const action=(key,run,glyph)=>{const button=el('button',glyph?'':t(key));button.type='button';button.className='btn btn-outline-secondary';if(glyph)iconButton(button,glyph,t(key));button.addEventListener('mousedown',event=>event.preventDefault());button.addEventListener('click',run);return button;};
/** UI around the shared schema; all mutations still pass through its read-only guard. */
export function buildExperience(field,load,loadSource){
  const f=field;
  f.retryButton=action('ui.retry',()=>void f.retryUpload?.());f.retryButton.hidden=true;f.status.after(f.retryButton);
  f.sourceExtras=el('div');f.sourceExtras.className='markdown-source-tools';
  f.codeHost=el('div');f.codeHost.className='markdown-code-editor';f.codeHost.hidden=true;f.plain.append(f.codeHost);
  f.enhance=action('markdown.source_tools',async()=>{try{
    if(f.loading)return;if(f.codeEditor){f.setRaw(f.codeEditor.value());f.sourcePosition=f.codeEditor.position();f.codeEditor.destroy();f.codeEditor=null;f.codeHost.replaceChildren();f.codeHost.hidden=true;f.text.hidden=false;f.text.focus();f.enhance.setAttribute('aria-pressed','false');return;}
    const current=f.generation,module=await loadSource();if(current!==f.generation)return;
    f.codeEditor=module.createSourceEditor({root:f.codeHost,value:f.rawBody(),label:t('markdown.body'),readOnly:f.readOnly,uploadFiles:files=>void f.uploadFiles(files),selection:f.sourcePosition&&{anchor:f.sourcePosition.anchor,head:f.sourcePosition.head},changed:value=>{f.setRaw(value);f.onChange();f.refreshPreview();}});
    f.text.hidden=true;f.codeHost.hidden=false;f.codeEditor.focus();f.enhance.setAttribute('aria-pressed','true');
  }catch{f.status.textContent=t('markdown.failed');f.text.hidden=false;f.codeHost.hidden=true;}},'code-square');f.enhance.setAttribute('aria-pressed','false');
  f.find=action('markdown.find_replace',()=>{if(!f.codeEditor){f.enhance.click();setTimeout(()=>f.codeEditor?.find(),100);}else f.codeEditor.find();},'search');
  f.numbers=action('markdown.line_numbers',()=>{f.showNumbers=f.showNumbers===false;f.codeEditor?.lineNumbers(f.showNumbers);f.numbers.setAttribute('aria-pressed',String(f.showNumbers));},'list-ol');f.numbers.setAttribute('aria-pressed','true');
  f.split=action('markdown.split',()=>{f.splitHost.hidden=!f.splitHost.hidden;f.split.setAttribute('aria-pressed',String(!f.splitHost.hidden));f.plain.classList.toggle('markdown-split',!f.splitHost.hidden);f.refreshPreview();},'layout-split');f.split.setAttribute('aria-pressed','false');
  f.splitHost=el('div');f.splitHost.className='journal-formatted markdown-split-preview';f.splitHost.setAttribute('aria-label',t('markdown.preview'));f.splitHost.hidden=true;f.plain.append(f.splitHost);
  f.sourceExtras.append(f.enhance,f.find,f.numbers,f.split);f.toolbar.after(f.sourceExtras);
  f.refreshPreview=()=>{
    clearTimeout(f.previewTimer);if(f.splitHost.hidden)return;
    const generation=f.generation;f.previewTimer=setTimeout(async()=>{
      if(generation!==f.generation||f.splitHost.hidden)return;
      const source=f.rawBody();if(new TextEncoder().encode(source).length>200000)return;
      await f.previewEditor?.destroy();f.previewEditor=null;f.splitHost.replaceChildren();
      const module=await load();if(generation!==f.generation)return;
      const root=el('div');f.splitHost.append(root);const created=await module.createMarkdownEditor({root,value:source,label:t('markdown.preview'),taskLabel:text=>text,escape:()=>f.text.focus(),readOnly:true,resources:f.resources});
      if(generation!==f.generation){await created?.destroy();return;}f.previewEditor=created;
    },250);
  };
  f.assetDialog=el('dialog');f.assetDialog.className='app-dialog markdown-asset-dialog';f.assetDialog.setAttribute('aria-label',t('markdown.existing_asset'));
  const assetHeading=el('h2',t('markdown.existing_asset')),assetSearch=el('input');assetSearch.type='search';assetSearch.className='form-control';assetSearch.setAttribute('aria-label',t('notes.search'));const assetList=el('div');assetList.className='markdown-asset-list';const closeAsset=action('ui.close',()=>f.assetDialog.close());f.assetDialog.append(assetHeading,assetSearch,assetList,closeAsset);f.root.append(f.assetDialog);
  let assetGeneration=0;
  const listAssets=async()=>{const current=++assetGeneration,generation=f.generation;assetList.textContent=t('markdown.loading');try{const entries=await f.resources.complete(assetSearch.value,{assetsOnly:true});if(current!==assetGeneration||generation!==f.generation)return;assetList.replaceChildren();for(const entry of entries.filter(entry=>['ASSET','BINARY'].includes(entry.kind))){const pick=el('button',entry.path);pick.type='button';pick.className='btn btn-outline-secondary';pick.addEventListener('click',()=>{if(f.readOnly)return;const image=/\.(png|jpg|jpeg|gif|webp)$/i.test(entry.path);if(f.editor)f.editor.command(image?'wiki_embed':'wiki_link',{value:entry.path});else{const at=f.text.selectionStart;f.text.setRangeText((image?'!':'')+'[['+entry.path+']]',at,f.text.selectionEnd,'end');f.onChange();}f.assetDialog.close();});assetList.append(pick);}if(!assetList.children.length)assetList.textContent=t('notes.empty');}catch(error){assetList.textContent=error.message;}};
  f.assetPicker=action('markdown.existing_asset',()=>{if(f.readOnly||!f.resources?.complete)return;assetSearch.value='';f.assetDialog.showModal();void listAssets();assetSearch.focus();},'folder');f.toolbar.append(f.assetPicker);assetSearch.addEventListener('input',()=>void listAssets());
  f.imageDialog=el('dialog');f.imageDialog.className='app-dialog';f.imageDialog.setAttribute('aria-label',t('markdown.image_properties'));const imageTitle=el('h2',t('markdown.image_properties')),imageForm=el('form'),imageFields={};
  for(const name of ['alt','width','height']){const label=el('label',t('markdown.image_'+name)),input=el('input');input.className='form-control';input.type=name==='alt'?'text':'number';if(name!=='alt'){input.min='1';input.max='2000';input.step='1';}input.setAttribute('aria-label',t('markdown.image_'+name));label.append(input);imageFields[name]=input;imageForm.append(label);}
  const embedLabel=el('label'),embed=el('input');embed.type='checkbox';embedLabel.append(embed,document.createTextNode(' '+t('markdown.embed_sizing')));const imageHelp=el('p',t('markdown.image_sizing_help')),imageError=el('p');imageError.setAttribute('role','alert');const applyImage=el('button',t('ui.save'));applyImage.className='btn btn-primary';applyImage.type='submit';const closeImage=action('ui.cancel',()=>f.imageDialog.close());imageForm.append(embedLabel,imageHelp,imageError,applyImage,closeImage);f.imageDialog.append(imageTitle,imageForm);f.root.append(f.imageDialog);let imageDetails;
  const dimensions=()=>{for(const name of ['width','height'])imageFields[name].disabled=!embed.checked;imageFields.alt.disabled=embed.checked&&!!imageFields.width.value;};embed.addEventListener('change',dimensions);imageFields.width.addEventListener('input',dimensions);
  f.imageProperties=action('markdown.image_properties',()=>{imageDetails=f.editor?.imageDetails();if(f.readOnly||!imageDetails)return;imageError.textContent='';for(const name of ['alt','width','height'])imageFields[name].value=imageDetails[name]||'';embed.checked=!!imageDetails.embed;embed.disabled=!!imageDetails.embed||!f.resources?.canonicalAsset;dimensions();f.imageDialog.showModal();imageFields.alt.focus();},'image');f.toolbar.append(f.imageProperties);
  imageForm.addEventListener('submit',event=>{event.preventDefault();if(f.readOnly)return;const generation=f.generation;void(async()=>{try{const path=embed.checked&&!imageDetails.embed?await f.resources.canonicalAsset(imageDetails.path):imageDetails.path;if(generation!==f.generation)return;const payload={...Object.fromEntries(Object.entries(imageFields).map(([key,input])=>[key,input.value])),embed:embed.checked,path};if(!f.editor?.command('image_properties',payload))throw new Error(t('markdown.invalid_image'));f.imageDialog.close();}catch(error){imageError.textContent=error.message;}})();});
  f.historyDialog=el('dialog');f.historyDialog.className='app-dialog markdown-history-dialog';f.historyDialog.setAttribute('aria-label',t('markdown.revisions'));const historyTitle=el('h2',t('markdown.revisions')),historyList=el('div'),historyStatus=el('p'),diff=el('pre');historyList.className='markdown-revision-list';diff.className='markdown-revision-diff';diff.setAttribute('aria-label',t('markdown.comparison'));let chosen;
  const applyRevision=action('markdown.restore_draft',()=>{if(f.readOnly||!chosen)return;const text=chosen.text;f.historyDialog.close();f.setValue(text);f.onChange();});applyRevision.disabled=true;f.applyRevision=applyRevision;
  const closeHistory=action('ui.close',()=>f.historyDialog.close());f.historyDialog.append(historyTitle,el('p',t('markdown.revision_help')),historyList,historyStatus,diff,applyRevision,closeHistory);f.root.append(f.historyDialog);
  const showComparison=value=>{chosen=value;diff.textContent=lineComparison(f.value(),value.text);historyStatus.textContent=t('markdown.comparison_legend');applyRevision.disabled=f.readOnly;};
  f.historyButton=action('markdown.revisions',async()=>{if(!f.resources?.revisions)return;const generation=f.generation;historyList.replaceChildren();diff.textContent='';chosen=null;applyRevision.disabled=true;historyStatus.textContent=t('markdown.loading');f.historyDialog.showModal();try{
    if(f.resources.latest){const latest=action('markdown.server_version',async()=>{try{const value=await f.resources.latest();if(generation===f.generation)showComparison(value);}catch(error){historyStatus.textContent=error.message;}});historyList.append(latest);}
    const values=await f.resources.revisions();if(generation!==f.generation)return;historyStatus.textContent=values.length?'':t('markdown.no_revisions');
    for(const value of values){const pick=el('button',t('markdown.revision_item',{version:value.contentVersion,date:value.modifiedAt}));pick.type='button';pick.className='btn btn-outline-secondary';pick.addEventListener('click',async()=>{try{const revision=await f.resources.revision(value.id);if(generation===f.generation)showComparison(revision);}catch(error){historyStatus.textContent=error.message;}});historyList.append(pick);}
  }catch(error){if(generation===f.generation)historyStatus.textContent=error.message;}},'clock-history');f.toolbar.append(f.historyButton);
  f.outline=el('details');f.outline.className='markdown-outline';f.outline.append(el('summary',t('markdown.outline')));f.outlineItems=el('nav');f.outlineItems.setAttribute('aria-label',t('markdown.outline'));f.outline.append(f.outlineItems);f.metadata.after(f.outline);
  f.outline.addEventListener('toggle',()=>{if(f.outline.open)paintOutline();});
  const paintOutline=()=>{
    const headings=f.editor?.outline()||[...f.rawBody().matchAll(/^(#{1,6})\s+(.+)$/gm)].map(match=>({level:match[1].length,text:match[2],pos:match.index,anchor:headingAnchor(match[2])}));
    f.outlineItems.replaceChildren();for(const heading of headings){const button=action('markdown.outline',()=>{if(f.editor)f.editor.scrollTo(heading.pos);else if(f.codeEditor)f.codeEditor.select(heading.pos);else{f.text.focus();f.text.setSelectionRange(heading.pos,heading.pos);}});button.textContent=heading.text;button.style.paddingInlineStart=(heading.level-1)*.75+.5+'rem';button.title=heading.text;f.outlineItems.append(button);}
  };
  f.scrollToHeading=heading=>{if(!heading)return;const target=f.editor?.outline().find(item=>headingAnchor(item.text)===headingAnchor(heading));if(target)f.editor.scrollTo(target.pos);};
  f.overflow=action('markdown.more',()=>{const open=f.toolbar.dataset.expanded!=='true';f.toolbar.dataset.expanded=String(open);f.overflow.setAttribute('aria-expanded',String(open));},'three-dots');f.overflow.classList.add('markdown-overflow');f.overflow.setAttribute('aria-expanded','false');f.toolbar.append(f.overflow);
  f.structured=el('details');f.structured.className='markdown-structured';f.structured.append(el('summary',t('markdown.structure')));
  const structure=el('div');structure.className='markdown-structured-actions';
  for(const name of ['indent','outdent','row_before','row_after','column_before','column_after','delete_row','delete_column','row_up','row_down','column_left','column_right','align_left','align_center','align_right','delete_table'])structure.append(action('markdown.'+name,()=>{if(!f.readOnly)f.editor?.command(name);}));
  f.structured.append(structure);f.toolbar.after(f.structured);
  const popup=el('div');popup.className='markdown-context-menu';popup.hidden=true;popup.setAttribute('role','group');popup.setAttribute('aria-label',t('markdown.formatting'));f.root.append(popup);f.contextPopup=popup;
  for(const name of ['bold','italic','strike','link']){const contextAction=action('markdown.'+name,()=>{if(f.readOnly)return;if(name==='link'){const href=prompt(t('markdown.link_url'),f.editor?.linkHref()||'https://');if(href)f.editor?.command(name,href);}else f.editor?.command(name);});contextAction.setAttribute('aria-label',t('markdown.selection_action',{action:t('markdown.'+name)}));popup.append(contextAction);}
  const suggestions=el('div');suggestions.className='markdown-suggestions';suggestions.hidden=true;suggestions.setAttribute('role','listbox');suggestions.setAttribute('aria-label',t('markdown.suggestions'));f.root.append(suggestions);f.suggestions=suggestions;
  let completion=0,lastQuery;
  const place=(node,rect)=>{node.style.left=Math.max(8,Math.min(rect.left,innerWidth-280))+'px';node.style.top=Math.max(8,Math.min(rect.bottom+8,innerHeight-220))+'px';};
  f.updateExperience=()=>{
    if(f.outline.open)paintOutline();
    f.applyRevision.disabled=f.readOnly||!chosen;
    f.imageProperties.disabled=f.readOnly||!f.editor?.imageDetails();
    f.historyButton.disabled=!f.resources?.revisions;
    f.assetPicker.disabled=f.readOnly||!f.resources?.complete;
    f.structured.hidden=!f.editor||f.readOnly;
    for(const button of f.tools.children){const name=button.dataset.command;if(name)button.setAttribute('aria-pressed',String(!!f.editor?.active(name)));}
    popup.hidden=!f.editor||f.readOnly||!f.editor.selectedText();if(!popup.hidden)place(popup,f.editor.selectionRect()||{left:8,bottom:8});
    if(!f.editor||f.readOnly||f.editor.composing()){suggestions.hidden=true;popup.hidden=true;return;}
    const wiki=f.editor.wikiQuery(),slash=f.editor.slash(),query=wiki?'wiki:'+wiki.query:slash?'slash:'+slash.query:null;
    if(query===lastQuery)return;lastQuery=query;const current=++completion;suggestions.replaceChildren();suggestions.hidden=true;
    const add=(label,run)=>{const button=el('button',label);button.type='button';button.className='btn btn-light';button.setAttribute('role','option');button.addEventListener('mousedown',event=>event.preventDefault());button.addEventListener('click',()=>{if(f.readOnly)return;suggestions.hidden=true;run();});suggestions.append(button);};
    if(slash){for(const name of ['heading','list','ordered','tasks','quote','code_block','table','hr'])if(t('markdown.'+name).toLocaleLowerCase().includes(slash.query.toLocaleLowerCase()))add(t('markdown.'+name),()=>f.editor.replaceSlash(name));}
    if(wiki&&f.resources?.complete)void f.resources.complete(wiki.query).then(entries=>{if(current!==completion||f.readOnly)return;for(const entry of entries)add(entry.path,()=>f.editor.insertWiki(wiki,entry.path.replace(/\.md$/i,'')));suggestions.hidden=!suggestions.children.length;place(suggestions,f.editor.selectionRect()||{left:8,bottom:8});}).catch(()=>{});
    suggestions.hidden=!suggestions.children.length;if(!suggestions.hidden)place(suggestions,f.editor.selectionRect()||{left:8,bottom:8});
  };
  f.rich.addEventListener('compositionend',()=>queueMicrotask(()=>f.updateExperience()));
  f.rich.addEventListener('keydown',event=>{
    if(event.isComposing||suggestions.hidden)return;const buttons=[...suggestions.querySelectorAll('button')];if(event.key==='ArrowDown'){event.preventDefault();buttons[0]?.focus();}else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();suggestions.hidden=true;}
  });
  suggestions.addEventListener('keydown',event=>{const buttons=[...suggestions.querySelectorAll('button')],index=buttons.indexOf(document.activeElement);if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();buttons[(index+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length]?.focus();}if(event.key==='Escape'){event.preventDefault();suggestions.hidden=true;f.editor?.focus();}});
}

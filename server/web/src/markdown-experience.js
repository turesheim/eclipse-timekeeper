import {emojiSuggestions} from './emoji.js';
import {wikiReference} from './wiki-links.js';
import {revisionDiff} from './revision-diff.js';
import {t} from './i18n.js';
import {noteTitle} from './note-title.js';
import {iconButton} from './icons.js';
import {headingAnchor} from './wiki-links.js';
import {sourceBrackets} from './source-brackets.js';
import {buildActionsMenu} from './markdown-actions.js';
const el=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
const action=(key,run,glyph)=>{const button=el('button',glyph?'':t(key));button.type='button';button.className='btn btn-outline-secondary';if(glyph)iconButton(button,glyph,t(key));button.addEventListener('mousedown',event=>event.preventDefault());button.addEventListener('click',run);return button;};
/** UI around the shared schema; all mutations still pass through its read-only guard. */
export function buildExperience(field){
  const f=field;
  buildActionsMenu(f);
  f.retryButton=action('ui.retry',()=>void f.retryUpload?.());f.retryButton.hidden=true;f.status.after(f.retryButton);
  f.resourceTools=el('div');f.resourceTools.setAttribute('role','group');f.resourceTools.setAttribute('aria-label',t('markdown.note_tools'));f.addActionsGroup(f.resourceTools);
  f.assetDialog=el('dialog');f.assetDialog.className='app-dialog markdown-asset-dialog';f.assetDialog.setAttribute('aria-label',t('markdown.existing_asset'));
  const assetHeading=el('h2',t('markdown.existing_asset')),assetSearch=el('input');assetSearch.type='search';assetSearch.className='form-control';assetSearch.setAttribute('aria-label',t('notes.search'));const assetList=el('div');assetList.className='markdown-asset-list';const closeAsset=action('ui.close',()=>f.assetDialog.close());f.assetDialog.append(assetHeading,assetSearch,assetList,closeAsset);f.root.append(f.assetDialog);
  let assetGeneration=0;
  const listAssets=async()=>{const current=++assetGeneration,generation=f.generation;assetList.textContent=t('markdown.loading');try{const entries=await f.resources.complete(assetSearch.value,{assetsOnly:true});if(current!==assetGeneration||generation!==f.generation)return;assetList.replaceChildren();for(const entry of entries.filter(entry=>['ASSET','BINARY'].includes(entry.kind))){const pick=el('button',entry.path);pick.type='button';pick.className='btn btn-outline-secondary';pick.addEventListener('click',async()=>{if(f.readOnly)return;const generation=f.generation;const image=/\.(png|jpg|jpeg|gif|webp)$/i.test(entry.path);if(f.editor){if(wikiReference(entry.path))f.editor.command(image?'wiki_embed':'wiki_link',{value:entry.path});else{try{const path=await f.resources.assetReference(entry);if(generation!==f.generation)return;f.editor.command(image?'image':'asset_link',{path,label:entry.path.split('/').at(-1)});}catch(error){assetList.textContent=error.message;return;}}}else{let text=(image?'!':'')+'[['+entry.path+']]';if(!wikiReference(entry.path)){try{const path=await f.resources.assetReference(entry);if(generation!==f.generation||f.readOnly)return;text=(image?'!':'')+'['+entry.path.split('/').at(-1).replace(/[\[\]\\]/g,' ')+']('+path+')';}catch(error){assetList.textContent=error.message;return;}}const selection={anchor:f.text.selectionStart,head:f.text.selectionEnd};f.insertSource(text,selection.anchor,selection.head);}f.assetDialog.close();f.focus();});assetList.append(pick);}if(!assetList.children.length)assetList.textContent=t('notes.empty');}catch(error){assetList.textContent=error.message;}};
  f.assetPicker=action('markdown.existing_asset',()=>{if(f.readOnly||!f.resources?.complete)return;assetSearch.value='';f.assetDialog.showModal();void listAssets();assetSearch.focus();},'folder');f.resourceTools.append(f.assetPicker);assetSearch.addEventListener('input',()=>void listAssets());
  f.imageDialog=el('dialog');f.imageDialog.className='app-dialog';f.imageDialog.setAttribute('aria-label',t('markdown.image_properties'));const imageTitle=el('h2',t('markdown.image_properties')),imageForm=el('form'),imageFields={};
  for(const name of ['alt','width','height']){const label=el('label',t('markdown.image_'+name)),input=el('input');input.className='form-control';input.type=name==='alt'?'text':'number';if(name!=='alt'){input.min='1';input.max='2000';input.step='1';}input.setAttribute('aria-label',t('markdown.image_'+name));label.append(input);imageFields[name]=input;imageForm.append(label);}
  const embedLabel=el('label'),embed=el('input');embed.type='checkbox';embedLabel.append(embed,document.createTextNode(' '+t('markdown.embed_sizing')));const imageHelp=el('p',t('markdown.image_sizing_help')),imageError=el('p');imageError.setAttribute('role','alert');const applyImage=el('button',t('ui.save'));applyImage.className='btn btn-primary';applyImage.type='submit';const closeImage=action('ui.cancel',()=>f.imageDialog.close());imageForm.append(embedLabel,imageHelp,imageError,applyImage,closeImage);f.imageDialog.append(imageTitle,imageForm);f.root.append(f.imageDialog);let imageDetails;
  const dimensions=()=>{for(const name of ['width','height'])imageFields[name].disabled=!embed.checked;imageFields.alt.disabled=embed.checked&&!!imageFields.width.value;};embed.addEventListener('change',dimensions);imageFields.width.addEventListener('input',dimensions);
  f.imageProperties=action('markdown.image_properties',()=>{imageDetails=f.editor?.imageDetails();if(f.readOnly||!imageDetails)return;imageError.textContent='';for(const name of ['alt','width','height'])imageFields[name].value=imageDetails[name]||'';embed.checked=!!imageDetails.embed;embed.disabled=!!imageDetails.embed||!f.resources?.canonicalAsset;dimensions();f.imageDialog.showModal();imageFields.alt.focus();},'image');f.resourceTools.append(f.imageProperties);
  imageForm.addEventListener('submit',event=>{event.preventDefault();if(f.readOnly)return;const generation=f.generation;void(async()=>{try{const path=embed.checked&&!imageDetails.embed?await f.resources.canonicalAsset(imageDetails.path):imageDetails.path;if(generation!==f.generation)return;const payload={...Object.fromEntries(Object.entries(imageFields).map(([key,input])=>[key,input.value])),embed:embed.checked,path};if(!f.editor?.command('image_properties',payload))throw new Error(t('markdown.invalid_image'));f.imageDialog.close();}catch(error){imageError.textContent=error.message;}})();});
  f.historyDialog=el('dialog');f.historyDialog.className='app-dialog markdown-history-dialog';f.historyDialog.setAttribute('aria-label',t('markdown.revisions'));
  const historyTitle=el('h2',t('markdown.revisions')),historyList=el('div'),historyStatus=el('p'),diff=el('pre');historyList.className='markdown-revision-list';historyStatus.setAttribute('role','status');diff.className='markdown-revision-diff';diff.tabIndex=0;diff.setAttribute('role','region');diff.setAttribute('aria-label',t('markdown.comparison'));
  let chosen,historyBusy=false,historyGeneration=0,selectionGeneration=0,historyCurrent=()=>false;
  const applyRevision=action('markdown.restore_draft',()=>{if(f.readOnly||historyBusy||!chosen)return;const text=chosen.text;f.historyDialog.close();f.setValue(text);f.onChange();});applyRevision.disabled=true;f.applyRevision=applyRevision;
  const closeHistory=action('ui.close',()=>f.historyDialog.close());
  f.deleteRevisionButton=action('markdown.delete_revision',async()=>{
    if(f.readOnly||historyBusy||!chosen?.id||!chosen.etag||!historyCurrent()||!confirm(t('markdown.delete_revision_confirm')))return;
    const selected=chosen,valid=historyCurrent;historyBusy=true;f.updateExperience();
    try{
      await f.resources.deleteRevision(selected.id,selected.etag);if(!valid())return;
      [...historyList.querySelectorAll('[data-revision-id]')].find(button=>button.dataset.revisionId===selected.id)?.remove();chosen=null;diff.replaceChildren();historyStatus.textContent=t('markdown.revision_deleted');closeHistory.focus();
    }catch(error){if(valid()){historyStatus.textContent=error.uncertain?t('markdown.revision_delete_uncertain'):error.message;if(error.uncertain)chosen=null;}}
    finally{if(valid()){historyBusy=false;f.updateExperience();}}
  });f.deleteRevisionButton.disabled=true;
  const historyActions=el('div');historyActions.className='dialog-actions';historyActions.append(applyRevision,f.deleteRevisionButton,closeHistory);
  f.historyDialog.append(historyTitle,el('p',t('markdown.revision_help')),historyList,historyStatus,diff,historyActions);f.root.append(f.historyDialog);
  f.historyDialog.addEventListener('close',()=>{if(!f.historyDialog.open){++historyGeneration;++selectionGeneration;}});
  const showComparison=(value,button)=>{
    chosen=value;diff.replaceChildren();const comparison=revisionDiff(f.value(),value.text);
    for(const hunk of comparison.hunks){
      const heading=el('span',`@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@`);heading.className='markdown-diff-hunk';diff.append(heading);
      for(const row of hunk.rows){const line=el('span');line.className='markdown-diff-'+row.kind;line.dataset.diff=row.kind;
        const marker=el('span',row.kind==='added'?'+ ':row.kind==='removed'?'− ':'  ');marker.setAttribute('aria-hidden','true');line.append(marker);
        if(row.kind!=='context'){const label=el('span',t('markdown.diff_'+row.kind)+': ');label.className='visually-hidden';line.append(label);}line.append(document.createTextNode(row.text));diff.append(line);
      }
    }
    if(!comparison.hunks.length)diff.textContent=t('markdown.no_differences');
    diff.scrollTop=0;historyStatus.textContent=comparison.truncated?t('markdown.diff_truncated'):t('markdown.comparison_legend');
    for(const choice of historyList.children)choice.setAttribute('aria-pressed',String(choice===button));f.updateExperience();
  };
  f.historyButton=action('markdown.revisions',async()=>{
    if(!f.resources?.revisions)return;
    const generation=f.generation,context=++historyGeneration;historyCurrent=()=>generation===f.generation&&context===historyGeneration&&f.historyDialog.open;const valid=historyCurrent;
    historyBusy=false;chosen=null;historyList.replaceChildren();diff.replaceChildren();f.applyRevision.disabled=f.deleteRevisionButton.disabled=true;historyStatus.textContent=t('markdown.loading');f.historyDialog.showModal();
    const choice=(label,load,id)=>{
      const button=el('button',label);button.type='button';button.className='btn btn-outline-secondary';button.setAttribute('aria-pressed','false');if(id)button.dataset.revisionId=id;
      button.addEventListener('click',async()=>{if(historyBusy)return;const request=++selectionGeneration;chosen=null;f.updateExperience();try{const value=await load();if(valid()&&request===selectionGeneration)showComparison(value,button);}catch(error){if(valid()&&request===selectionGeneration)historyStatus.textContent=error.message;}});historyList.append(button);
    };
    if(f.resources.latest)choice(t('markdown.server_version'),()=>f.resources.latest());
    try{const values=await f.resources.revisions();if(!valid())return;historyStatus.textContent=values.length?'':t('markdown.no_revisions');
      for(const value of values)choice(t('markdown.revision_item',{version:value.contentVersion,date:value.modifiedAt}),()=>f.resources.revision(value.id),value.id);
    }catch(error){if(valid())historyStatus.textContent=error.message;}
  },'clock-history');f.resourceTools.append(f.historyButton);
  f.scrollToHeading=heading=>{if(!heading)return;const target=f.editor?.outline().find(item=>headingAnchor(item.text)===headingAnchor(heading));if(target){f.editor.scrollTo(target.pos);return true;}f.status.textContent=t('markdown.heading_missing',{heading});return false;};
  const suggestions=el('div');suggestions.className='markdown-suggestions';suggestions.hidden=true;suggestions.setAttribute('role','listbox');suggestions.setAttribute('aria-label',t('markdown.suggestions'));f.root.append(suggestions);f.suggestions=suggestions;
  let completion=0,lastQuery;
  const source=sourceBrackets(f);
  f.dismissSuggestions=()=>{++completion;lastQuery=null;suggestions.hidden=true;suggestions.replaceChildren();};
  f.resetCompletions=()=>{source.reset();f.dismissSuggestions();};
  const context=()=>{
    if(f.readOnly||f.loading||f.editor?.composing())return null;
    const active=document.activeElement,inside=suggestions.contains(active);
    const rich=f.editor&&(inside||f.rich.contains(active)),plain=!f.editor&&!f.plain.hidden&&(inside||active===f.text);
    const wiki=rich?f.editor.wikiQuery():plain?source.query():null,slash=rich&&!wiki?f.editor.slash():null,emoji=rich&&!wiki&&!slash?f.editor.emojiQuery():null;
    const range=wiki||slash||emoji;if(!range)return null;
    return {wiki,slash,emoji,rich,key:[f.generation,rich?'rich':'source',wiki?'wiki':slash?'slash':'emoji',range.from,range.to,range.query,!!wiki?.embed].join('|')};
  };
  const place=()=>{
    const rich=f.editor?.selectionRect(),native=!f.editor?f.caret?.sourceRect(f.text):null,rect=rich||(native?{left:native.left,top:native.top,bottom:native.top+native.height}:null);if(!rect)return;
    const width=suggestions.offsetWidth,height=suggestions.offsetHeight;
    suggestions.style.left=Math.max(8,Math.min(rect.left,innerWidth-width-8))+'px';
    suggestions.style.top=Math.max(8,Math.min(rect.bottom+8+height<=innerHeight-8?rect.bottom+8:rect.top-height-8,innerHeight-height-8))+'px';
  };
  f.updateExperience=()=>{
    for(const button of historyList.children)button.disabled=historyBusy;
    f.applyRevision.disabled=f.readOnly||!chosen||historyBusy;
    f.deleteRevisionButton.disabled=f.readOnly||historyBusy||!chosen?.id||!chosen.etag||!f.resources?.deleteRevision;
    f.imageProperties.disabled=f.readOnly||!f.editor?.imageDetails();
    f.historyButton.disabled=!f.resources?.revisions;
    f.assetPicker.disabled=f.readOnly||!f.resources?.complete;
    f.syncActions();
    for(const button of f.tools.children){const name=button.dataset.command;if(name)button.setAttribute('aria-pressed',String(!!f.editor?.active(name)));}
    const tableKeys=!!f.editor?.context().table&&!f.readOnly&&!f.loading;
    if(f.tableButton.dataset.tableKeys!==String(tableKeys)){
      f.tableButton.dataset.tableKeys=String(tableKeys);f.tableButton.title=t(tableKeys?'markdown.table_keys':'markdown.table');
      if(tableKeys){f.tableButton.setAttribute('aria-description',t('markdown.table_keys'));f.tableButton.setAttribute('aria-keyshortcuts','ArrowUp ArrowDown ArrowLeft ArrowRight Shift+ArrowUp Shift+ArrowDown Shift+ArrowLeft Shift+ArrowRight Backspace Delete Shift+Delete L C R');}
      else{f.tableButton.removeAttribute('aria-description');f.tableButton.removeAttribute('aria-keyshortcuts');}
    }
    const state=context();if(!state){if(lastQuery!==null)f.dismissSuggestions();return;}
    if(state.key===lastQuery){if(!suggestions.hidden)place();return;}lastQuery=state.key;const current=++completion;suggestions.replaceChildren();suggestions.hidden=true;
    const valid=()=>current===completion&&context()?.key===state.key;
    const add=(label,run)=>{
      const button=el('button',label);button.type='button';button.className='btn btn-light';button.setAttribute('role','option');button.setAttribute('aria-selected','false');button.addEventListener('mousedown',event=>event.preventDefault());
      button.addEventListener('focus',()=>{for(const choice of suggestions.children)choice.setAttribute('aria-selected',String(choice===button));});
      button.addEventListener('click',()=>{if(!valid())return;f.dismissSuggestions();run();});suggestions.append(button);
    };
    const {wiki,slash,emoji}=state;
    if(emoji)for(const {name,emoji:character}of emojiSuggestions(emoji.query))add(character+' :'+name+':',()=>f.editor.insertEmoji(emoji,name));
    if(slash)for(const name of ['heading','list','ordered','tasks','quote','code_block','table','hr'])if(t('markdown.'+name).toLocaleLowerCase().includes(slash.query.toLocaleLowerCase()))add(t('markdown.'+name),()=>f.editor.replaceSlash(name));
    if(wiki&&f.resources?.complete)void f.resources.complete(wiki.query,{assetsOnly:wiki.embed}).then(entries=>{
      if(!valid())return;for(const entry of entries)add(entry.title?noteTitle(entry)+' · '+entry.path:entry.path,()=>{const path=entry.path.replace(/\.md$/i,'');if(state.rich)f.editor.insertWiki(wiki,path);else source.insert(wiki,path);});
      suggestions.hidden=!suggestions.children.length;if(!suggestions.hidden)place();
    }).catch(()=>{});
    suggestions.hidden=!suggestions.children.length;if(!suggestions.hidden)place();
  };
  f.rich.addEventListener('compositionend',()=>queueMicrotask(()=>f.updateExperience()));
  const editorKeys=event=>{
    if(event.isComposing||suggestions.hidden)return;
    if(event.key==='ArrowDown'){event.preventDefault();event.stopPropagation();suggestions.querySelector('button')?.focus();}
    else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();f.dismissSuggestions();if(!f.editor)f.escapeTab=true;f.focus();}
  };
  f.rich.addEventListener('keydown',editorKeys,true);f.text.addEventListener('keydown',editorKeys,true);
  suggestions.addEventListener('focusout',()=>queueMicrotask(()=>f.updateExperience()));
  suggestions.addEventListener('keydown',event=>{
    const buttons=[...suggestions.querySelectorAll('button')],index=buttons.indexOf(document.activeElement);
    if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();event.stopPropagation();buttons[(index+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length]?.focus();}
    if(event.key==='Escape'){event.preventDefault();event.stopPropagation();f.dismissSuggestions();if(!f.editor)f.escapeTab=true;f.focus();}
  });
}

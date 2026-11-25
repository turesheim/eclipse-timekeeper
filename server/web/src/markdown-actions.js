import {t} from './i18n.js';
import {iconButton} from './icons.js';

/** One keyboard-accessible menu for note resources and document actions. */
export function buildActionsMenu(field){
  const f=field,menu=document.createElement('div'),trigger=document.createElement('button'),bound=new WeakSet();
  trigger.type='button';trigger.className='btn btn-outline-secondary markdown-actions-trigger';iconButton(trigger,'three-dots',t('markdown.note_tools'));
  menu.className='dropdown-menu markdown-actions-menu shadow';menu.id='markdown-actions-'+crypto.randomUUID();menu.popover='auto';menu.setAttribute('role','menu');menu.setAttribute('aria-label',t('markdown.note_tools'));
  trigger.setAttribute('aria-haspopup','menu');trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls',menu.id);
  f.actionsTrigger=trigger;f.actionsMenu=menu;f.toolbar.append(trigger);f.root.append(menu);
  const choices=()=>[...menu.querySelectorAll('button')].filter(button=>!button.disabled&&!button.closest('[hidden]'));
  const position=()=>{
    if(!menu.matches(':popover-open'))return;
    const viewport=f.compactTools?.matches?window.visualViewport:null;
    const left=viewport?.offsetLeft??0,top=viewport?.offsetTop??0,right=left+(viewport?.width??innerWidth),bottom=top+(viewport?.height??innerHeight);
    menu.style.maxHeight=viewport?`min(30rem, ${Math.max(0,viewport.height-16)}px)`:'';
    const rect=trigger.getBoundingClientRect(),height=menu.offsetHeight;
    menu.style.left=Math.max(left+8,Math.min(rect.right-menu.offsetWidth,right-menu.offsetWidth-8))+'px';
    menu.style.top=Math.max(top+8,Math.min(rect.bottom+4+height<=bottom-8?rect.bottom+4:rect.top-height-4,bottom-height-8))+'px';
  };
  f.closeActions=()=>{if(menu.matches(':popover-open'))menu.hidePopover();};
  f.syncActions=()=>{
    f.syncCompactActions?.();
    for(const button of menu.querySelectorAll('button'))if(!bound.has(button)){
      bound.add(button);const label=button.getAttribute('aria-label')||button.title;
      if(label){const text=document.createElement('span');text.textContent=label;button.append(text);}
      // The menu already exposes the full label. A second tooltip would consume
      // Escape before it reaches the menu's keyboard handler.
      button.removeAttribute('title');delete button.dataset.tkTooltip;delete button.dataset.tkTooltipComposite;
      button.className='dropdown-item';button.setAttribute('role','menuitem');button.tabIndex=-1;
      button.addEventListener('click',()=>f.closeActions(),{capture:true});
    }
    trigger.disabled=f.loading||!choices().length;if(trigger.disabled)f.closeActions();
  };
  f.addActionsGroup=group=>{group.className='markdown-actions-group';menu.append(group);f.syncActions();};
  const open=(last=false)=>{f.prepareCompactActions?.();f.syncActions();if(trigger.disabled)return;f.closeBlockMenu();f.closeListMenu();if(!menu.matches(':popover-open'))menu.showPopover();position();const items=choices();items[last?items.length-1:0]?.focus();};
  trigger.addEventListener('mousedown',event=>event.preventDefault());trigger.addEventListener('click',()=>menu.matches(':popover-open')?f.closeActions():open());
  trigger.addEventListener('pointerdown',()=>{if(!menu.matches(':popover-open'))f.captureCompactSelection?.();});
  trigger.addEventListener('pointercancel',()=>f.discardCompactPress?.());
  trigger.addEventListener('keydown',event=>{
    if(['Enter',' ','ArrowDown','ArrowUp'].includes(event.key))f.discardCompactPress?.();
    if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();open(event.key==='ArrowUp');}
  });
  menu.addEventListener('beforetoggle',event=>{
    const opened=event.newState==='open',listen=opened?'addEventListener':'removeEventListener';
    trigger.setAttribute('aria-expanded',String(opened));
    window[listen]('resize',position);document[listen]('scroll',position,true);
    if(f.compactTools){window.visualViewport?.[listen]('resize',position);window.visualViewport?.[listen]('scroll',position);}
  });
  menu.addEventListener('keydown',event=>{
    const items=choices(),index=items.indexOf(document.activeElement);
    if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();items[event.key==='Home'?0:event.key==='End'?items.length-1:(index+(event.key==='ArrowDown'?1:-1)+items.length)%items.length]?.focus();}
    else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();f.closeActions();trigger.focus();}
    else if(event.key==='Tab'){f.closeActions();trigger.focus();}
  });
}

/** Move the existing note menu into Today’s phone header and add basic editing choices. */
export function buildCompactActions(f,media,host){
  f.compactTools=media;
  const group=document.createElement('div');group.className='markdown-actions-group';
  const choice=(key,glyph,run)=>{
    const button=document.createElement('button');button.type='button';iconButton(button,glyph,t('markdown.'+key));
    button.addEventListener('click',run);group.append(button);return button;
  };
  const marks=[
    choice('bold','type-bold',()=>applyMark('bold')),
    choice('italic','type-italic',()=>applyMark('italic'))
  ];
  const source=choice('source','markdown',()=>void f.showSource());
  const formatted=choice('formatted','file-richtext',()=>void f.showFormatted());
  f.actionsMenu.prepend(group);
  let pressed,selection;
  const release=target=>{if(target?.bookmark)target.editor.release(target.bookmark);};
  const capture=()=>{
    const editor=f.editor,fragmentFocus=!!document.activeElement?.closest('.markdown-source-fragment');
    if(!editor||fragmentFocus)return {editor,fragmentFocus};
    editor.syncSelection();
    return {editor,fragmentFocus,bookmark:editor.bookmark()};
  };
  const applyMark=name=>{
    if(selection?.bookmark&&selection.editor===f.editor){
      selection.editor.insertAt(selection.bookmark,name);
      selection.editor.focus();
    }
  };
  // Touch can change the live selection after pointerdown, before the menu
  // receives its click. Use the mapped range captured at the initial press.
  f.captureCompactSelection=()=>{
    if(!media.matches)return;
    release(pressed);
    pressed=capture();
  };
  f.discardCompactPress=()=>{release(pressed);pressed=null;};
  f.prepareCompactActions=()=>{
    if(!media.matches)return;
    release(selection);
    selection=pressed??capture();
    pressed=null;
  };
  f.actionsMenu.addEventListener('toggle',event=>{
    if(event.newState==='closed'){release(selection);selection=null;}
  });
  f.syncCompactActions=()=>{
    group.hidden=!media.matches;
    const rich=!!f.editor;
    for(const mark of marks)mark.hidden=!rich||f.readOnly||f.bannerBusy||selection?.fragmentFocus;
    source.hidden=!rich;formatted.hidden=rich;
  };
  const apply=()=>{
    const {actionsTrigger:trigger,actionsMenu:menu,toolbar}=f;
    const focused=trigger===document.activeElement||menu.contains(document.activeElement);
    f.closeActions();f.closeBlockMenu();f.closeListMenu();f.closeCalloutMenu();
    f.discardCompactPress();
    toolbar.hidden=media.matches;
    (media.matches?host:toolbar).append(trigger);
    f.syncActions();
    if(focused)trigger.focus({preventScroll:true});
  };
  media.addEventListener('change',apply);apply();
}

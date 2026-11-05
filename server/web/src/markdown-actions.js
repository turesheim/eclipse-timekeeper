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
  const position=()=>{if(!menu.matches(':popover-open'))return;const rect=trigger.getBoundingClientRect();menu.style.left=Math.max(8,Math.min(rect.right-menu.offsetWidth,innerWidth-menu.offsetWidth-8))+'px';menu.style.top=Math.max(8,Math.min(rect.bottom+4+menu.offsetHeight<=innerHeight-8?rect.bottom+4:rect.top-menu.offsetHeight-4,innerHeight-menu.offsetHeight-8))+'px';};
  f.closeActions=()=>{if(menu.matches(':popover-open'))menu.hidePopover();};
  f.syncActions=()=>{
    for(const button of menu.querySelectorAll('button'))if(!bound.has(button)){
      bound.add(button);const label=button.getAttribute('aria-label')||button.title;
      if(label){const text=document.createElement('span');text.textContent=label;button.append(text);}
      button.className='dropdown-item';button.setAttribute('role','menuitem');button.tabIndex=-1;
      button.addEventListener('click',()=>f.closeActions(),{capture:true});
    }
    trigger.disabled=f.loading||!choices().length;if(trigger.disabled)f.closeActions();
  };
  f.addActionsGroup=group=>{group.className='markdown-actions-group';menu.append(group);f.syncActions();};
  const open=(last=false)=>{f.syncActions();if(trigger.disabled)return;f.closeBlockMenu();if(!menu.matches(':popover-open'))menu.showPopover();position();const items=choices();items[last?items.length-1:0]?.focus();};
  trigger.addEventListener('mousedown',event=>event.preventDefault());trigger.addEventListener('click',()=>menu.matches(':popover-open')?f.closeActions():open());
  trigger.addEventListener('keydown',event=>{if(['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();open(event.key==='ArrowUp');}});
  menu.addEventListener('beforetoggle',event=>{const opened=event.newState==='open';trigger.setAttribute('aria-expanded',String(opened));for(const [target,type,options]of [[window,'resize',false],[document,'scroll',true]])target[opened?'addEventListener':'removeEventListener'](type,position,options);});
  menu.addEventListener('keydown',event=>{
    const items=choices(),index=items.indexOf(document.activeElement);
    if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();items[event.key==='Home'?0:event.key==='End'?items.length-1:(index+(event.key==='ArrowDown'?1:-1)+items.length)%items.length]?.focus();}
    else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();f.closeActions();trigger.focus();}
    else if(event.key==='Tab'){f.closeActions();trigger.focus();}
  });
}

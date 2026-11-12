import {iconButton} from './icons.js';
import {t} from './i18n.js';

/** Main actions expose only the sidebars supplied by the current view. */
export class SidebarActions {
  constructor(host,layout,panes,changed=()=>{}){
    Object.assign(this,{layout,changed,items:[],narrow:matchMedia('(max-width:1050px)')});
    const group=document.createElement('div');group.className='btn-group sidebar-actions';group.setAttribute('role','group');group.setAttribute('aria-label',t('layout.sidebar_actions'));
    for(const side of ['left','right']){
      const spec=panes[side];layout.classList.toggle('sidebar-'+side+'-collapsed',!spec?.panel);
      if(!spec?.panel){if(spec?.separator)spec.separator.hidden=true;continue;}
      const button=document.createElement('button');button.type='button';button.id=host.id+'-toggle-'+side+'-sidebar';
      spec.panel.id||=host.id+'-'+side+'-sidebar';button.setAttribute('aria-controls',spec.panel.id);
      const item={...spec,side,button,media:spec.breakpoint?matchMedia(`(max-width:${spec.breakpoint}px)`):this.narrow};this.items.push(item);group.append(button);
      button.addEventListener('click',()=>this.setExpanded(side,!item[item.media.matches?'narrow':'wide']));
    }
    if(this.items.length)host.prepend(group);
    for(const media of new Set(this.items.map(item=>item.media)))media.addEventListener('change',()=>this.paint());
    document.addEventListener('focusin',event=>{for(const item of this.items)item.focused=item.panel.contains(event.target)||item.separator===event.target;});
    this.reset();
  }
  setExpanded(side,expanded){const item=this.items.find(item=>item.side===side);if(item){item[item.media.matches?'narrow':'wide']=expanded;this.paint();}}
  reset(){for(const item of this.items){item.wide=true;item.narrow=!item.collapseOnNarrow;}this.paint();}
  paint(){
    for(const item of this.items){
      const expanded=item[item.media.matches?'narrow':'wide'];
      if(!expanded&&(item.panel.contains(document.activeElement)||item.separator===document.activeElement||item.focused&&document.activeElement===document.body))item.button.focus({preventScroll:true});
      item.panel.hidden=!expanded;if(item.separator)item.separator.hidden=!expanded;
      this.layout.classList.toggle('sidebar-'+item.side+'-collapsed',!expanded);
      item.button.setAttribute('aria-expanded',String(expanded));item.button.classList.toggle('active',expanded);
      iconButton(item.button,item.side==='left'?'layout-sidebar':'layout-sidebar-reverse',t('layout.'+(expanded?'collapse':'expand')+'_'+item.side+'_sidebar'));
    }
    this.changed();
  }
}

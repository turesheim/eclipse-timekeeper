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
      const item={...spec,side,button};this.items.push(item);group.append(button);
      button.addEventListener('click',()=>{const mode=this.narrow.matches?'narrow':'wide';item[mode]=!item[mode];this.paint();});
    }
    if(this.items.length)host.prepend(group);
    this.narrow.addEventListener('change',()=>this.paint());this.reset();
  }
  reset(){for(const item of this.items){item.wide=true;item.narrow=!item.collapseOnNarrow;}this.paint();}
  paint(){
    for(const item of this.items){
      const expanded=item[this.narrow.matches?'narrow':'wide'];
      if(!expanded&&(item.panel.contains(document.activeElement)||item.separator===document.activeElement))item.button.focus({preventScroll:true});
      item.panel.hidden=!expanded;if(item.separator)item.separator.hidden=!expanded;
      this.layout.classList.toggle('sidebar-'+item.side+'-collapsed',!expanded);
      item.button.setAttribute('aria-expanded',String(expanded));item.button.classList.toggle('active',expanded);
      iconButton(item.button,item.side==='left'?'layout-sidebar':'layout-sidebar-reverse',t('layout.'+(expanded?'collapse':'expand')+'_'+item.side+'_sidebar'));
    }
    this.changed();
  }
}

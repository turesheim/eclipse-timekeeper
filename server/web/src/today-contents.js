import {t} from './i18n.js';

/** A live outline of Today's journal. Selecting an entry only moves the caret. */
export class TodayContents {
  constructor(root,field){
    this.root=root;this.field=field;this.list=root.querySelector('ol');this.empty=root.querySelector('p');this.generation=0;
  }
  show(){this.active=true;this.update();}
  hide(){this.active=false;++this.generation;}
  reset(){this.hide();this.key=null;this.list.replaceChildren();this.empty.textContent=t('today.no_headings');this.empty.hidden=false;}
  update(){
    if(!this.active)return;
    clearTimeout(this.timer);const generation=++this.generation;
    this.timer=setTimeout(()=>void this.paint(generation),100);
  }
  async paint(generation){
    try{
      if(generation!==this.generation||!this.active)return;
      if(this.field.loading){this.key=null;this.list.replaceChildren();this.empty.textContent=t('markdown.loading');this.empty.hidden=false;return;}
      const editor=this.field.editor,headings=await this.field.outline();
      if(generation!==this.generation||!this.active)return;
      const key=JSON.stringify([!!editor,headings]);if(key===this.key)return;this.key=key;
      const focused=this.list.contains(document.activeElement)?[...this.list.querySelectorAll('button')].indexOf(document.activeElement):-1;
      this.list.replaceChildren();const base=Math.min(6,...headings.map(heading=>heading.level));
      for(const [index,heading] of headings.entries()){
        const item=document.createElement('li'),pick=document.createElement('button');pick.type='button';pick.className='btn btn-link';pick.textContent=heading.text||t('markdown.heading_level',{level:heading.level});
        item.style.setProperty('--contents-depth',String(heading.level-base));
        pick.addEventListener('click',async()=>{
          try{
            const generation=this.field.generation,current=(await this.field.outline())[index];
            if(!this.active||this.field.loading||generation!==this.field.generation||current?.text!==heading.text||current?.level!==heading.level)return;
            this.field.jumpToHeading(current);
            for(const button of this.list.querySelectorAll('button'))button.removeAttribute('aria-current');pick.setAttribute('aria-current','location');
          }catch{this.update();}
        });item.append(pick);this.list.append(item);
      }
      this.empty.textContent=t('today.no_headings');this.empty.hidden=!!headings.length;
      if(focused>=0)this.list.querySelectorAll('button')[Math.min(focused,headings.length-1)]?.focus({preventScroll:true});
    }catch{if(generation===this.generation){this.list.replaceChildren();this.key=null;this.empty.textContent=t('today.contents_unavailable');this.empty.hidden=false;}}
  }
}

import {iconButton} from './icons.js';
import {t} from './i18n.js';

/** Reuse the existing controls and handlers in one date/navigation layout. */
export function dateNavigation(container,{input,choose,previous,current,next,previousLabel,currentLabel,nextLabel,chooseLabel=t('today.choose_date'),month=false}){
  const label=input.labels[0],field=document.createElement('div'),group=document.createElement('div');
  field.className='date-navigation-field'+(month?' date-navigation-month':'');group.className='input-group';
  choose??=document.createElement('button');choose.id||=input.id+'-choose';choose.type='button';
  iconButton(choose,'calendar',chooseLabel);group.append(input,choose);
  label.htmlFor=input.id;label.className='visually-hidden';field.append(label,group);
  const toolbar=document.createElement('div'),buttons=document.createElement('div');toolbar.className='btn-toolbar';buttons.className='btn-group';
  toolbar.setAttribute('role','toolbar');buttons.setAttribute('role','group');
  for(const node of [toolbar,buttons])node.setAttribute('aria-label',t('ui.date_navigation'));
  for(const [button,glyph,text] of [[previous,'left',previousLabel],[current,'clock',currentLabel],[next,'right',nextLabel]]){
    button.type='button';iconButton(button,glyph,text);buttons.append(button);
  }
  toolbar.append(buttons);container.classList.add('date-navigation');container.replaceChildren(field,toolbar);
  if(!month)choose.addEventListener('click',()=>{
    try{if(typeof input.showPicker==='function')input.showPicker();else input.focus();}catch{input.focus();}
  });
}

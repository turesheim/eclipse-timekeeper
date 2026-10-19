import {isProjectIcon,projectIcon,projectIconLabel,searchProjectIcons} from './icons.js';

const pageSize = 60;
const element = (tag,text,className) => {
  const node=document.createElement(tag);
  if(text!==undefined)node.textContent=text;
  if(className)node.className=className;
  return node;
};

export function createProjectIconPicker(initial) {
  let selected=isProjectIcon(initial) ? initial : '',matches=[],shown=0;
  const group=element('fieldset',undefined,'project-icon-picker');
  const choices=element('div',undefined,'project-icon-choices');
  const search=element('input',undefined,'form-control');
  search.type='search';search.setAttribute('aria-label','Search project icons');
  search.placeholder=`Search all ${searchProjectIcons().length.toLocaleString()} icons`;search.autocomplete='off';
  const status=element('p',undefined,'hint d-flex flex-wrap justify-content-between gap-2');status.setAttribute('role','status');
  const count=element('span'),selection=element('span');status.append(count,selection);
  const retained=element('input');retained.type='radio';retained.name='icon';retained.hidden=true;
  const more=element('button','More icons','btn btn-outline-secondary');more.type='button';
  group.append(element('legend','Icon (optional)'),search,choices,status,more);

  function updateSelection() {
    const visible=[...choices.querySelectorAll('input')].find(input=>input.value===selected);
    if(visible) {retained.remove();visible.checked=true;}
    else {retained.value=selected;group.append(retained);retained.checked=true;}
    selection.replaceChildren('Selected: ');
    if(selected)selection.append(projectIcon({icon:selected,name:''}));
    selection.append(selected ? ' '+projectIconLabel(selected) : 'None');
  }
  function choice({id,label}) {
    const option=element('label',undefined,'project-icon-option'),input=element('input');
    input.type='radio';input.name='icon';input.value=id;input.checked=id===selected;input.className='form-check-input';
    input.addEventListener('change',()=>{if(input.checked){selected=id;updateSelection();}});
    option.append(input);
    if(id)option.append(projectIcon({icon:id,name:label}));
    option.append(element('span',label));return option;
  }
  function appendPage() {
    const page=matches.slice(shown,shown+pageSize),fragment=document.createDocumentFragment();
    for(const entry of page)fragment.append(choice(entry));
    const first=fragment.firstElementChild;choices.append(fragment);shown+=page.length;
    updateSelection();count.textContent=matches.length ? `${shown} of ${matches.length} icons` : 'No matching icons.';
    more.hidden=shown>=matches.length;return first;
  }
  function filter() {
    matches=searchProjectIcons(search.value);shown=0;
    choices.replaceChildren(choice({id:'',label:'None'}));appendPage();choices.scrollTop=0;
  }
  search.addEventListener('input',()=>{if(!search.disabled)filter();});
  more.addEventListener('click',()=>{
    if(search.disabled)return;
    const first=appendPage();
    first?.querySelector('input').focus({preventScroll:true});first?.scrollIntoView({block:'nearest'});
  });
  filter();return group;
}

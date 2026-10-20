import { getLocale } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { isProjectIcon, projectIcon, projectIconCategories, projectIconLabel, searchProjectIcons } from './icons.js';

const pageSize = 60;
const element = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
};

export function createProjectIconPicker(initial) {
  let selected = isProjectIcon(initial) ? initial : '',matches = [],shown = 0;
  const group = element('fieldset', undefined, 'project-icon-picker');
  const choices = element('div', undefined, 'project-icon-choices');
  const search = element('input', undefined, 'form-control');
  search.type = 'search';search.setAttribute('aria-label', i18nText("ui.search_project_icons"));
  search.placeholder = i18nText("ui.search_all_icons", { value0: searchProjectIcons().length.toLocaleString(getLocale()) });search.autocomplete = 'off';
  const filters=element('div',undefined,'project-icon-filters');
  function select(label,options){
    const field=element('label',i18nText(label),'form-label'),input=element('select',undefined,'form-select');
    for(const [value,text] of options){const option=element('option',text);option.value=value;input.append(option);}
    field.append(input);filters.append(field);return input;
  }
  const category=select('icons.category',[
    ['all',i18nText('icons.all')+' ('+searchProjectIcons().length.toLocaleString(getLocale())+')'],
    ...projectIconCategories.map(value=>[value.id,i18nText('icons.category.'+value.id)+' ('+value.count.toLocaleString(getLocale())+')'])
  ]);
  const style=select('icons.style',['all','filled','unfilled'].map(value=>[value,i18nText('icons.style.'+value)]));
  const status = element('p', undefined, 'hint d-flex flex-wrap justify-content-between gap-2');status.setAttribute('role', 'status');
  const count = element('span'),selection = element('span');status.append(count, selection);
  const retained = element('input');retained.type = 'radio';retained.name = 'icon';retained.hidden = true;
  const more = element('button', i18nText("ui.more_icons"), 'btn btn-outline-secondary');more.type = 'button';
  group.append(element('legend', i18nText("ui.icon_optional")), search, filters, choices, status, more);

  function updateSelection() {
    const visible = [...choices.querySelectorAll('input')].find((input) => input.value === selected);
    if (visible) {retained.remove();visible.checked = true;} else
    {retained.value = selected;group.append(retained);retained.checked = true;}
    selection.replaceChildren(i18nText("ui.selected"));
    if (selected) selection.append(projectIcon({ icon: selected, name: '' }));
    selection.append(selected ? ' ' + projectIconLabel(selected) : i18nText("ui.none"));
  }
  function choice({ id, label }) {
    const option = element('label', undefined, 'project-icon-option'),input = element('input');
    input.type = 'radio';input.name = 'icon';input.value = id;input.checked = id === selected;input.className = 'form-check-input';
    input.addEventListener('change', () => {if (input.checked) {selected = id;updateSelection();}});
    option.append(input);
    if (id) option.append(projectIcon({ icon: id, name: label }));
    option.append(element('span', label));return option;
  }
  function appendPage() {
    const page = matches.slice(shown, shown + pageSize),fragment = document.createDocumentFragment();
    for (const entry of page) fragment.append(choice(entry));
    const first = fragment.firstElementChild;choices.append(fragment);shown += page.length;
    updateSelection();count.textContent = matches.length ? i18nText("ui.of_icons", { value0: shown, value1: matches.length }) : i18nText("ui.no_matching_icons");
    more.hidden = shown >= matches.length;return first;
  }
  function filter() {
    matches = searchProjectIcons(search.value,{category:category.value,style:style.value});shown = 0;
    choices.replaceChildren(choice({ id: '', label: i18nText("ui.none") }));appendPage();choices.scrollTop = 0;
  }
  search.addEventListener('input', () => {if (!search.disabled) filter();});
  for(const input of [category,style])input.addEventListener('change',()=>{if(!input.disabled)filter();});
  more.addEventListener('click', () => {
    if (search.disabled) return;
    const first = appendPage();
    first?.querySelector('input').focus({ preventScroll: true });first?.scrollIntoView({ block: 'nearest' });
  });
  filter();return group;
}

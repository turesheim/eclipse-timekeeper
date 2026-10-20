// Bootstrap Icons, bundled locally with the upstream MIT license.
import {projectCatalog,projectCategories} from './project-icons.js';
const normalize = value => value.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/[-_]/g, ' ');
export const projectIconCategories = projectCategories.map(([id,name,count])=>({id,name,count}));
const categoryNames = new Map(projectCategories.map(([id,name])=>[id,name]));
const projectEntries = projectCatalog.map(([id,label,terms,category]) => ({id,label,category,filled:/(?:^|-)fill(?:-|$)/.test(id),search:normalize(`${id} ${label} ${terms} ${categoryNames.get(category)}`)}));
const projectsByIcon = new Map(projectEntries.map(entry => [entry.id,entry]));
export const isProjectIcon = id => projectsByIcon.has(id);
export const projectIconLabel = id => projectsByIcon.get(id)?.label;
export function searchProjectIcons(query = '', {category='all',style='all'} = {}) {
  const terms = normalize(query).trim().split(/\s+/).filter(Boolean);
  return projectEntries.filter(entry => (category==='all'||entry.category===category)
    && (style!=='filled'||entry.filled) && (style!=='unfilled'||!entry.filled)
    && terms.every(term => entry.search.includes(term)));
}
const actions = {close:'x-lg',add:'plus-lg',link:'link-45deg',unlink:'link',left:'chevron-left',right:'chevron-right',down:'chevron-down',clock:'clock',calendar:'calendar3',more:'three-dots',start:'play-fill',stop:'stop-fill',edit:'pencil-square',delete:'trash',refresh:'arrow-repeat',check:'check-circle-fill',warning:'exclamation-triangle-fill',error:'exclamation-circle-fill',collapse:'chevron-double-up',expand:'chevron-double-down',copy:'copy',paste:'clipboard'};
function bootstrapIcon(name) {
  const node = document.createElement('span'); node.className = `icon bi bi-${name}`;
  node.setAttribute('aria-hidden', 'true'); return node;
}
export const icon = name => bootstrapIcon(actions[name] || (projectsByIcon.has(name) ? name : 'question-circle'));
export function iconButton(node, name, label) {
  node.replaceChildren(icon(name)); node.classList.add('btn', 'icon-button');
  if (![...node.classList].some(value => /^btn-(primary|danger|outline-)/.test(value))) node.classList.add('btn-outline-secondary');
  if (name === 'delete') {node.classList.remove('btn-outline-secondary');node.classList.add('btn-outline-danger');}
  node.setAttribute('aria-label', label); node.title = label; return node;
}

export function projectIcon(project) {
  const entry=projectsByIcon.get(project?.icon);if(!entry)return null;
  const node=bootstrapIcon(entry.id);node.dataset.projectIcon=entry.id;node.title=project.name;return node;
}

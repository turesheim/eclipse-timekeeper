// Bootstrap Icons, bundled locally with the upstream MIT license.
import {projectCatalog} from './project-icons.js';
import {legacyProjectIcons} from './legacy-project-icons.js';
const normalize = value => value.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/[-_]/g, ' ');
const projectEntries = projectCatalog.map(([id,label,terms]) => ({id,label,search:normalize(`${id} ${label} ${terms}`)}));
const projectsByIcon = new Map(projectEntries.map(entry => [entry.id,entry]));
for(const [id,name] of Object.entries(legacyProjectIcons))if(!projectsByIcon.has(id))projectsByIcon.set(id,{id,name,label:id.split('-').map(word=>word[0].toUpperCase()+word.slice(1)).join(' ')});
export const isProjectIcon = id => projectsByIcon.has(id);
export const projectIconLabel = id => projectsByIcon.get(id)?.label;
export function searchProjectIcons(query = '') {
  const terms = normalize(query).trim().split(/\s+/).filter(Boolean);
  return terms.length ? projectEntries.filter(entry => terms.every(term => entry.search.includes(term))) : projectEntries;
}
const actions = {close:'x-lg',add:'plus-lg',link:'link-45deg',unlink:'link',left:'chevron-left',right:'chevron-right',down:'chevron-down',clock:'clock',calendar:'calendar3',more:'three-dots',start:'play-fill',stop:'stop-fill',edit:'pencil-square',delete:'trash',refresh:'arrow-repeat',check:'check-circle-fill',warning:'exclamation-triangle-fill',error:'exclamation-circle-fill',collapse:'chevron-double-up',expand:'chevron-double-down',copy:'copy',paste:'clipboard'};
function bootstrapIcon(name) {
  const node = document.createElement('span'); node.className = `icon bi bi-${name}`;
  node.setAttribute('aria-hidden', 'true'); return node;
}
export const icon = name => bootstrapIcon(actions[name] || projectsByIcon.get(name)?.name || (projectsByIcon.has(name) ? name : 'question-circle'));
export function iconButton(node, name, label) {
  node.replaceChildren(icon(name)); node.classList.add('btn', 'icon-button');
  if (![...node.classList].some(value => /^btn-(primary|danger|outline-)/.test(value))) node.classList.add('btn-outline-secondary');
  if (name === 'delete') {node.classList.remove('btn-outline-secondary');node.classList.add('btn-outline-danger');}
  node.setAttribute('aria-label', label); node.title = label; return node;
}

export function projectIcon(project) {
  const entry=projectsByIcon.get(project?.icon);if(!entry)return null;
  const node=bootstrapIcon(entry.name || entry.id);node.dataset.projectIcon=entry.id;node.title=project.name;return node;
}

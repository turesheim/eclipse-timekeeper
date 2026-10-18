// Font Awesome Free 7.3.1, bundled under assets/fontawesome with its upstream license.
import {projectCatalog} from './project-icons.js';
export const projectIcons = projectCatalog.map(([id,label])=>[id,label]);
const glyphs = {...Object.fromEntries(projectCatalog.map(([id,,glyph])=>[id,glyph])),close:'\uf00d',add:'\uf067', link:'\uf0c1', unlink:'\uf127', left:'\uf053', right:'\uf054', down:'\uf078', clock:'\uf017', calendar:'\uf133', more:'\uf141', start:'\uf04b', stop:'\uf04d', edit:'\uf044', delete:'\uf1f8', refresh:'\uf021', collapse:'\uf102', expand:'\uf103',copy:'\uf0c5',paste:'\uf0ea'};
export function icon(name) {
  const node = document.createElement('span'); node.className = 'icon';
  node.setAttribute('aria-hidden', 'true'); node.textContent = glyphs[name]; return node;
}
export function iconButton(node, name, label) {
  node.replaceChildren(icon(name)); node.classList.add('btn', 'icon-button');
  if (![...node.classList].some(value => /^btn-(primary|danger|outline-)/.test(value))) node.classList.add('btn-outline-secondary');
  if (name === 'delete') {node.classList.remove('btn-outline-secondary');node.classList.add('btn-outline-danger');}
  node.setAttribute('aria-label', label); node.title = label; return node;
}

export function projectIcon(project) {
  if(!projectIcons.some(([id])=>id===project?.icon))return null;
  const node=icon(project.icon);node.textContent=projectCatalog.find(([id])=>id===project.icon)[2];node.dataset.projectIcon=project.icon;node.title=project.name;return node;
}

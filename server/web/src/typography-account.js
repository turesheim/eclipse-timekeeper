import {t} from './i18n.js';
import {node, button, section} from './account-ui.js';

export async function renderTypography(view) {
  const preferences = window.timekeeperTypography;
  let defaults;
  try {defaults = await preferences.defaults();}
  catch {throw new Error(t('typography.error.load'));}
  if (!view.valid()) return;
  const card = section(view, t('typography.title'));
  const description = node('p', t('typography.description'), 'hint');description.id = 'typography-description';
  const help = node('p', t('typography.help'), 'hint');help.id = 'typography-help';
  const value = node('form'), label = node('label', t('typography.css'), 'form-label');
  const editor = node('textarea', undefined, 'form-control typography-editor');
  editor.id = 'typography-css';label.htmlFor = editor.id;editor.spellcheck = false;
  editor.autocapitalize = 'off';editor.setAttribute('autocorrect', 'off');
  editor.setAttribute('aria-describedby', `${description.id} ${help.id}`);
  editor.value = preferences.read() ?? defaults;
  editor.setSelectionRange(0, 0);
  const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
  const save = node('button', t('typography.save'), 'btn btn-primary');save.type = 'submit';
  const dirty = () => {
    if (editor.value === (preferences.read() ?? defaults)) view.clean(value);
    else view.dirty.add(value);
    feedback.textContent = '';editor.removeAttribute('aria-invalid');
  };
  editor.addEventListener('input', dirty);
  const message = error => {
    feedback.textContent = error.typographyCode ? t('typography.error.' + error.typographyCode, error.values) : t('typography.error.storage');
    if (error.typographyCode) {editor.setAttribute('aria-invalid', 'true');editor.focus();}
  };
  value.addEventListener('submit', event => {
    event.preventDefault();if (!view.valid()) return;
    try {preferences.save(editor.value);view.clean(value);editor.removeAttribute('aria-invalid');feedback.textContent = t('typography.saved');}
    catch (error) {message(error);}
  });
  const reset = button(t('typography.reset'), () => {
    if (!view.valid()) return;
    try {preferences.reset();editor.value = defaults;editor.setSelectionRange(0, 0);view.clean(value);editor.removeAttribute('aria-invalid');feedback.textContent = t('typography.reset_done');}
    catch (error) {message(error);}
  });
  const actions = node('div', undefined, 'd-flex flex-wrap gap-2');actions.append(save, reset);
  value.append(label, editor, help, feedback, actions);card.append(description, value);
  // Clean editors follow other tabs; an unsaved draft remains available to edit.
  const changed = () => {if (view.valid() && !view.dirty.has(value)) editor.value = preferences.read() ?? defaults;};
  window.addEventListener('timekeeper-typography-change', changed);
  view.cleanup.add(() => window.removeEventListener('timekeeper-typography-change', changed));
}

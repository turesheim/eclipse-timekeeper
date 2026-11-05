import {t} from './i18n.js';
import {node, button, section} from './account-ui.js';
import {cssEditor} from './css-editor.js';

export async function renderTypography(view) {
  const preferences = window.timekeeperTypography;
  const [profile, defaults] = await Promise.all([
    view.session.request('/me'),
    preferences.defaults().catch(() => {throw new Error(t('typography.error.load'));})
  ]);
  if (!view.valid()) return;
  preferences.receive(profile);
  let loaded = preferences.profile() ?? profile;
  const card = section(view, t('typography.title'));
  const description = node('p', t('typography.description'), 'hint');description.id = 'typography-description';
  const help = node('p', t('typography.help'), 'hint');help.id = 'typography-help';
  const value = node('form', undefined, 'typography-form'), label = node('label', t('typography.css'), 'form-label');
  const editor = node('textarea', undefined, 'form-control typography-editor');
  editor.id = 'typography-css';label.htmlFor = editor.id;editor.spellcheck = false;
  editor.autocapitalize = 'off';editor.setAttribute('autocorrect', 'off');
  editor.setAttribute('aria-describedby', `${description.id} ${help.id}`);
  editor.value = loaded.data.typographyCss ?? defaults;editor.setSelectionRange(0, 0);
  const syntax=cssEditor(editor);view.cleanup.add(()=>syntax.destroy());
  const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
  const save = node('button', t('typography.save'), 'btn btn-primary');save.type = 'submit';
  editor.addEventListener('input', () => {
    if (editor.value === (loaded.data.typographyCss ?? defaults)) view.clean(value);
    else view.dirty.add(value);
    feedback.textContent = '';editor.removeAttribute('aria-invalid');
  });
  async function persist(css) {
    if (css !== null) try {preferences.validate(css);}
    catch (error) {editor.setAttribute('aria-invalid', 'true');editor.focus();throw new Error(t('typography.error.' + error.typographyCode, error.values));}
    const reply = await view.session.request('/me', {
      method: 'PUT', etag: loaded.etag, body: {zoneId: loaded.data.zoneId, typographyCss: css}
    });
    if (!view.valid()) return;
    loaded = reply;preferences.receive(reply);
    editor.value = css ?? defaults;if (css === null) editor.setSelectionRange(0, 0);
    syntax.refresh();
    view.clean(value);editor.removeAttribute('aria-invalid');
    feedback.textContent = t(css === null ? 'typography.reset_done' : 'typography.saved');
    void view.refreshProfile(reply).catch(() => {});
  }
  async function change(css, submit) {
    if (!view.valid() || view.busy) return;
    save.disabled = true;reset.disabled = true;
    try {await view.run(() => persist(css), {submit, feedback, write: true});}
    finally {if (view.valid()) save.disabled = reset.disabled = !view.canWrite || view.uncertain;}
  }
  value.addEventListener('submit', event => {event.preventDefault();void change(editor.value, save);});
  const reset = button(t('typography.reset'), () => void change(null, reset));
  save.disabled = reset.disabled = !view.canWrite;
  const actions = node('div', undefined, 'd-flex flex-wrap gap-2');actions.append(save, reset);
  value.append(label, syntax.root, help, feedback, actions);card.append(description, value);
  // Confirmed peer changes update clean editors. Dirty editors keep their ETag
  // and draft so a stale save is handled by the shared account form lifecycle.
  const changed = () => {
    const latest = preferences.profile();
    if (!view.valid() || view.dirty.has(value) || latest?.data.ownerId !== view.session.owner) return;
    loaded = latest;
    const text = loaded.data.typographyCss ?? defaults;
    if (editor.value !== text) {editor.value = text;editor.setSelectionRange(0, 0);}
    syntax.refresh();
  };
  window.addEventListener('timekeeper-typography-change', changed);
  view.cleanup.add(() => window.removeEventListener('timekeeper-typography-change', changed));
}

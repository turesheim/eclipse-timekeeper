import {t} from './i18n.js';
import {node, button, field, passwordField, validatePassword, form} from './account-ui.js';
import {PasskeyCeremony, passkeysSupported, canceledPasskey} from './webauthn.js';

export async function renderPassword(view) {
  let canUsePasskey = false;
  if (passkeysSupported()) {
    try {
      const {data} = await view.session.request('/auth/passkeys');
      canUsePasskey = data.enabled && data.items.some(key => key.usableHere);
    } catch (error) {
      if (error.code !== 'PASSKEY_ACCOUNT_SIGN_IN_REQUIRED') throw error;
    }
  }
  if (!view.valid()) return;
  const ceremony = new PasskeyCeremony(async (path, body) =>
    (await view.session.request('/auth/passkeys/' + path, {method: 'POST', body})).data);
  let canceled = false;
  const editor = form(view, t('ui.change_password'), t('ui.change_password_and_sign_out'), async () => {
    validatePassword(next.value, confirm.value);
    canceled = false;
    const body = {newPassword: next.value};
    try {
      if (method?.value === 'passkey') {
        const evidence = await ceremony.run('proof/options', 'proof/finish', {action: 'PASSWORD'}, {
          valid: () => view.valid() && !canceled,
          stage: stage => {
            cancel.hidden = false;cancel.disabled = stage === 'finish';
            editor.feedback.textContent = t(stage === 'prompt' ? 'ui.complete_the_request_in_your_browser_or_password_manager' :
              stage === 'finish' ? 'ui.saving' : 'ui.preparing_passkey_request');
          }
        });
        if (!view.valid() || canceled) return;
        body.proof = evidence.proof;
      } else {
        body.currentPassword = old.value;old.value = '';
      }
      cancel.hidden = true;
      await view.session.request('/auth/password', {method: 'POST', body});
      if (view.valid()) view.signOut(t('ui.password_changed_sign_in_again_your_existing_sessions_and_api_tokens_have_been_revoke'));
    } catch (error) {
      if (canceledPasskey(error) || canceled) {editor.feedback.textContent = t('ui.passkey_request_canceled');return;}
      if (error.status === 401) {
        await view.session.request('/auth/session');
        throw new Error(t('ui.the_current_password_was_not_accepted_your_draft_is_kept'));
      }
      throw error;
    } finally {
      body.currentPassword = '';body.newPassword = '';body.proof = '';
      if (view.valid()) cancel.hidden = true;
    }
  });
  editor.value.append(node('p', t('ui.use_at_least_15_characters_changing_your_password_signs_out_all_of_your_sessions_and_'), 'alert alert-warning'));
  const method = canUsePasskey ? field(editor.value, t('ui.confirm_with'), 'password', {choices: [
    ['password', t('ui.current_password')], ['passkey', t('ui.existing_passkey')]
  ]}) : null;
  if (canUsePasskey) editor.value.append(node('p', t('password.passkey_help'), 'hint'));
  const old = passwordField(editor.value, t('ui.current_password'), true);
  const next = passwordField(editor.value, t('ui.new_password'));
  const confirm = passwordField(editor.value, t('ui.confirm_new_password'));
  method?.addEventListener('change', () => {
    old.value = '';old.required = method.value === 'password';
    // A disabled, hidden current-password field must not block native validation.
    old.disabled = !old.required;old.closest('.account-field').hidden = !old.required;
  });
  const cancel = button(t('ui.cancel_passkey_request'), () => {
    if (cancel.disabled) return;
    canceled = true;ceremony.cancel();editor.feedback.textContent = t('ui.passkey_request_canceled');
  });
  cancel.hidden = true;editor.finish();editor.value.append(cancel);
  view.cleanup.add(() => {canceled = true;ceremony.cancel();old.value = next.value = confirm.value = '';});
}

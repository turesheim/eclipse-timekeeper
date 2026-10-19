import { t as i18nText } from "./i18n.js";import { instantText } from './model.js';
import { node, button, field, passwordField, section, form, table } from './account-ui.js';
import { PasskeyCeremony, passkeysSupported, canceledPasskey } from './webauthn.js';

export async function renderPasskeys(view, zone) {
  let data;
  try {data = (await view.session.request('/auth/passkeys')).data;}
  catch (error) {
    if (!view.valid()) return;
    if (error.code !== 'PASSKEY_ACCOUNT_SIGN_IN_REQUIRED') throw error;
    const card = section(view, i18nText("ui.passkeys"));card.append(node('p', error.message, 'alert alert-info'));
    const signIn = button(i18nText("ui.sign_in_with_your_account"), () => void view.run(async () => {
      await view.session.request('/auth/logout', { method: 'POST' });if (view.valid()) view.signOut(i18nText("ui.sign_in_with_your_account_password_or_a_passkey_to_manage_passkeys"));
    }, { submit: signIn }));card.append(signIn);return;
  }
  if (!view.valid()) return;
  const card = section(view, i18nText("ui.passkeys")),feedback = node('p', '', 'form-feedback'),rows = node('div'),actions = node('div');feedback.setAttribute('role', 'status');
  card.append(node('p', i18nText("ui.use_your_browser_or_password_manager_to_sign_in_with_a_fingerprint_device_pin_or_secu")));
  if (!data.enabled) card.append(node('p', i18nText("ui.passkeys_require_a_configured_https_hostname_and_enabled_server_support_you_can_conti"), 'alert alert-info'));else
  if (!passkeysSupported()) card.append(node('p', i18nText("ui.this_browser_cannot_add_or_use_passkeys_you_can_still_rename_or_remove_one_using_your"), 'alert alert-info'));
  const request = async (path, body) => (await view.session.request('/auth/passkeys/' + path, { method: 'POST', body })).data;
  const ceremony = new PasskeyCeremony(request);let operation = null,actionForm = null,readGeneration = 0;
  const dispose = () => {if (operation) operation.canceled = true;ceremony.cancel();for (const manager of managers) manager.clear();};view.cleanup.add(dispose);
  const hasKey = () => data.items.some((key) => key.usableHere);
  const managers = [];
  async function refresh() {
    const current = ++readGeneration,reply = await view.session.request('/auth/passkeys');
    if (!view.valid() || current !== readGeneration) return;
    data = reply.data;paint();for (const manager of managers) manager.update();
  }
  const reload = button(i18nText("ui.refresh_passkey_list"), () => void view.run(refresh, { submit: reload, feedback }));card.append(reload, feedback, rows);
  function proofFields(target) {
    const method = field(target, i18nText("ui.confirm_with"), 'password', { choices: [['password', i18nText("ui.current_password")], ['passkey', i18nText("ui.existing_passkey")]] }),password = passwordField(target, i18nText("ui.current_password"), true);
    const update = () => {
      method.querySelector('option[value="passkey"]').disabled = !hasKey() || !passkeysSupported();
      if (method.value === 'passkey' && (!hasKey() || !passkeysSupported())) method.value = 'password';
      const usePassword = method.value === 'password';password.disabled = !usePassword;password.required = usePassword;password.closest('.account-field').hidden = !usePassword;
    };
    method.addEventListener('change', () => {password.value = '';update();});update();
    const result = { target, update, clear: () => {password.value = '';}, async value(action, stage) {
        if (method.value === 'password') {const value = password.value;password.value = '';return { currentPassword: value };}
        const proof = await ceremony.run('proof/options', 'proof/finish', action, { valid: () => view.valid() && !operation?.canceled, stage });
        return { proof: proof.proof };
      } };managers.push(result);return result;
  }
  async function change(cancel, feedback, work) {
    ++readGeneration;const current = { canceled: false };operation = current;
    const stage = (value) => {
      if (!view.valid() || operation !== current) return;
      cancel.hidden = false;cancel.disabled = value === 'finish';
      feedback.textContent = value === 'prompt' ? i18nText("ui.complete_the_request_in_your_browser_or_password_manager") : value === 'finish' ? i18nText("ui.saving") : i18nText("ui.preparing_passkey_request");
    };
    cancel.onclick = () => {if (cancel.disabled || operation !== current) return;current.canceled = true;ceremony.cancel();feedback.textContent = i18nText("ui.passkey_request_canceled");};
    try {await work(stage, current);}
    catch (error) {
      if (!view.valid()) return;
      if (canceledPasskey(error) || current.canceled) {feedback.textContent = i18nText("ui.passkey_request_canceled_your_other_passkeys_are_unchanged");return;}
      throw error;
    } finally {if (operation === current) operation = null;if (view.valid()) cancel.hidden = true;}
  }
  function cancelControl(target) {const value = button(i18nText("ui.cancel_passkey_request"), () => {});value.hidden = true;target.append(value);return value;}
  const add = form(view, i18nText("ui.add_passkey"), i18nText("ui.add_passkey"), async () => {
    await change(addCancel, add.feedback, async (stage, current) => {
      const name = addName.value.trim(),proof = await addProof.value({ action: 'ADD' }, stage);
      if (!view.valid() || current.canceled) return;
      await ceremony.run('register/options', 'register/finish', { name, ...proof }, { create: true, valid: () => view.valid() && !current.canceled, stage });
      if (!view.valid() || current.canceled) return;
      addName.value = '';view.clean(add.value);add.feedback.textContent = i18nText("ui.passkey_added");await refresh();
    });
  }, { canSubmit: () => data.enabled && passkeysSupported() });
  const addName = field(add.value, i18nText("ui.passkey_name"), '', { maxLength: 200 });const addProof = proofFields(add.value),addCancel = cancelControl(add.value);add.finish();
  view.root.append(actions);
  function discardAction() {
    for (let index = managers.length - 1; index >= 0; index--) if (managers[index].target === actionForm) {managers[index].clear();managers.splice(index, 1);}
    if (actionForm) view.clean(actionForm);actionForm = null;actions.replaceChildren();
  }
  function closeAction(ask = true) {
    if (view.busy) return false;
    if (actionForm && ask && view.dirty.has(actionForm) && !window.confirm(i18nText("ui.discard_unsaved_passkey_changes"))) return false;
    discardAction();return true;
  }
  function edit(key, remove = false) {
    if (view.busy || !closeAction()) return;
    const title = remove ? i18nText("ui.remove", { value0: key.name }) : i18nText("ui.rename", { value0: key.name });
    const editor = form(view, title, remove ? i18nText("ui.remove_passkey") : i18nText("ui.save_name"), async () => {
      if (remove) await change(cancel, editor.feedback, async (stage, current) => {
        const proof = await evidence.value({ action: 'REMOVE', id: key.id, etag: key.etag }, stage);
        if (!view.valid() || current.canceled) return;
        stage('finish');
        const reply = await view.session.request('/auth/passkeys/' + key.id, { method: 'DELETE', etag: key.etag, body: proof });
        if (!view.valid()) return;
        view.clean(editor.value);
        if (reply.data.signedOut) {view.signOut(i18nText("ui.passkey_removed_its_sessions_were_signed_out_sign_in_with_your_password_or_another_pa"));return;}
        feedback.textContent = i18nText("ui.passkey_removed_delete_it_from_your_password_manager_separately_if_needed");discardAction();await refresh();
      });else
      {
        ++readGeneration;await view.session.request('/auth/passkeys/' + key.id, { method: 'PUT', etag: key.etag, body: { name: name.value.trim() } });
        if (!view.valid()) return;view.clean(editor.value);feedback.textContent = i18nText("ui.passkey_renamed");discardAction();await refresh();
      }
    }, { canSubmit: () => !remove || data.enabled });
    let name, evidence, cancel;
    if (remove) {
      editor.value.append(node('p', i18nText("ui.removing_this_passkey_signs_out_sessions_created_with_it_it_does_not_remove_the_crede"), 'alert alert-warning'));
      evidence = proofFields(editor.value);cancel = cancelControl(editor.value);
    } else name = field(editor.value, i18nText("ui.passkey_name"), key.name, { maxLength: 200 });
    editor.finish();editor.value.append(button(i18nText("ui.cancel"), () => closeAction()));
    actionForm = editor.value;actions.append(editor.value.closest('section'));(name || editor.value.querySelector('input,select'))?.focus();
  }
  function paint() {
    rows.replaceChildren();
    if (!data.items.length) {rows.append(node('p', i18nText("ui.no_passkeys_added")));return;}
    rows.append(table([i18nText("ui.name"), i18nText("ui.added"), i18nText("ui.last_used"), i18nText("ui.availability"), i18nText("ui.actions")], data.items.map((key) => {
      const controls = node('div', undefined, 'd-flex flex-wrap gap-2');
      const rename = button(i18nText("ui.rename_3064d7"), () => edit(key)),remove = button(i18nText("ui.remove_c3812f"), () => edit(key, true), "btn btn-outline-danger");
      rename.setAttribute('aria-label', i18nText("ui.rename_cee97a", { value0: key.name }));remove.setAttribute('aria-label', i18nText("ui.remove_e224cf", { value0: key.name }));
      rename.disabled = !view.canWrite;remove.disabled = !view.canWrite || !data.enabled;controls.append(rename, remove);
      return [key.name, instantText(key.createdAt, zone), key.lastUsedAt ? instantText(key.lastUsedAt, zone) : i18nText("ui.never_used"), !data.enabled ? i18nText("ui.passkeys_disabled") : key.usableHere ? i18nText("ui.ready_to_use") : i18nText("ui.registered_for_add_a_replacement_here", { value0: key.rpId }), controls];
    })));
  }
  paint();
}

import { getLocale } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { instantText, localDateTimeToInstant } from './model.js';
import { node, button, field, passwordField, validatePassword, section, form, table } from './account-ui.js';
import { renderRecoveryEmails } from './recovery-emails.js';
import { renderImports } from './imports.js';
import { renderPasskeys } from './passkeys.js';
import { renderAccountData } from './account-data.js';

export async function renderAccount(view, part) {
  const [loaded, profile] = await Promise.all([view.session.request('/auth/account'), view.session.request('/me')]);
  if (!view.valid()) return;
  const account = loaded.data,zone = profile.data.zoneId;
  if (part === 'data') {await renderAccountData(view, zone);return;}
  if (part === 'passkeys') {await renderPasskeys(view, zone);return;}
  if (part === 'import') {await renderImports(view, zone);return;}
  if (part === 'tokens') {await renderTokens(view, zone);return;}
  if (account.state === 'UNCLAIMED') {
    view.root.append(node('p', i18nText("ui.ask_the_server_operator_for_an_account_recovery_code_to_add_a_username_and_password_t"), 'alert alert-info'));
    if (part === 'password') return;
  } else if (part === 'password') {
    const password = form(view, i18nText("ui.change_password"), i18nText("ui.change_password_and_sign_out"), async () => {
      validatePassword(next.value, confirm.value);
      try {await view.session.request('/auth/password', { method: 'POST', body: { currentPassword: old.value, newPassword: next.value } });}
      catch (error) {
        if (error.status === 401) {await view.session.request('/auth/session');throw new Error(i18nText("ui.the_current_password_was_not_accepted_your_draft_is_kept"));}
        throw error;
      }
      if (view.valid()) view.signOut(i18nText("ui.password_changed_sign_in_again_your_existing_sessions_and_api_tokens_have_been_revoke"));
    });
    password.value.append(node('p', i18nText("ui.use_at_least_15_characters_changing_your_password_signs_out_all_of_your_sessions_and_"), 'alert alert-warning'));
    const old = passwordField(password.value, i18nText("ui.current_password"), true),next = passwordField(password.value, i18nText("ui.new_password")),confirm = passwordField(password.value, i18nText("ui.confirm_new_password"));
    password.finish();return;
  } else {
    const identity = form(view, i18nText("ui.profile"), i18nText("ui.save_profile"), async () => {
      const reply = await view.session.request('/auth/account', { method: 'PUT', etag: loaded.etag, body: { username: username.value, displayName: display.value.trim() || username.value } });
      if (!view.valid()) return;loaded.etag = reply.etag;view.changed(reply.data);view.clean(identity.value);identity.feedback.textContent = i18nText("ui.profile_saved");
    });
    const username = field(identity.value, i18nText("ui.account_username"), account.username, { autocomplete: 'username', maxLength: 64 });
    const display = field(identity.value, i18nText("ui.display_name_optional"), account.displayName === account.username ? '' : account.displayName, { required: false, autocomplete: 'nickname', maxLength: 200 });
    identity.value.append(node('p', i18nText("ui.leave_display_name_blank_to_use_your_username_renaming_your_account_preserves_your_pr"), 'hint'));identity.finish();
  }
  const localePreferences = form(view, i18nText('locale.language'), i18nText('locale.save'), async () => {
    const otherDrafts = [...view.dirty].filter((value) => value !== localePreferences.value);
    if (otherDrafts.length && !window.confirm(i18nText('locale.discard'))) return;
    const reply = await view.session.request('/me', { method: 'PUT', etag: profile.etag, body: { zoneId: profile.data.zoneId, locale: localeChoice.value || null } });
    if (!view.valid()) return;
    profile.etag = reply.etag;profile.data = reply.data;
    if (otherDrafts.length) view.dirty.clear();else view.clean(localePreferences.value);
    localePreferences.feedback.textContent = i18nText('locale.saved');
    location.reload();
  });
  const localeChoice = field(localePreferences.value, i18nText('locale.language'), profile.data.locale || '', { required:false, choices: [
    ['', i18nText('locale.browser')], ['en-GB', i18nText("ui.english_united_kingdom")], ['en-US', i18nText("ui.english_united_states")], ['nb-NO', i18nText("ui.norsk_bokmal_norge")]]
  });
  localePreferences.finish();
  const preferences = form(view, i18nText("ui.time_zone_b9fe14"), i18nText("ui.save_time_zone"), async () => {
    try {new Intl.DateTimeFormat(getLocale(), { timeZone: input.value.trim() });} catch {throw new Error(i18nText("ui.enter_a_valid_time_zone_for_example_europe_oslo"));}
    const reply = await view.session.request('/me', { method: 'PUT', etag: profile.etag, body: { zoneId: input.value.trim() } });
    if (!view.valid()) return;profile.etag = reply.etag;profile.data = reply.data;view.clean(preferences.value);preferences.feedback.textContent = i18nText("ui.time_zone_saved_stored_activity_times_are_unchanged");
    // The preference write is confirmed. Refreshing hidden tracking reports
    // must not keep account navigation reserved after the save succeeds.
    void view.refreshProfile(reply).catch(() => {});
  });
  const input = field(preferences.value, i18nText("ui.time_zone_b9fe14"), zone),preview = node('p', '', 'hint');input.setAttribute('aria-describedby', 'account-zone-preview');preview.id = 'account-zone-preview';
  const updatePreview = () => {try {preview.textContent = `${input.value}: ${instantText(new Date().toISOString(), input.value.trim())}`;} catch {preview.textContent = i18nText("ui.enter_a_valid_iana_time_zone");}};
  input.addEventListener('input', updatePreview);updatePreview();
  const local = Intl.DateTimeFormat().resolvedOptions().timeZone;
  if (local) preferences.value.append(button(i18nText("ui.use_this_device_s_time_zone", { value0: local }), () => {input.value = local;view.dirty.add(preferences.value);updatePreview();}));
  preferences.value.append(preview);preferences.finish();
  if (account.state === 'ENABLED') await renderRecoveryEmails(view);
}

async function renderTokens(view, zone) {
  const card = section(view, i18nText("ui.personal_api_tokens"));
  card.append(node('p', i18nText("ui.use_a_separate_named_token_for_each_eclipse_installation_or_other_client_token_secret")));
  const list = node('div');card.append(list);
  let listGeneration = 0;
  async function refreshList(feedback, acknowledgement) {
    try {await tokens();}
    catch (error) {
      if (error.status === 401) throw error;
      if (!view.valid()) return;
      feedback.textContent = i18nText("ui.unable_to_refresh_the_token_list", { value0: acknowledgement });
      feedback.append(' ', button(i18nText("ui.refresh_token_list"), () => void view.run(tokens, { feedback })));
    }
  }
  async function tokens() {
    const current = ++listGeneration;
    let reply;
    try {reply = await view.session.request('/auth/tokens');}
    catch (error) {if (view.valid() && (error.status === 401 || current === listGeneration)) throw error;return;}
    if (!view.valid() || current !== listGeneration) return;
    const { data } = reply;
    list.replaceChildren();
    if (!data.items.length) {list.append(node('p', i18nText("ui.no_api_tokens_yet"), 'hint'));return;}
    const rows = data.items.map((token) => {
      const actions = node('div', undefined, 'token-actions');
      const expired = token.expiresAt && Date.parse(token.expiresAt) <= Date.now();
      const controls = [];
      if (!token.revoked) {
        const revoke = button(i18nText("ui.revoke"), () => {
          actions.replaceChildren(node('p', i18nText("ui.revoke_clients_and_browser_sessions_using_this_token_will_lose_access_your_password_s", { value0: token.name })));
          const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
          const confirm = button(i18nText("ui.revoke_token"), () => void view.run(async () => {
            await view.session.request(`/auth/tokens/${token.id}`, { method: 'DELETE' });if (!view.valid()) return;
            feedback.textContent = i18nText("ui.token_revoked");actions.replaceChildren(feedback);await refreshList(feedback, i18nText("ui.token_revoked"));
          }, { submit: confirm, feedback, write: true }), "btn btn-danger");
          actions.append(confirm, button(i18nText("ui.cancel"), () => {actions.replaceChildren(...controls);}), feedback);
        });revoke.disabled = !view.canWrite;controls.push(revoke);
      }
      if (token.source === 'MANAGED') {
        const remove = button(i18nText("ui.delete"), () => {
          actions.replaceChildren(node('p', i18nText("ui.delete_this_removes_the_token_permanently_clients_and_browser_sessions_using_it_will_", { value0: token.name })));
          const feedback = node('p', '', 'form-feedback');feedback.setAttribute('role', 'status');
          const confirm = button(i18nText("ui.delete_token"), () => void view.run(async () => {
            await view.session.request(`/auth/tokens/${token.id}?remove=true`, { method: 'DELETE' });if (!view.valid()) return;
            feedback.textContent = i18nText("ui.token_deleted");actions.replaceChildren(feedback);await refreshList(feedback, i18nText("ui.token_deleted"));
          }, { submit: confirm, feedback, write: true }), "btn btn-danger");
          actions.append(confirm, button(i18nText("ui.cancel"), () => {actions.replaceChildren(...controls);}), feedback);
        }, "btn btn-outline-danger");remove.disabled = !view.canWrite;controls.push(remove);
      }
      actions.append(...controls);
      return [token.name, token.permissions.includes('write') ? i18nText("ui.read_write") : i18nText("ui.read_only"), token.expiresAt ? instantText(token.expiresAt, zone) : i18nText("ui.no_expiry"), token.lastUsedAt ? instantText(token.lastUsedAt, zone) : i18nText("ui.never_used"), token.revoked ? i18nText("ui.revoked") : expired ? i18nText("ui.expired") : i18nText("ui.active"), actions];
    });
    list.append(table([i18nText("ui.name"), i18nText("ui.access"), i18nText("ui.expiry"), i18nText("ui.last_used"), i18nText("ui.status"), i18nText("ui.actions")], rows));
  }
  await tokens();if (!view.valid()) return;
  const create = form(view, i18nText("ui.create_api_token"), i18nText("ui.create_token"), async () => {
    const expiresAt = expiry.value ? localDateTimeToInstant(expiry.value, zone) : null;
    const { data } = await view.session.request('/auth/tokens', { method: 'POST', body: { name: name.value, permissions: access.value === 'write' ? ['read', 'write'] : ['read'], expiresAt } });
    if (!view.valid()) return;
    secret.replaceChildren(node('p', i18nText("ui.copy_this_token_now_it_will_not_be_shown_again"), 'alert alert-info'));
    const value = field(secret, i18nText("ui.new_api_token"), data.token, { required: false });value.readOnly = true;value.spellcheck = false;
    const feedback = node('p', '', 'hint');feedback.setAttribute('role', 'status');
    secret.append(button(i18nText("ui.copy_token"), async () => {
      if (!view.valid()) return;
      try {await navigator.clipboard.writeText(value.value);if (view.valid()) feedback.textContent = i18nText("ui.token_copied");}
      catch {if (view.valid()) {value.focus();value.select();feedback.textContent = i18nText("ui.copy_the_selected_token_using_your_browser");}}
    }), button(i18nText("ui.done"), () => {value.value = '';secret.replaceChildren();}), feedback);
    name.value = '';expiry.value = '';view.clean(create.value);create.feedback.textContent = i18nText("ui.token_created");await refreshList(create.feedback, i18nText("ui.token_created"));
  });
  create.value.append(node('p', i18nText("ui.expiry_times_use", { value0: zone }), 'hint'));
  const name = field(create.value, i18nText("ui.token_name"), '', { maxLength: 200 });
  const access = field(create.value, i18nText("ui.token_access"), 'write', { choices: [['read', i18nText("ui.read_only")], ['write', i18nText("ui.read_write")]] });
  const expiry = field(create.value, i18nText("ui.token_expiry_local_time_optional"), '', { type: 'datetime-local', required: false });
  create.finish();const secret = node('div', undefined, 'new-token');create.value.append(secret);
}

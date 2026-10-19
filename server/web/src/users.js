import {getLocale} from './i18n.js';
import { t as i18nText } from "./i18n.js";import { ownerHeader } from './api.js';
import { node, link, field, passwordField, validatePassword, section, form, table } from './account-ui.js';

export async function renderUsers(view, key) {
  const { data } = await view.session.request('/admin/users');if (!view.valid()) return;
  if (!key) {
    const card = section(view, i18nText("ui.independent_accounts"));
    card.append(node('p', i18nText("ui.manage_account_access_administrator_status_does_not_grant_access_to_another_user_s_cl")), link(i18nText("ui.add_user"), '#users/new', "btn btn-primary"));
    const rows = [...data.users].sort((a, b) => (a.username || a.displayName).localeCompare(b.username || b.displayName,getLocale())).map((user) => [user.displayName, user.username || i18nText("ui.unclaimed"), user.administrator ? i18nText("ui.administrator") : i18nText("ui.user"), user.state === 'ENABLED' ? i18nText("ui.enabled") : user.state === 'DISABLED' ? i18nText("ui.disabled") : i18nText("ui.unclaimed"), link(i18nText("ui.edit"), `#users/${ownerHeader(user.ownerId)}`)]);
    card.append(table([i18nText("ui.name"), i18nText("ui.username"), i18nText("ui.role"), i18nText("ui.access"), i18nText("ui.actions")], rows));return;
  }
  view.root.append(link(i18nText("ui.back_to_users"), '#users'));
  const creating = key === 'new';
  const loaded = creating ? null : await view.session.request(`/admin/users/${encodeURIComponent(key)}`);
  if (!view.valid()) return;
  const user = loaded?.data.user;
  if (user?.state === 'UNCLAIMED') {view.root.append(node('p', i18nText("ui.the_operator_must_issue_an_explicit_owner_bound_recovery_code_to_claim_this_account"), 'alert alert-info'));return;}
  const edit = form(view, creating ? i18nText("ui.add_user") : i18nText("ui.edit_6ec4b4", { value0: user.username }), creating ? i18nText("ui.create_user") : i18nText("ui.save_user"), async () => {
    if (creating) validatePassword(password.value, confirmation.value);else
    if (user.state === 'ENABLED' && !enabled.checked || user.administrator !== admin.checked) {
      if (!window.confirm(i18nText("ui.change_access_for_this_revokes_their_sessions_and_api_tokens_their_work_data_will_be_", { value0: user.username }))) return;
    }
    const body = { username: username.value, displayName: display.value.trim() || username.value, administrator: admin.checked, ...(creating ? { password: password.value, email: email.value } : { enabled: enabled.checked }) };
    const reply = await view.session.request(creating ? '/admin/users' : `/admin/users/${encodeURIComponent(key)}`, { method: creating ? 'POST' : 'PUT', etag: loaded?.etag, body });
    if (!view.valid()) return;
    view.clean(edit.value);
    if (!creating && user.ownerId === view.account.ownerId) {
      view.changed(reply.data.user);
      if (!enabled.checked || user.administrator !== admin.checked) {view.signOut(i18nText("ui.account_access_changed_sign_in_again"));return;}
    }
    view.navigate('#users');
  });
  const username = field(edit.value, creating ? i18nText("ui.new_user_username") : i18nText("ui.user_username"), user?.username, { autocomplete: creating ? 'off' : 'username', maxLength: 64 });
  const display = field(edit.value, i18nText("ui.display_name_optional"), user?.displayName === user?.username ? '' : user?.displayName, { required: false, maxLength: 200 });
  let password, confirmation, enabled, email;
  if (creating) {email = field(edit.value, i18nText("ui.new_user_recovery_email"), '', { type: 'email', autocomplete: 'off', maxLength: 254 });password = passwordField(edit.value, i18nText("ui.temporary_password"));confirmation = passwordField(edit.value, i18nText("ui.confirm_temporary_password"));} else
  enabled = field(edit.value, i18nText("ui.enabled"), user.state === 'ENABLED', { type: 'checkbox' });
  const admin = field(edit.value, creating ? i18nText("ui.new_user_is_administrator") : i18nText("ui.administrator"), user?.administrator || false, { type: 'checkbox' });
  if (!creating && user.administrator && user.state === 'ENABLED' && data.users.filter((value) => value.administrator && value.state === 'ENABLED').length === 1) {
    enabled.disabled = true;admin.disabled = true;
    edit.value.append(node('p', i18nText("ui.this_is_the_last_enabled_administrator_create_or_enable_another_administrator_before_"), 'alert alert-info'));
  }
  edit.value.append(node('p', i18nText("ui.disabling_an_account_or_changing_its_role_revokes_its_sessions_and_api_tokens_disabli"), 'hint'));
  edit.finish();
}

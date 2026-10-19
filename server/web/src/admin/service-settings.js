import { t as i18nText } from "/web/i18n.js";import { node, form, field, passwordField, section, table, button } from '/web/account-ui.js';

/** Passwords stay in this view only and are never part of the redacted configuration. */
export async function renderServiceSettings(view) {
  let loaded = await view.session.request('/admin/settings');if (!view.valid()) return;
  const status = section(view, i18nText("ui.service_status")),pending = node('p'),values = node('div'),details = node('details');details.append(node('summary', i18nText("ui.saved_and_effective_settings")), values);status.append(pending, details);
  function describe(value) {
    return [value.publicOrigin || i18nText("ui.not_configured"), value.passkeysEnabled ? i18nText("ui.enabled") : i18nText("ui.disabled"), value.passkeyRpId,
    value.emailRecoveryEnabled ? i18nText("ui.enabled") : i18nText("ui.disabled"), value.smtpHost, String(value.smtpPort), value.smtpFrom, value.smtpUsername || i18nText("ui.none"),
    value.smtpTls ? i18nText("ui.tls_on_connect") : value.smtpStartTls === 'REQUIRED' ? i18nText("ui.starttls_required") : value.smtpStartTls === 'OPTIONAL' ? i18nText("ui.starttls_when_offered") : i18nText("ui.plain_connection")];
  }
  function paint() {
    const data = loaded.data,saved = describe(data.saved),effective = describe(data.effective);
    pending.textContent = data.restartRequired ? i18nText("ui.changes_are_saved_restart_timekeeper_on_every_server_instance_to_apply_them_browser_s") : i18nText("ui.saved_settings_are_active");
    pending.className = data.restartRequired ? 'alert alert-info' : 'hint';pending.setAttribute('role', 'status');
    const labels = [i18nText("ui.public_https_address"), i18nText("ui.passkey_sign_in"), i18nText("ui.passkey_rp_id"), i18nText("ui.email_recovery"), i18nText("ui.smtp_host"), i18nText("ui.smtp_port"), i18nText("ui.sender_address"), i18nText("ui.smtp_username"), i18nText("ui.smtp_encryption")];
    effective[1] = data.passkeysEnabled ? i18nText("ui.enabled") : data.effective.passkeysEnabled ? i18nText("ui.unavailable_for_this_address") : i18nText("ui.disabled");
    values.replaceChildren(table([i18nText("ui.setting"), i18nText("ui.saved"), i18nText("ui.effective")], [...labels.map((label, index) => [label, saved[index], effective[index]]), [i18nText("ui.smtp_password"), data.smtpPasswordConfigured ? i18nText("ui.configured") : i18nText("ui.not_configured"), data.effectiveSmtpPasswordConfigured ? i18nText("ui.configured") : i18nText("ui.not_configured")]]));
    values.append(node('p', i18nText("ui.database_schema", { value0: data.databaseType === 'postgresql' ? 'PostgreSQL' : 'H2', value1: data.schemaVersion }), 'hint'));
    if (!data.canEdit) status.append(node('p', i18nText("ui.sign_in_with_your_account_password_or_a_passkey_to_change_service_settings_an_api_tok"), 'alert alert-info'));
  }
  paint();
  if (!loaded.data.canEdit) {
    status.append(button(i18nText("ui.sign_in_with_an_account"), () => void view.run(async () => {
      await view.session.request('/auth/logout', { method: 'POST' });if (view.valid()) location.assign('/web/#administration');
    })));return;
  }
  const editor = form(view, i18nText("ui.general_settings"), i18nText("ui.save_settings"), async ({ value, feedback }) => {
    if (clearPassword.checked && smtpPassword.value) throw new Error(i18nText("ui.choose_either_a_replacement_smtp_password_or_clear_the_saved_password"));
    const publicOrigin = origin.value.trim() || null,rpId = rp.value.trim() || 'AUTO';
    if (publicOrigin && rpId !== 'AUTO') {
      let host;try {host = new URL(publicOrigin).hostname;} catch {throw new Error(i18nText("ui.enter_a_valid_public_https_address"));}
      if (host !== rpId) throw new Error(i18nText("ui.the_passkey_rp_id_must_match_the_public_hostname_use_auto_to_follow_that_hostname"));
    }
    const body = { publicOrigin, passkeysEnabled: passkeys.checked, passkeyRpId: rpId, emailRecoveryEnabled: email.checked,
      smtpHost: host.value.trim(), smtpPort: Number(port.value), smtpFrom: from.value.trim(), smtpUsername: username.value, smtpTls: encryption.value === 'TLS',
      smtpStartTls: encryption.value === 'TLS' || encryption.value === 'DISABLED' ? 'DISABLED' : encryption.value,
      smtpPasswordAction: clearPassword.checked ? 'CLEAR' : smtpPassword.value ? 'REPLACE' : 'PRESERVE', currentPassword: proof.value,
      ...(smtpPassword.value ? { smtpPassword: smtpPassword.value } : {}) };
    const reply = await view.session.request('/admin/settings', { method: 'PUT', etag: loaded.etag, body });if (!view.valid()) return;
    loaded = reply;proof.value = '';smtpPassword.value = '';clearPassword.checked = false;view.clean(value);paint();
    secret.textContent = loaded.data.smtpPasswordConfigured ? i18nText("ui.an_smtp_password_is_saved_leave_the_password_field_blank_to_keep_it") : i18nText("ui.no_smtp_password_is_saved");
    feedback.textContent = loaded.data.restartRequired ? i18nText("ui.settings_saved_restart_timekeeper_to_apply_the_changes") : i18nText("ui.settings_are_already_active");
  });
  const value = loaded.data.saved;
  const origin = field(editor.value, i18nText("ui.public_https_address"), value.publicOrigin, { type: 'url', required: false, maxLength: 2048 });origin.placeholder = 'https://timekeeper.example.org';
  const passkeys = field(editor.value, i18nText("ui.enable_passkey_sign_in"), value.passkeysEnabled, { type: 'checkbox' });
  const rp = field(editor.value, i18nText("ui.passkey_rp_id"), value.passkeyRpId, { required: false, maxLength: 255 });rp.placeholder = 'AUTO';
  const email = field(editor.value, i18nText("ui.enable_recovery_email"), value.emailRecoveryEnabled, { type: 'checkbox' });
  const host = field(editor.value, i18nText("ui.smtp_host"), value.smtpHost, { maxLength: 255 });
  const port = field(editor.value, i18nText("ui.smtp_port"), value.smtpPort, { type: 'number' });port.min = '1';port.max = '65535';port.step = '1';
  const from = field(editor.value, i18nText("ui.sender_email_address"), value.smtpFrom, { maxLength: 320 });
  const username = field(editor.value, i18nText("ui.smtp_username"), value.smtpUsername, { required: false, maxLength: 255, autocomplete: "section-smtp username" });
  const encryption = field(editor.value, i18nText("ui.smtp_encryption"), value.smtpTls ? 'TLS' : value.smtpStartTls, { choices: [['REQUIRED', i18nText("ui.starttls_required")], ['TLS', i18nText("ui.tls_on_connect")], ['OPTIONAL', i18nText("ui.starttls_when_offered")], ['DISABLED', i18nText("ui.plain_connection")]] });
  const secret = node('p', loaded.data.smtpPasswordConfigured ? i18nText("ui.an_smtp_password_is_saved_leave_the_password_field_blank_to_keep_it") : i18nText("ui.no_smtp_password_is_saved"), 'hint');editor.value.append(secret);
  const smtpPassword = field(editor.value, i18nText("ui.new_smtp_password"), '', { type: 'password', required: false, autocomplete: "section-smtp new-password", maxLength: 1024 });
  const clearPassword = field(editor.value, i18nText("ui.clear_saved_smtp_password"), false, { type: 'checkbox' });
  const proof = passwordField(editor.value, i18nText("ui.current_administrator_password"), true);proof.autocomplete = 'section-administrator current-password';
  editor.value.append(node('p', i18nText("ui.changes_take_effect_after_a_coordinated_restart_dns_certificates_and_tailscale_forwar"), 'hint'));
  view.cleanup.add(() => {smtpPassword.value = '';proof.value = '';});editor.finish();
}

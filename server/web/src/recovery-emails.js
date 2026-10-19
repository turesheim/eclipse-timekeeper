import { t as i18nText } from "./i18n.js";import { node, button, field, passwordField, form } from './account-ui.js';

export async function renderRecoveryEmails(view) {
  const loaded = await Promise.all(['PRIMARY', 'BACKUP'].map((slot) => view.session.request(`/auth/email/addresses/${slot}`)));
  if (!view.valid()) return;
  if (!loaded[0].data.verifiedAddress && !loaded[0].data.pendingAddress) {
    view.root.append(node('p', i18nText("ui.add_a_primary_recovery_email_so_you_can_reset_a_forgotten_password_your_existing_sign"), 'alert alert-info'));
  }
  for (let index = 0; index < loaded.length; index++) {
    const slot = index === 0 ? 'PRIMARY' : 'BACKUP',label = index === 0 ? i18nText("ui.primary") : i18nText("ui.backup");
    const current = loaded[index];
    let state = current.data;
    const edit = form(view, i18nText("ui.recovery_email", { value0: label, value1: index ? i18nText("ui.optional") : '' }), i18nText("ui.save_email", { value0: label.toLowerCase() }), async () => {
      const address = input.value.trim() || null;
      try {
        const reply = await view.session.request(`/auth/email/addresses/${slot}`, { method: 'PUT', etag: current.etag, body: { email: address, currentPassword: password.value } });
        if (!view.valid()) return;
        state = reply.data;current.etag = reply.etag;password.value = '';view.clean(edit.value);paint();
        edit.feedback.textContent = address ? i18nText("ui.address_saved_verification_delivery_was_requested_check_your_inbox_if_no_message_arri") : i18nText("ui.backup_address_removed");
      } catch (error) {
        if (error.status === 401) {await view.session.request('/auth/session');throw new Error(i18nText("ui.the_current_password_was_not_accepted_your_draft_is_kept"));}
        if (error.status === 503) throw new Error(i18nText("ui.recovery_email_delivery_is_unavailable_ask_the_server_operator_to_check_email_configu"));
        throw error;
      }
    });
    const input = field(edit.value, i18nText("ui.email", { value0: label }), state.pendingAddress || state.verifiedAddress || '', { type: 'email', autocomplete: 'email', required: !index, maxLength: 254 });
    const password = passwordField(edit.value, i18nText("ui.current_password_for_email", { value0: label.toLowerCase() }), true);
    const status = node('p', '', 'hint');edit.value.append(status);
    const resend = button(i18nText("ui.resend_verification", { value0: label.toLowerCase() }), () => {
      void view.run(async () => {
        try {await view.session.request(`/auth/email/addresses/${slot}/resend`, { method: 'POST', body: {} });}
        catch (error) {if (error.status === 503) throw new Error(i18nText("ui.recovery_email_delivery_is_unavailable_ask_the_server_operator_to_check_email_configu"));throw error;}
        if (view.valid()) edit.feedback.textContent = i18nText("ui.verification_delivery_requested_check_your_inbox_or_contact_the_operator_if_delivery_");
      }, { submit: resend, feedback: edit.feedback, write: true });
    });
    const remove = index ? button(i18nText("ui.remove_backup_email"), () => {input.value = '';edit.value.requestSubmit();}, "btn btn-outline-danger") : null;
    const paint = () => {
      status.textContent = [state.verifiedAddress ? i18nText("ui.verified", { value0: state.verifiedAddress }) : i18nText("ui.no_verified_recovery_address"), state.pendingAddress ? i18nText("ui.awaiting_verification", { value0: state.pendingAddress }) : ''].filter(Boolean).join(' ');
      resend.hidden = !state.pendingAddress;resend.disabled = !view.canWrite;
      if (remove) {remove.hidden = !state.pendingAddress && !state.verifiedAddress;remove.disabled = !view.canWrite;}
    };
    paint();edit.value.append(resend, node('p', index ? i18nText("ui.leave_blank_to_remove_the_backup_a_verified_backup_can_recover_your_account_without_a") : i18nText("ui.verify_an_address_to_enable_email_recovery_its_existing_verified_address_remains_usab"), 'hint'));edit.finish();
    if (remove) edit.value.append(remove);
  }
}

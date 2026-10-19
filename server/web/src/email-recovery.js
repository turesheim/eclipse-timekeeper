import { t as i18nText } from "./i18n.js";import { validatePassword } from './account-ui.js';

const $ = (id) => document.getElementById(id);

// Recovery tokens live only in this closure; remove their fragment before any requests/navigation.
export function emailRecovery({ request, signIn }) {
  let mode = null,token = null,generation = 0;
  const form = $('email-recovery-form'),submit = form.querySelector('[type="submit"]');
  const feedback = $('email-recovery-feedback');

  function close() {
    ++generation;mode = token = null;form.reset();form.hidden = true;
    for (const input of form.querySelectorAll('input')) input.disabled = true;
    $('login-form').hidden = false;$('forgot-password').hidden = false;
    feedback.textContent = '';submit.disabled = false;
  }
  function show(selected) {
    close();mode = selected;$('login-form').hidden = true;$('forgot-password').hidden = true;form.hidden = false;
    for (const input of $('login-form').querySelectorAll('input')) input.disabled = true;
    for (const [id, visible] of [['recovery-username', selected === 'forgot'], ['recovery-password', selected === 'reset'], ['recovery-confirm', selected === 'reset']]) {
      const input = $(id);input.closest('label').hidden = !visible;input.disabled = !visible;input.required = visible;
    }
    $('email-recovery-title').textContent = selected === 'forgot' ? i18nText("ui.forgot_password") : selected === 'reset' ? i18nText("ui.choose_a_new_password") : i18nText("ui.verify_recovery_email");
    submit.textContent = selected === 'forgot' ? i18nText("ui.request_reset_link") : selected === 'reset' ? i18nText("ui.reset_password") : i18nText("ui.verify_email");
    $('email-recovery-hint').textContent = selected === 'forgot' ? i18nText("ui.enter_your_username_recovery_requires_a_verified_email_address_and_working_email_deli") :

    selected === 'reset' ? i18nText("ui.use_at_least_15_characters_recovery_signs_out_your_sessions_and_revokes_your_passkeys") : i18nText("ui.confirm_that_this_mailbox_should_be_used_to_recover_your_timekeeper_account");

    (selected === 'forgot' ? $('recovery-username') : selected === 'reset' ? $('recovery-password') : submit).focus();
  }
  $('forgot-password').addEventListener('click', () => {signIn();show('forgot');});
  $('email-recovery-back').addEventListener('click', () => {signIn();$('username').focus();});
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (mode === 'reset') {
      try {validatePassword($('recovery-password').value, $('recovery-confirm').value);}
      catch (error) {feedback.textContent = error.message;return;}
    }
    const current = generation,selected = mode;
    const body = selected === 'forgot' ? { username: $('recovery-username').value } :
    selected === 'reset' ? { token, password: $('recovery-password').value } : { token };
    if (selected !== 'forgot' && !token) return;
    submit.disabled = true;feedback.textContent = '';
    void request(`email/${selected === 'forgot' ? 'forgot' : selected === 'reset' ? 'reset' : 'verify'}`, body).then((result) => {
      if (current !== generation) return;
      token = null;form.reset();
      feedback.textContent = selected === 'forgot' ? result.message :
      selected === 'reset' ? i18nText("ui.password_reset_sign_in_with_your_new_password_add_replacement_passkeys_in_my_account_") : i18nText("ui.recovery_email_verified_sign_in_to_review_your_account");

      if (selected === 'forgot') submit.disabled = false;
    }).catch((error) => {
      if (current !== generation) return;
      if (selected === 'forgot') {
        feedback.textContent = i18nText("ui.the_reset_request_could_not_be_confirmed_try_again_or_contact_the_server_operator");submit.disabled = false;
      } else {
        token = null;$('recovery-password').value = '';$('recovery-confirm').value = '';
        feedback.textContent = error.status ? i18nText("ui.this_link_could_not_be_used_it_may_have_expired_or_been_replaced_request_a_fresh_link") : i18nText("ui.the_result_could_not_be_confirmed_sign_in_to_check_the_change_before_requesting_anoth");

      }
    });
  });
  // The language bootstrap may still be loading when the sign-in HTML appears.
  // Enable this action only once its handlers can receive the first click.
  $('forgot-password').disabled = false;
  return { close, async initializeFromLocation() {
      const match = /^#(reset-password|verify-email)\/(.*)$/.exec(location.hash);
      if (!match) return false;
      history.replaceState(null, '', location.pathname + location.search);
      signIn();show(match[1] === 'reset-password' ? 'reset' : 'verify');
      const current = generation;token = /^[A-Za-z0-9_-]{43}$/.test(match[2]) ? match[2] : null;submit.disabled = true;
      try {
        if (!token) throw new Error();
        await request('email/preview', { token, purpose: mode === 'reset' ? 'RESET' : 'VERIFY' });
        if (current === generation) submit.disabled = false;
      } catch {
        if (current === generation) {token = null;feedback.textContent = i18nText("ui.this_link_could_not_be_used_request_a_fresh_link_from_the_current_server_address_or_c");}
      }
      return true;
    } };
}

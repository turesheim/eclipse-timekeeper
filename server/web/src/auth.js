import { t as i18nText } from "./i18n.js";import { Api, ApiError } from './api.js';
import { savedToken, forgetToken, isSecureOrigin } from './credentials.js';
import { connectAccount, closeAccount } from './account-page.js';
import { emailRecovery } from './email-recovery.js';
import { PasskeyCeremony, passkeysSupported, canceledPasskey } from './webauthn.js';

const $ = (id) => document.getElementById(id);
export function authentication({ connect, clear, updateAccount, refreshProfile, renderCalendars, renderLabels,renderFocus,beforeSignOut }) {
  let active = null,generation = 0,recovery = null;
  let passkeysEnabled = false,passkeyBusy = false,passkeyStage = '',statusRequest = null;
  const errorText = (error) => error instanceof ApiError ? error.message : i18nText("ui.unable_to_sign_in_use_https_and_check_your_account_details");
  function mode() {
    const value = $('auth-mode').value,code = ['setup', 'recover'].includes(value),token = value === 'token';
    for (const [field, visible] of [['code', code], ['username', !token], ['setup-email', value === 'setup'], ['password', !token], ['confirm', code], ['remember', value === 'login'], ['token', token]]) {
      $(`${field}-field`).hidden = !visible;
      for (const input of $(`${field}-field`).querySelectorAll('input')) {
        input.disabled = !visible || $('login-form').hidden;
        input.required = visible && !$('login-form').hidden && input.type !== 'checkbox';
      }
    }
    $('password').autocomplete = code ? 'new-password' : 'current-password';
    $('forgot-password').hidden = value !== 'login' || $('login-form').hidden;
    $('passkey-sign-in').hidden = value !== 'login';$('passkey-support').hidden = value !== 'login';
    $('passkey-sign-in').disabled = passkeyBusy || !passkeysEnabled || !passkeysSupported();
    $('passkey-cancel').hidden = !passkeyBusy;$('passkey-cancel').disabled = passkeyStage === 'finish';
    $('passkey-support').textContent = passkeyBusy ? i18nText("ui.complete_the_request_in_your_browser_or_password_manager") : !passkeysEnabled ? i18nText("ui.passkey_sign_in_requires_a_configured_https_hostname_you_can_use_your_username_and_pa") :
    !passkeysSupported() ? i18nText("ui.passkeys_are_unavailable_in_this_browser_you_can_use_your_username_and_password") :
    '';
    $('auth-hint').textContent = value === 'recover' ? i18nText("ui.recovery_revokes_passkeys_sessions_and_api_tokens_use_the_operator_s_one_time_code_an") : code ? i18nText("ui.get_a_one_time_code_from_the_server_operator_new_passwords_must_contain_at_least_15_c") : token ? i18nText("ui.exchange_an_existing_api_token_for_a_browser_session_the_token_is_never_saved_in_brow") : i18nText("ui.use_your_timekeeper_account_api_tokens_are_managed_after_signing_in");
  }
  $('auth-mode').addEventListener('change', mode);mode();
  async function publicRequest(path, body) {
    if (!isSecureOrigin()) throw new Error();
    const response = await fetch(`/api/v1/auth/${path}`, { method: body ? 'POST' : 'GET', credentials: 'same-origin', redirect: 'error', referrerPolicy: 'origin', cache: 'no-store', headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    const data = response.status === 204 ? null : await response.json();if (!response.ok) throw new ApiError(data.code, response.status);return data;
  }
  function loadStatus() {
    // One in-flight status request also prevents simultaneous first loads from
    // racing to install different HttpOnly browser-binding cookies.
    if (!statusRequest) statusRequest = publicRequest('status').finally(() => {statusRequest = null;});
    return statusRequest;
  }
  const loginPasskey = new PasskeyCeremony(publicRequest);
  function passkeyState(busy, stage = '') {
    passkeyBusy = busy;passkeyStage = stage;$('auth-mode').disabled = busy;
    $('login-form').querySelector('[type="submit"]').disabled = busy;mode();
  }
  function capabilities(status) {
    passkeysEnabled = status.passkeysEnabled === true;$('login-build').textContent = status.spkVersion ? i18nText("ui.spk_61eb05", { value0: status.spkVersion }) : '';mode();return status;
  }
  async function attach(data, current) {
    if (current !== generation) return;
    const session = new Api(null, undefined, data);active = session;forgetToken();
    $('login-form').reset();mode();$('login-error').textContent = '';
    if ((await connect(session, data)) === false) return;
    if (current === generation && active === session) await connectAccount(session, data, reset, updateAccount, refreshProfile, renderCalendars, renderLabels, renderFocus);
  }
  function reset(message = '', retry = null, {restoring = false} = {}) {
    loginPasskey.cancel();passkeyState(false);
    ++generation;active?.close();active = null;closeAccount();clear({restoring});
    recovery?.close();
    $('login-form').reset();
    if (retry) {$('auth-mode').value = retry.mode;$('username').value = retry.username;}
    mode();$('login-error').textContent = message;
    if (!restoring) {const current = generation;void loadStatus().then((status) => {if (current === generation) capabilities(status);}).catch(() => {});}
  }
  async function exchange(token, current) {
    if (!isSecureOrigin()) throw new Error();
    capabilities(await loadStatus());
    if (current !== generation) return;
    const bearer = new Api(token);
    try {const { data } = await bearer.request('/auth/token-login', { method: 'POST' });forgetToken(token);await attach(data, current);}
    catch (error) {if (error.status === 401) forgetToken(token);throw error;} finally
    {bearer.close();}
  }
  $('login-form').addEventListener('submit', (event) => {
    event.preventDefault();
    if (passkeyBusy) return;
    const selected = $('auth-mode').value;
    if (['setup', 'recover'].includes(selected) && $('password').value !== $('password-confirm').value) {
      $('login-error').textContent = i18nText("ui.passwords_do_not_match");$('password-confirm').focus();return;
    }
    const current = ++generation,submit = $('login-form').querySelector('button');submit.disabled = true;$('login-error').textContent = '';
    const body = { username: $('username').value, password: $('password').value };
    const token = $('token').value;$('password').value = '';$('password-confirm').value = '';$('token').value = '';
    if (selected === 'login') body.remember = $('remember').checked;else
    if (['setup', 'recover'].includes(selected)) {body.code = $('setup-code').value.trim();body.displayName = body.username;$('setup-code').value = '';}
    if (selected === 'setup') body.email = $('setup-email').value;
    void (async () => {
      let issued = false;
      try {
        if (selected === 'token') await exchange(token, current);else
        {capabilities(await loadStatus());if (current !== generation) return;const data = await publicRequest(selected, body);issued = true;await attach(data, current);}
      }
      catch (error) {
        if (current !== generation) return;
        const retry = { mode: issued ? 'login' : selected, username: body.username };
        let message = errorText(error);
        if (error.status === 401 && selected !== 'token') {
          if (issued && selected === 'setup') message = i18nText("ui.your_account_was_created_but_the_browser_session_could_not_be_used_sign_in_with_your_");else
          if (!issued && selected === 'setup') message = i18nText("ui.the_setup_code_was_not_accepted_use_the_code_from_the_latest_installation_it_may_have");else
          if (!issued && selected === 'recover') message = i18nText("ui.the_recovery_code_was_not_accepted_ask_the_server_operator_for_a_new_recovery_code");else
          if (!issued && selected === 'login') {
            message = i18nText("ui.the_username_or_password_was_not_accepted");
            try {
              if ((await loadStatus()).setupRequired) {
                retry.mode = 'setup';
                message = i18nText("ui.first_administrator_setup_has_not_completed_enter_the_setup_code_from_the_latest_inst");
              }
            } catch {/* Keep the credential error if status is unavailable. */}
          }
        }
        if (current === generation) reset(message, retry);
      } finally
      {submit.disabled = false;}
    })();
  });
  $('passkey-sign-in').addEventListener('click', () => {
    if (passkeyBusy || !passkeysEnabled || !passkeysSupported()) return;
    const current = ++generation;$('password').value = '';$('login-error').textContent = '';passkeyState(true);
    void (async () => {
      try {
        capabilities(await loadStatus());if (current !== generation) return;
        if (!passkeysEnabled) {$('login-error').textContent = i18nText("ui.passkey_sign_in_is_unavailable_for_this_server_use_your_username_and_password");return;}
        const data = await loginPasskey.run('passkeys/login/options', 'passkeys/login/finish', { remember: $('remember').checked },
        { valid: () => current === generation, stage: (stage) => {if (current === generation) passkeyState(true, stage);} });
        await attach(data, current);
      } catch (error) {
        if (current !== generation) return;
        $('login-error').textContent = canceledPasskey(error) ? i18nText("ui.passkey_request_canceled_you_can_sign_in_with_your_username_and_password") :
        error instanceof ApiError ? error.message : i18nText("ui.the_passkey_request_could_not_be_completed_use_your_username_and_password");
      } finally {if (current === generation) passkeyState(false);}
    })();
  });
  $('passkey-cancel').addEventListener('click', () => {
    if (!passkeyBusy || passkeyStage === 'finish') return;
    ++generation;loginPasskey.cancel();passkeyState(false);$('login-error').textContent = i18nText("ui.passkey_request_canceled");$('username').focus();
  });
  window.addEventListener('pagehide', () => {++generation;loginPasskey.cancel();});
  $('sign-out').addEventListener('click', () => {
    const session = active;if (!session) return;
    if(beforeSignOut&&!beforeSignOut())return;
    $('sign-out').disabled = true;
    void session.request('/auth/logout', { method: 'POST' }).then(() => {if (session === active) {forgetToken();reset();}}).catch((error) => {
      if (session !== active) return;
      if (error.status === 401) reset();else $('status').textContent = i18nText("ui.sign_out_could_not_be_confirmed_please_retry_while_connected");
    }).finally(() => {$('sign-out').disabled = false;});
  });
  recovery = emailRecovery({ request: publicRequest, signIn: reset });
  window.addEventListener('hashchange', () => {void recovery.initializeFromLocation();});
  return { clear: reset, suspend: () => reset('', null, {restoring: true}), async initialize() {
      if (await recovery.initializeFromLocation()) return;
      const current = ++generation;
      const submit = $('login-form').querySelector('button');submit.disabled = true;
      try {capabilities(await loadStatus());if (current !== generation) return;const session = new Api();const { data } = await session.request('/auth/session');session.close();forgetToken();await attach(data, current);}
      catch (error) {
        if (current !== generation) return;
        if (active) {reset(errorText(error));return;}
        const token = savedToken();
        if (token) {try {await exchange(token, current);return;} catch (failure) {$('login-error').textContent = errorText(failure);}}
        try {
          const status = capabilities(await loadStatus());
          if (current === generation) {
            $('login-build').textContent = status.spkVersion ? i18nText("ui.spk_61eb05", { value0: status.spkVersion }) : '';
            if (status.setupRequired && $('auth-mode').value === 'login') {$('auth-mode').value = 'setup';mode();}
          }
        } catch {/* The sign-in form remains usable during an outage. */}
      } finally {
        submit.disabled = false;
        if (current === generation) {$('startup').hidden = true;if (!active) $('login').hidden = false;}
      }
    } };
}

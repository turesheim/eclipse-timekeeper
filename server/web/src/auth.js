import {Api, ApiError} from './api.js';
import {savedToken, forgetToken, isSecureOrigin} from './credentials.js';
import {connectAccount, closeAccount} from './account-page.js';
import {emailRecovery} from './email-recovery.js';

const $ = id => document.getElementById(id);
export function authentication({connect, clear, updateAccount, refreshProfile}) {
  let active = null, generation = 0, recovery = null;
  const errorText = error => error instanceof ApiError ? error.message : 'Unable to sign in. Use HTTPS and check your account details.';
  function mode() {
    const value = $('auth-mode').value, code = ['setup','recover'].includes(value), token = value === 'token';
    for (const [field, visible] of [['code',code],['username',!token],['setup-email',value === 'setup'],['password',!token],['confirm',code],['remember',value === 'login'],['token',token]]) {
      $(`${field}-field`).hidden = !visible;
      for (const input of $(`${field}-field`).querySelectorAll('input')) {
        input.disabled = !visible;
        input.required = visible && input.type !== 'checkbox';
      }
    }
    $('password').autocomplete = code ? 'new-password' : 'current-password';
    $('forgot-password').hidden = value !== 'login';
    $('auth-hint').textContent = code ? 'Get a one-time code from the server operator. New passwords must contain at least 15 characters.' : token ? 'Exchange an existing API token for a browser session. The token is never saved in browser storage.' : 'Use your Timekeeper account. API tokens are managed after signing in.';
  }
  $('auth-mode').addEventListener('change',mode); mode();
  async function publicRequest(path, body) {
    if (!isSecureOrigin()) throw new Error();
    const response = await fetch(`/api/v1/auth/${path}`, {method:body ? 'POST':'GET', credentials:'same-origin', redirect:'error', cache:'no-store', headers:{Accept:'application/json', ...(body ? {'Content-Type':'application/json'} : {})}, ...(body ? {body:JSON.stringify(body)} : {})});
    const data = response.status === 204 ? null : await response.json(); if (!response.ok) throw new ApiError(data.code, response.status); return data;
  }
  async function attach(data, current) {
    if (current !== generation) return;
    const session = new Api(null, undefined, data); active = session; forgetToken();
    $('login-form').reset(); mode(); $('login-error').textContent = '';
    await connect(session, data);
    if (current === generation && active === session) await connectAccount(session, data, reset, updateAccount, refreshProfile);
  }
  function reset(message = '', retry = null) {
    ++generation; active?.close(); active = null; closeAccount(); clear();
    recovery?.close();
    $('login-form').reset();
    if (retry) { $('auth-mode').value = retry.mode; $('username').value = retry.username; }
    mode(); $('login-error').textContent = message;
  }
  async function exchange(token, current) {
    if(!isSecureOrigin()) throw new Error();
    const bearer = new Api(token);
    try { const {data} = await bearer.request('/auth/token-login',{method:'POST'}); forgetToken(token); await attach(data,current); }
    catch (error) { if (error.status === 401) forgetToken(token); throw error; }
    finally { bearer.close(); }
  }
  $('login-form').addEventListener('submit', event => {
    event.preventDefault();
    const selected = $('auth-mode').value;
    if (['setup','recover'].includes(selected) && $('password').value !== $('password-confirm').value) {
      $('login-error').textContent = 'Passwords do not match.'; $('password-confirm').focus(); return;
    }
    const current = ++generation, submit = $('login-form').querySelector('button'); submit.disabled = true; $('login-error').textContent = '';
    const body = {username:$('username').value,password:$('password').value};
    const token = $('token').value; $('password').value = ''; $('password-confirm').value = ''; $('token').value = '';
    if (selected === 'login') body.remember = $('remember').checked;
    else if (['setup','recover'].includes(selected)) { body.code = $('setup-code').value.trim(); body.displayName = body.username; $('setup-code').value = ''; }
    if (selected === 'setup') body.email = $('setup-email').value;
    void (async () => {
      let issued = false;
      try {
        if(selected === 'token') await exchange(token,current);
        else { const data = await publicRequest(selected,body); issued = true; await attach(data,current); }
      }
      catch(error) {
        if(current !== generation) return;
        const retry = {mode:issued ? 'login' : selected, username:body.username};
        let message = errorText(error);
        if(error.status === 401 && selected !== 'token') {
          if(issued && selected === 'setup') message = 'Your account was created, but the browser session could not be used. Sign in with your new username and password.';
          else if(!issued && selected === 'setup') message = 'The setup code was not accepted. Use the code from the latest installation; it may have expired or been replaced. Administrator setup has not completed with this request.';
          else if(!issued && selected === 'recover') message = 'The recovery code was not accepted. Ask the server operator for a new recovery code.';
          else if(!issued && selected === 'login') {
            message = 'The username or password was not accepted.';
            try {
              if((await publicRequest('status')).setupRequired) {
                retry.mode = 'setup';
                message = 'First administrator setup has not completed. Enter the setup code from the latest installation and choose your username and password.';
              }
            } catch { /* Keep the credential error if status is unavailable. */ }
          }
        }
        if(current === generation) reset(message,retry);
      }
      finally { submit.disabled = false; }
    })();
  });
  $('sign-out').addEventListener('click', () => {
    const session = active; if(!session) return;
    $('sign-out').disabled = true;
    void session.request('/auth/logout',{method:'POST'}).then(() => {if(session === active) {forgetToken(); reset();}}).catch(error => {
      if(session !== active) return;
      if(error.status === 401) reset(); else $('status').textContent = 'Sign-out could not be confirmed. Please retry while connected.';
    }).finally(() => {$('sign-out').disabled = false;});
  });
  recovery = emailRecovery({request:publicRequest, signIn:reset});
  window.addEventListener('hashchange', () => {void recovery.initializeFromLocation();});
  return {clear:reset, async initialize() {
    if (await recovery.initializeFromLocation()) return;
    const current = ++generation;
    const submit=$('login-form').querySelector('button');submit.disabled=true;
    try { const session = new Api(); const {data} = await session.request('/auth/session'); session.close(); forgetToken(); await attach(data,current); }
    catch(error) {
      if(current !== generation) return;
      const token = savedToken();
      if(token) {try {await exchange(token,current); return;} catch(failure) {$('login-error').textContent = errorText(failure);}}
      try {
        const status = await publicRequest('status');
        if(current === generation) {
          $('login-build').textContent = status.spkVersion ? `SPK ${status.spkVersion}` : '';
          if(status.setupRequired && $('auth-mode').value === 'login') {$('auth-mode').value = 'setup';mode();}
        }
      } catch { /* The sign-in form remains usable during an outage. */ }
    } finally {submit.disabled=false;}
  }};
}

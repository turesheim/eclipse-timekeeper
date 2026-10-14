import {Api, ApiError} from './api.js';
import {savedToken, forgetToken, isSecureOrigin} from './credentials.js';
import {openAccount, closeAccount} from './account.js';

const $ = id => document.getElementById(id);
export function authentication({connect, clear, updateAccount}) {
  let active = null, generation = 0;
  const errorText = error => error instanceof ApiError ? error.message : 'Unable to sign in. Use HTTPS and check your account details.';
  function mode() {
    const value = $('auth-mode').value, code = ['setup','recover'].includes(value), token = value === 'token';
    for (const [field, visible] of [['code',code],['username',!token],['password',!token],['display',code],['remember',value === 'login'],['token',token]]) {
      $(`${field}-field`).hidden = !visible;
      for (const input of $(`${field}-field`).querySelectorAll('input')) input.required = visible && input.type !== 'checkbox';
    }
    $('password').autocomplete = code ? 'new-password' : 'current-password';
    $('auth-hint').textContent = code ? 'Get a one-time code from the server operator. New passwords must contain at least 15 characters.' : token ? 'Exchange an existing API token for a browser session. The token is never saved in browser storage.' : 'Use your Timekeeper account. API tokens are managed after signing in.';
  }
  $('auth-mode').addEventListener('change',mode); mode();
  async function publicRequest(path, body) {
    if (!isSecureOrigin()) throw new Error();
    const response = await fetch(`/api/v1/auth/${path}`, {method:body ? 'POST':'GET', credentials:'same-origin', redirect:'error', cache:'no-store', headers:{Accept:'application/json', ...(body ? {'Content-Type':'application/json'} : {})}, ...(body ? {body:JSON.stringify(body)} : {})});
    const data = await response.json(); if (!response.ok) throw new ApiError(data.code, response.status); return data;
  }
  async function attach(data, current) {
    if (current !== generation) return;
    const session = new Api(null, undefined, data); active = session; forgetToken();
    $('login-form').reset(); mode(); $('login-error').textContent = '';
    await connect(session, data);
  }
  function reset(message = '') {
    ++generation; active?.close(); active = null; closeAccount(); clear();
    $('login-form').reset(); mode(); $('login-error').textContent = message;
  }
  async function exchange(token, current) {
    if(!isSecureOrigin()) throw new Error();
    const bearer = new Api(token);
    try { const {data} = await bearer.request('/auth/token-login',{method:'POST'}); forgetToken(token); await attach(data,current); }
    catch (error) { if (error.status === 401) forgetToken(token); throw error; }
    finally { bearer.close(); }
  }
  $('login-form').addEventListener('submit', event => {
    event.preventDefault(); const current = ++generation, submit = $('login-form').querySelector('button'); submit.disabled = true; $('login-error').textContent = '';
    const selected = $('auth-mode').value;
    const body = {username:$('username').value,password:$('password').value};
    const token = $('token').value; $('password').value = ''; $('token').value = '';
    if (selected === 'login') body.remember = $('remember').checked;
    else { body.code = $('setup-code').value; body.displayName = $('display-name').value; $('setup-code').value = ''; }
    void (async () => {
      try { if(selected === 'token') await exchange(token,current); else await attach(await publicRequest(selected,body),current); }
      catch(error) { if(current === generation) reset(errorText(error)); }
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
  $('account').addEventListener('click', () => {if(active) void openAccount(active,reset,updateAccount);});
  return {clear:reset, async initialize() {
    const current = ++generation;
    const submit=$('login-form').querySelector('button');submit.disabled=true;
    try { const session = new Api(); const {data} = await session.request('/auth/session'); session.close(); forgetToken(); await attach(data,current); }
    catch(error) {
      if(current !== generation) return;
      const token = savedToken();
      if(token) {try {await exchange(token,current); return;} catch(failure) {$('login-error').textContent = errorText(failure);}}
      try {const status = await publicRequest('status'); if(current === generation && status.setupRequired && $('auth-mode').value === 'login') {$('auth-mode').value = 'setup';mode();}} catch { /* The sign-in form remains usable during an outage. */ }
    } finally {submit.disabled=false;}
  }};
}

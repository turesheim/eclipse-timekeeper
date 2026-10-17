import {instantText, localDateTimeToInstant} from './model.js';
import {node, button, field, passwordField, validatePassword, section, form, table} from './account-ui.js';
import {renderRecoveryEmails} from './recovery-emails.js';
import {renderImports} from './imports.js';
import {renderPasskeys} from './passkeys.js';

export async function renderAccount(view, part) {
  const [loaded, profile] = await Promise.all([view.session.request('/auth/account'), view.session.request('/me')]);
  if (!view.valid()) return;
  const account = loaded.data, zone = profile.data.zoneId;
  if (part === 'passkeys') {await renderPasskeys(view, zone);return;}
  if (part === 'import') {await renderImports(view, zone); return;}
  if (part === 'tokens') {await renderTokens(view, zone); return;}
  if (account.state === 'UNCLAIMED') {
    view.root.append(node('p', 'Ask the server operator for an account recovery code to add a username and password to this existing account.', 'alert alert-info'));
    if (part === 'password') return;
  } else if (part === 'password') {
    const password = form(view, 'Change password', 'Change password and sign out', async () => {
      validatePassword(next.value, confirm.value);
      try {await view.session.request('/auth/password', {method: 'POST', body: {currentPassword: old.value, newPassword: next.value}});}
      catch (error) {
        if (error.status === 401) {await view.session.request('/auth/session'); throw new Error('The current password was not accepted. Your draft is kept.');}
        throw error;
      }
      if (view.valid()) view.signOut('Password changed. Sign in again. Your existing sessions and API tokens have been revoked.');
    });
    password.value.append(node('p', 'Use at least 15 characters. Changing your password signs out all of your sessions and revokes all of your API tokens, including Eclipse connections.', 'alert alert-warning'));
    const old = passwordField(password.value, 'Current password', true), next = passwordField(password.value, 'New password'), confirm = passwordField(password.value, 'Confirm new password');
    password.finish(); return;
  } else {
    const identity = form(view, 'Profile', 'Save profile', async () => {
      const reply = await view.session.request('/auth/account', {method: 'PUT', etag: loaded.etag, body: {username: username.value, displayName: display.value.trim() || username.value}});
      if (!view.valid()) return; loaded.etag = reply.etag; view.changed(reply.data); view.clean(identity.value); identity.feedback.textContent = 'Profile saved.';
    });
    const username = field(identity.value, 'Account username', account.username, {autocomplete: 'username', maxLength: 64});
    const display = field(identity.value, 'Display name (optional)', account.displayName === account.username ? '' : account.displayName, {required: false, autocomplete: 'nickname', maxLength: 200});
    identity.value.append(node('p', 'Leave display name blank to use your username. Renaming your account preserves your private records.', 'hint')); identity.finish();
  }
  const preferences = form(view, 'Time zone', 'Save time zone', async () => {
    try {new Intl.DateTimeFormat(undefined, {timeZone: input.value.trim()});} catch {throw new Error('Enter a valid time zone, for example Europe/Oslo.');}
    const reply = await view.session.request('/me', {method: 'PUT', etag: profile.etag, body: {zoneId: input.value.trim()}});
    if (!view.valid()) return; profile.etag = reply.etag; view.clean(preferences.value); preferences.feedback.textContent = 'Time zone saved. Stored activity times are unchanged.';
    // The preference write is confirmed. Refreshing hidden tracking reports
    // must not keep account navigation reserved after the save succeeds.
    void view.refreshProfile(reply).catch(() => {});
  });
  const input = field(preferences.value, 'Time zone', zone), preview = node('p', '', 'hint'); input.setAttribute('aria-describedby', 'account-zone-preview'); preview.id = 'account-zone-preview';
  const updatePreview = () => {try {preview.textContent = `${input.value}: ${instantText(new Date().toISOString(), input.value.trim())}`;} catch {preview.textContent = 'Enter a valid IANA time zone.';}};
  input.addEventListener('input', updatePreview); updatePreview();
  const local = Intl.DateTimeFormat().resolvedOptions().timeZone;
  if (local) preferences.value.append(button(`Use this device's time zone (${local})`, () => {input.value = local; view.dirty.add(preferences.value); updatePreview();}));
  preferences.value.append(preview); preferences.finish();
  if (account.state === 'ENABLED') await renderRecoveryEmails(view);
}

async function renderTokens(view, zone) {
  const card = section(view, 'Personal API tokens');
  card.append(node('p', 'Use a separate named token for each Eclipse installation or other client. Token secrets are shown once.'));
  const list = node('div'); card.append(list);
  let listGeneration = 0;
  async function refreshList(feedback, acknowledgement) {
    try {await tokens();}
    catch (error) {
      if (error.status === 401) throw error;
      if (!view.valid()) return;
      feedback.textContent = `${acknowledgement} Unable to refresh the token list.`;
      feedback.append(' ', button('Refresh token list', () => void view.run(tokens, {feedback})));
    }
  }
  async function tokens() {
    const current = ++listGeneration;
    let reply;
    try {reply = await view.session.request('/auth/tokens');}
    catch (error) {if (view.valid() && (error.status === 401 || current === listGeneration)) throw error; return;}
    if (!view.valid() || current !== listGeneration) return;
    const {data} = reply;
    list.replaceChildren();
    if (!data.items.length) {list.append(node('p', 'No API tokens yet.', 'hint')); return;}
    const rows = data.items.map(token => {
      const actions = node('div', undefined, 'token-actions');
      const expired = token.expiresAt && Date.parse(token.expiresAt) <= Date.now();
      if (!token.revoked) {
        const revoke = button('Revoke', () => {
          actions.replaceChildren(node('p', `Revoke “${token.name}”? Clients and browser sessions using this token will lose access. Your password sign-in sessions remain valid.`));
          const feedback = node('p', '', 'form-feedback'); feedback.setAttribute('role', 'status');
          const confirm = button('Revoke token', () => void view.run(async () => {
            await view.session.request(`/auth/tokens/${token.id}`, {method: 'DELETE'}); if (!view.valid()) return;
            feedback.textContent = 'Token revoked.'; actions.replaceChildren(feedback); await refreshList(feedback, 'Token revoked.');
          }, {submit: confirm, feedback, write: true}), 'btn btn-danger');
          actions.append(confirm, button('Cancel', () => {actions.replaceChildren(revoke);}), feedback);
        }); revoke.disabled = !view.canWrite; actions.append(revoke);
      }
      return [token.name, token.permissions.includes('write') ? 'Read/write' : 'Read-only', token.expiresAt ? instantText(token.expiresAt, zone) : 'No expiry', token.lastUsedAt ? instantText(token.lastUsedAt, zone) : 'Never used', token.revoked ? 'Revoked' : expired ? 'Expired' : 'Active', actions];
    });
    list.append(table(['Name', 'Access', 'Expiry', 'Last used', 'Status', 'Actions'], rows));
  }
  await tokens(); if (!view.valid()) return;
  const create = form(view, 'Create API token', 'Create token', async () => {
    const expiresAt = expiry.value ? localDateTimeToInstant(expiry.value, zone) : null;
    const {data} = await view.session.request('/auth/tokens', {method: 'POST', body: {name: name.value, permissions: access.value === 'write' ? ['read', 'write'] : ['read'], expiresAt}});
    if (!view.valid()) return;
    secret.replaceChildren(node('p', 'Copy this token now. It will not be shown again.', 'alert alert-info'));
    const value = field(secret, 'New API token', data.token, {required: false}); value.readOnly = true; value.spellcheck = false;
    const feedback = node('p', '', 'hint'); feedback.setAttribute('role', 'status');
    secret.append(button('Copy token', async () => {
      if (!view.valid()) return;
      try {await navigator.clipboard.writeText(value.value); if (view.valid()) feedback.textContent = 'Token copied.';}
      catch {if (view.valid()) {value.focus(); value.select(); feedback.textContent = 'Copy the selected token using your browser.';}}
    }), button('Done', () => {value.value = ''; secret.replaceChildren();}), feedback);
    name.value = ''; expiry.value = ''; view.clean(create.value); create.feedback.textContent = 'Token created.'; await refreshList(create.feedback, 'Token created.');
  });
  create.value.append(node('p', `Expiry times use ${zone}.`, 'hint'));
  const name = field(create.value, 'Token name', '', {maxLength: 200});
  const access = field(create.value, 'Token access', 'write', {choices: [['read', 'Read-only'], ['write', 'Read/write']]});
  const expiry = field(create.value, 'Token expiry (local time, optional)', '', {type: 'datetime-local', required: false});
  create.finish(); const secret = node('div', undefined, 'new-token'); create.value.append(secret);
}

import {ownerHeader} from './api.js';
import {node, link, field, passwordField, validatePassword, section, form, table} from './account-ui.js';

export async function renderUsers(view, key) {
  const {data} = await view.session.request('/auth/users'); if (!view.valid()) return;
  if (!key) {
    const card = section(view, 'Independent accounts');
    card.append(node('p', 'Manage account access. Administrator status does not grant access to another user’s clients, projects, activities, work sessions or reports.'), link('Add user', '#users/new', 'btn btn-primary'));
    const rows = [...data.users].sort((a, b) => (a.username || a.displayName).localeCompare(b.username || b.displayName)).map(user => [user.displayName, user.username || 'Unclaimed', user.administrator ? 'Administrator' : 'User', user.state === 'ENABLED' ? 'Enabled' : user.state === 'DISABLED' ? 'Disabled' : 'Unclaimed', link('Edit', `#users/${ownerHeader(user.ownerId)}`)]);
    card.append(table(['Name', 'Username', 'Role', 'Access', 'Actions'], rows)); return;
  }
  view.root.append(link('Back to users', '#users'));
  const creating = key === 'new';
  const loaded = creating ? null : await view.session.request(`/auth/users/${encodeURIComponent(key)}`);
  if (!view.valid()) return;
  const user = loaded?.data.user;
  if (user?.state === 'UNCLAIMED') {view.root.append(node('p', 'The operator must issue an explicit owner-bound recovery code to claim this account.', 'alert alert-info')); return;}
  const edit = form(view, creating ? 'Add user' : `Edit ${user.username}`, creating ? 'Create user' : 'Save user', async () => {
    if (creating) validatePassword(password.value, confirmation.value);
    else if ((user.state === 'ENABLED' && !enabled.checked) || user.administrator !== admin.checked) {
      if (!window.confirm(`Change access for “${user.username}”? This revokes their sessions and API tokens. Their work data will be retained.`)) return;
    }
    const body = {username: username.value, displayName: display.value.trim() || username.value, administrator: admin.checked, ...(creating ? {password: password.value} : {enabled: enabled.checked})};
    const reply = await view.session.request(creating ? '/auth/users' : `/auth/users/${encodeURIComponent(key)}`, {method: creating ? 'POST' : 'PUT', etag: loaded?.etag, body});
    if (!view.valid()) return;
    view.clean(edit.value);
    if (!creating && user.ownerId === view.account.ownerId) {
      view.changed(reply.data.user);
      if (!enabled.checked || user.administrator !== admin.checked) {view.signOut('Account access changed. Sign in again.'); return;}
    }
    view.navigate('#users');
  });
  const username = field(edit.value, creating ? 'New user username' : 'User username', user?.username, {autocomplete: creating ? 'off' : 'username', maxLength: 64});
  const display = field(edit.value, 'Display name (optional)', user?.displayName === user?.username ? '' : user?.displayName, {required: false, maxLength: 200});
  let password, confirmation, enabled;
  if (creating) {password = passwordField(edit.value, 'Temporary password'); confirmation = passwordField(edit.value, 'Confirm temporary password');}
  else enabled = field(edit.value, 'Enabled', user.state === 'ENABLED', {type: 'checkbox'});
  const admin = field(edit.value, creating ? 'New user is administrator' : 'Administrator', user?.administrator || false, {type: 'checkbox'});
  if (!creating && user.administrator && user.state === 'ENABLED' && data.users.filter(value => value.administrator && value.state === 'ENABLED').length === 1) {
    enabled.disabled = true; admin.disabled = true;
    edit.value.append(node('p', 'This is the last enabled administrator. Create or enable another administrator before disabling this account or removing its administrator role.', 'alert alert-info'));
  }
  edit.value.append(node('p', 'Disabling an account or changing its role revokes its sessions and API tokens. Disabling retains private work data; enabling does not restore revoked credentials.', 'hint'));
  edit.finish();
}

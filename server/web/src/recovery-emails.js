import {node, button, field, passwordField, form} from './account-ui.js';

export async function renderRecoveryEmails(view) {
  const loaded = await Promise.all(['PRIMARY', 'BACKUP'].map(slot => view.session.request(`/auth/email/addresses/${slot}`)));
  if (!view.valid()) return;
  for (let index = 0; index < loaded.length; index++) {
    const slot = index === 0 ? 'PRIMARY' : 'BACKUP', label = index === 0 ? 'Primary' : 'Backup';
    const current = loaded[index];
    let state = current.data;
    const edit = form(view, `${label} recovery email${index ? ' (optional)' : ''}`, `Save ${label.toLowerCase()} email`, async () => {
      const address = input.value.trim() || null;
      try {
        const reply = await view.session.request(`/auth/email/addresses/${slot}`, {method: 'PUT', etag: current.etag, body: {email: address, currentPassword: password.value}});
        if (!view.valid()) return;
        state = reply.data; current.etag = reply.etag; password.value = ''; view.clean(edit.value); paint();
        edit.feedback.textContent = address ? 'Address saved. Verification delivery was requested. Check your inbox; if no message arrives, ask the operator to check email configuration.' : 'Backup address removed.';
      } catch (error) {
        if (error.status === 401) {await view.session.request('/auth/session'); throw new Error('The current password was not accepted. Your draft is kept.');}
        if (error.status === 503) throw new Error('Recovery email delivery is unavailable. Ask the server operator to check email configuration.');
        throw error;
      }
    });
    const input = field(edit.value, `${label} email`, state.pendingAddress || state.verifiedAddress || '', {type: 'email', autocomplete: 'email', required: !index, maxLength: 254});
    const password = passwordField(edit.value, `Current password for ${label.toLowerCase()} email`, true);
    const status = node('p', '', 'hint'); edit.value.append(status);
    const resend = button(`Resend ${label.toLowerCase()} verification`, () => {
      void view.run(async () => {
        await view.session.request(`/auth/email/addresses/${slot}/resend`, {method: 'POST', body: {}});
        if (view.valid()) edit.feedback.textContent = 'Verification delivery requested. Check your inbox, or contact the operator if delivery is unavailable.';
      }, {submit: resend, feedback: edit.feedback, write: true});
    });
    const paint = () => {
      status.textContent = [state.verifiedAddress ? `Verified: ${state.verifiedAddress}.` : 'No verified recovery address.', state.pendingAddress ? `Awaiting verification: ${state.pendingAddress}.` : ''].filter(Boolean).join(' ');
      resend.hidden = !state.pendingAddress; resend.disabled = !view.canWrite;
    };
    paint(); edit.value.append(resend, node('p', index ? 'Leave blank to remove the backup. A verified backup can recover your account without access to your primary mailbox.' : 'Verify an address to enable email recovery. Its existing verified address remains usable while a replacement awaits confirmation.', 'hint')); edit.finish();
  }
}

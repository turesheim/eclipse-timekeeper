import {validatePassword} from './account-ui.js';

const $ = id => document.getElementById(id);

// Recovery tokens live only in this closure; remove their fragment before any requests/navigation.
export function emailRecovery({request, signIn}) {
  let mode = null, token = null, generation = 0;
  const form = $('email-recovery-form'), submit = form.querySelector('[type="submit"]');
  const feedback = $('email-recovery-feedback');

  function close() {
    ++generation; mode = token = null; form.reset(); form.hidden = true;
    $('login-form').hidden = false; $('forgot-password').hidden = false;
    feedback.textContent = ''; submit.disabled = false;
  }
  function show(selected) {
    close(); mode = selected; $('login-form').hidden = true; $('forgot-password').hidden = true; form.hidden = false;
    for (const [id, visible] of [['recovery-username', selected === 'forgot'], ['recovery-password', selected === 'reset'], ['recovery-confirm', selected === 'reset']]) {
      const input = $(id); input.closest('label').hidden = !visible; input.disabled = !visible; input.required = visible;
    }
    $('email-recovery-title').textContent = selected === 'forgot' ? 'Forgot password?' : selected === 'reset' ? 'Choose a new password' : 'Verify recovery email';
    submit.textContent = selected === 'forgot' ? 'Request reset link' : selected === 'reset' ? 'Reset password' : 'Verify email';
    $('email-recovery-hint').textContent = selected === 'forgot'
      ? 'Enter your username. Recovery requires a verified email address and working email delivery. Otherwise, contact the server operator.'
      : selected === 'reset' ? 'Use at least 15 characters. Recovery signs out your sessions and revokes your API tokens, including Eclipse connections.'
        : 'Confirm that this mailbox should be used to recover your Timekeeper account.';
    (selected === 'forgot' ? $('recovery-username') : selected === 'reset' ? $('recovery-password') : submit).focus();
  }
  $('forgot-password').addEventListener('click', () => {signIn(); show('forgot');});
  $('email-recovery-back').addEventListener('click', () => {signIn(); $('username').focus();});
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (mode === 'reset') {
      try {validatePassword($('recovery-password').value, $('recovery-confirm').value);}
      catch (error) {feedback.textContent = error.message; return;}
    }
    const current = generation, selected = mode;
    const body = selected === 'forgot' ? {username: $('recovery-username').value}
      : selected === 'reset' ? {token, password: $('recovery-password').value} : {token};
    if (selected !== 'forgot' && !token) return;
    submit.disabled = true; feedback.textContent = '';
    void request(`email/${selected === 'forgot' ? 'forgot' : selected === 'reset' ? 'reset' : 'verify'}`, body).then(result => {
      if (current !== generation) return;
      token = null; form.reset();
      feedback.textContent = selected === 'forgot' ? result.message
        : selected === 'reset' ? 'Password reset. Sign in with your new password. Eclipse connections need new API tokens.'
          : 'Recovery email verified. Sign in to review your account.';
      if (selected === 'forgot') submit.disabled = false;
    }).catch(error => {
      if (current !== generation) return;
      if (selected === 'forgot') {
        feedback.textContent = 'The reset request could not be confirmed. Try again, or contact the server operator.'; submit.disabled = false;
      } else {
        token = null; $('recovery-password').value = ''; $('recovery-confirm').value = '';
        feedback.textContent = error.status ? 'This link could not be used. It may have expired or been replaced. Request a fresh link from the current server address.'
          : 'The result could not be confirmed. Sign in to check the change before requesting another link.';
      }
    });
  });
  return {close, async initializeFromLocation() {
    const match = /^#(reset-password|verify-email)\/(.*)$/.exec(location.hash);
    if (!match) return false;
    history.replaceState(null, '', location.pathname + location.search);
    signIn(); show(match[1] === 'reset-password' ? 'reset' : 'verify');
    const current = generation; token = /^[A-Za-z0-9_-]{43}$/.test(match[2]) ? match[2] : null; submit.disabled = true;
    try {
      if (!token) throw new Error();
      await request('email/preview', {token, purpose: mode === 'reset' ? 'RESET' : 'VERIFY'});
      if (current === generation) submit.disabled = false;
    } catch {
      if (current === generation) {token = null; feedback.textContent = 'This link could not be used. Request a fresh link from the current server address, or contact the server operator.';}
    }
    return true;
  }};
}

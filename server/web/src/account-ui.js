export function node(tag, text, className) {
  const value = document.createElement(tag);
  if (text !== undefined) value.textContent = text;
  if (className) value.className = className;
  return value;
}
export function button(text, action, className = 'btn btn-outline-secondary') {
  const value = node('button', text, className); value.type = 'button';
  value.addEventListener('click', action); return value;
}
export function link(text, hash, className = 'btn btn-outline-secondary') {
  const value = node('a', text, className); value.href = hash; return value;
}
let fieldId = 0;
export function field(form, text, value = '', {type = 'text', required = true, autocomplete = 'off', choices, maxLength} = {}) {
  const wrap = node('div', undefined, 'account-field'), label = node('label', text, 'form-label');
  const input = node(choices ? 'select' : 'input'); input.id = `account-field-${++fieldId}`; label.htmlFor = input.id;
  if (choices) for (const [key, title] of choices) {const option = node('option', title); option.value = key; input.append(option);}
  else {input.type = type; input.autocomplete = autocomplete;}
  input.className = choices ? 'form-select' : type === 'checkbox' ? 'form-check-input' : 'form-control';
  if (type === 'checkbox') {input.checked = !!value; required = false; wrap.className += ' check';}
  else input.value = value ?? '';
  input.required = required; if (maxLength) input.maxLength = maxLength;
  wrap.append(label, input); form.append(wrap); return input;
}
export function passwordField(form, title, current = false) {
  const input = field(form, title, '', {type: 'password', autocomplete: current ? 'current-password' : 'new-password'});
  const reveal = button(`Show ${title.toLowerCase()}`, () => {
    const show = input.type === 'password'; input.type = show ? 'text' : 'password';
    reveal.textContent = `${show ? 'Hide' : 'Show'} ${title.toLowerCase()}`; reveal.setAttribute('aria-pressed', String(show));
  }, 'btn btn-outline-secondary password-reveal');
  reveal.setAttribute('aria-controls', input.id); reveal.setAttribute('aria-pressed', 'false'); input.parentElement.append(reveal);
  return input;
}
export function validatePassword(password, confirmation) {
  if (password !== confirmation) throw new Error('Passwords do not match.');
  if ([...password].length < 15 || new TextEncoder().encode(password).length > 1024) throw new Error('Use at least 15 characters and at most 1024 UTF-8 bytes.');
}
export function section(view, title) {
  const value = node('section', undefined, 'card account-card'); value.append(node('h2', title)); view.root.append(value); return value;
}
export function form(view, title, action, work, {canSubmit = () => true} = {}) {
  const card = section(view, title), value = node('form'), feedback = node('p', '', 'form-feedback'); feedback.setAttribute('role', 'status');
  const submit = node('button', action, 'btn btn-primary'); submit.type = 'submit'; submit.disabled = !view.canWrite || !canSubmit();
  value.addEventListener('input', () => view.dirty.add(value)); value.addEventListener('change', () => view.dirty.add(value));
  value.addEventListener('submit', event => {
    event.preventDefault(); void view.run(() => work({value, feedback, submit}), {submit, feedback, write: true, canSubmit});
  });
  card.append(value);
  return {value, feedback, submit, finish() {value.append(feedback, submit);}};
}
export function table(headings, rows) {
  const wrap = node('div', undefined, 'table-wrap'), table = node('table', undefined, 'table table-hover align-middle record-table mb-0');
  const head = node('thead'), header = node('tr'), body = node('tbody');
  for (const text of headings) {const th = node('th', text); th.scope = 'col'; header.append(th);}
  for (const cells of rows) {
    const row = node('tr'); cells.forEach((value, index) => {
      const cell = node('td'); cell.dataset.label = headings[index];
      cell.append(value instanceof Node ? value : document.createTextNode(value)); row.append(cell);
    }); body.append(row);
  }
  head.append(header); table.append(head, body); wrap.append(table); return wrap;
}

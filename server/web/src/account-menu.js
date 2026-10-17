/** Native disclosure navigation: normal links/buttons keep their keyboard behavior. */
export function accountMenu(menu) {
  const summary = menu.querySelector('summary');
  menu.addEventListener('toggle', () => summary.setAttribute('aria-expanded', String(menu.open)));
  document.addEventListener('click', event => {
    if (!menu.contains(event.target) || event.target.closest('.account-menu-items a, .account-menu-items button')) menu.open = false;
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu.open) {
      event.preventDefault(); menu.open = false; summary.focus();
    }
  });
}

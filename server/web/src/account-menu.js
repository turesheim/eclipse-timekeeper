/** Native disclosure navigation: normal links/buttons keep their keyboard behavior. */
export function accountMenu(menu) {
  const summary = menu.querySelector('summary'), panel = menu.querySelector('.account-menu-items');
  const fit = () => {
    if (menu.open) panel.style.maxHeight = Math.max(0, innerHeight - panel.getBoundingClientRect().top - 8) + 'px';
  };
  menu.addEventListener('toggle', () => {
    summary.setAttribute('aria-expanded', String(menu.open));
    if (menu.open) { panel.scrollTop = 0;fit(); }
  });
  window.addEventListener('resize', fit);
  new ResizeObserver(fit).observe(menu.closest('header') || menu);
  document.addEventListener('click', event => {
    if (!menu.contains(event.target) || event.target.closest('.account-menu-items a, .account-menu-items button')) menu.open = false;
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu.open) {
      event.preventDefault(); menu.open = false; summary.focus();
    }
  });
}

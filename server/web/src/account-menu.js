/** Native disclosure navigation: normal links/buttons keep their keyboard behavior. */
export function accountMenu(menu) {
  const summary = menu.querySelector('summary'), panel = menu.querySelector('.account-menu-items');
  const fit = () => {
    if(!menu.open)return;
    const opensUp=getComputedStyle(panel).getPropertyValue('--account-menu-opens-up').trim()==='1';
    const bounds=panel.getBoundingClientRect();
    const height=(opensUp?bounds.bottom:innerHeight-bounds.top)-8;
    panel.style.maxHeight=Math.max(0,height)+'px';
  };
  menu.addEventListener('toggle', () => {
    summary.setAttribute('aria-expanded', String(menu.open));
    if (menu.open) { panel.scrollTop = 0;fit(); }
  });
  window.addEventListener('resize', fit);
  new ResizeObserver(fit).observe(menu.closest('header') || menu, {box:'border-box'});
  document.addEventListener('click', event => {
    if (!menu.contains(event.target) || event.target.closest('.account-menu-items a, .account-menu-items button')) menu.open = false;
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu.open) {
      event.preventDefault(); menu.open = false; summary.focus();
    }
  });
}

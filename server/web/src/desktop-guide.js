import {t} from './i18n.js';
import {MarkdownField} from './markdown-field.js';

const guide = new URL('./guide/user/DESKTOP.md', import.meta.url);
const screenshot = '../assets/screenshots/desktop-guide-navigation.png';
const documentation = new URL('https://github.com/turesheim/eclipse-timekeeper/blob/main/docs/user/DESKTOP.md');

export function desktopGuide(trigger, dialog) {
  const closeButton = dialog.querySelector('#desktop-guide-close');
  const status = dialog.querySelector('#desktop-guide-status');
  const content = dialog.querySelector('#desktop-guide-content');
  let field;
  let request;

  trigger.addEventListener('click', async () => {
    if (dialog.open) return;
    dialog.showModal();
    dialog.scrollTop = 0;
    if (field) return;

    status.textContent = t('guide.loading');
    const loading = new AbortController();
    request = loading;
    try {
      const response = await fetch(guide, {signal: loading.signal});
      if (!response.ok) throw new Error('Guide fetch failed: ' + response.status);
      const body = await response.text();
      if (loading.signal.aborted) return;

      field = new MarkdownField(content, () => {}, () => {}, 'desktop-guide-note', null, {
        allowsAsset: reference => reference === screenshot,
        image: (reference, alt) => {
          const dom = document.createElement('img');
          dom.src = new URL(screenshot, guide).href;
          dom.alt = alt;
          return {dom};
        },
        allowsLink: href => /^\.{0,2}\/?[\w/-]+\.md(?:#[\w-]+)?$/.test(href),
        openLink: href => window.open(new URL(href, documentation).href, '_blank', 'noopener,noreferrer')
      });
      field.setValue(body);
      status.textContent = '';
      content.hidden = false;
      await field.showFormatted(false);
    } catch {
      if (!loading.signal.aborted) status.textContent = t('guide.failed');
    }
  });

  closeButton.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    if (dialog.open) return;
    request?.abort();
    if (trigger.getClientRects().length) trigger.focus();
  });
  return () => {
    request?.abort();
    dialog.close();
  };
}

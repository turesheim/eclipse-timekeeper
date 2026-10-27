import {t} from './i18n.js';
import {node,section} from './account-ui.js';

/** Connection instructions for standard WebDAV clients. */
export function renderJournal(view){
  view.root.classList.add('journal-account');
  view.cleanup.add(()=>view.root.classList.remove('journal-account'));
  const card=section(view,t('journal.webdav.title'));
  card.append(node('p',t('journal.webdav.intro')));
  const label=node('p',t('journal.webdav.url_label'),'mb-1');
  const url=node('code',new URL('/dav/journal/',location.origin).href,'journal-webdav-url');
  card.append(label,url);
  const credentials=node('p',t('journal.webdav.credentials'));
  const tokens=node('a',t('journal.webdav.tokens'),'btn btn-outline-secondary');
  tokens.href='#account/tokens';
  card.append(credentials,tokens,node('p',t('journal.webdav.naming'),'mt-3'),node('p',t('journal.webdav.concurrency'),'hint'));
  const guide=node('a',t('journal.webdav.guide'));
  guide.href='https://github.com/turesheim/eclipse-timekeeper/blob/main/docs/operations/JOURNAL-WEBDAV.md';
  guide.target='_blank';guide.rel='noopener noreferrer';
  card.append(guide);
}

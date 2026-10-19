import { t as i18nText } from "./i18n.js";import { iconButton } from './icons.js';

/** One visible, session-local Undo offer. No work snapshots or credentials enter browser storage. */
export class UndoNotice {
  constructor(root, callbacks) {
    this.root = root;this.callbacks = callbacks;this.message = root.querySelector('[role=status]');this.button = root.querySelector('[data-undo]');
    this.dismiss = root.querySelector('[data-dismiss-undo]');iconButton(this.dismiss, 'close', i18nText("ui.dismiss_undo"));
    this.button.addEventListener('pointerdown', (event) => event.preventDefault());
    this.button.addEventListener('click', () => void this.apply());this.dismiss.addEventListener('click', () => this.clear());
    this.root.addEventListener('pointerenter', () => this.paint());
    this.root.addEventListener('focusin', () => this.paint());
    this.timer = setInterval(() => this.paint(), 500);
  }
  offer(info, api, label = i18nText("ui.work_session_updated")) {
    if (!info) return;
    const now = performance.now();
    this.entry = { ...info, api, label, key: crypto.randomUUID(), until: now + info.seconds * 1000, duration: 10000, hideAt: now + 10000 };this.paint();
  }
  clear() {this.entry = null;this.root.hidden = true;this.root.classList.remove('is-fading');this.message.textContent = '';}
  paint() {
    const entry = this.entry;if (!entry) return;
    const now = performance.now();
    if (now >= entry.until && !entry.done && !entry.busy && !entry.uncertain) {this.clear();return;}
    const interacting = matchMedia('(hover: hover)').matches && this.root.matches(':hover') || this.root.contains(document.activeElement);
    if (entry.busy || entry.uncertain || interacting) entry.hideAt = now + entry.duration;
    const fading = now >= entry.hideAt;
    if (!fading) entry.fadeAt = null;else
    if (entry.fadeAt == null) entry.fadeAt = now;
    if (fading && now >= entry.fadeAt + 250) {this.clear();return;}
    this.root.classList.toggle('is-fading', fading);
    this.root.hidden = false;
    this.message.textContent = entry.message || entry.label;
    this.button.textContent = entry.uncertain ? i18nText("ui.retry_undo") : i18nText("ui.undo_a8283a");
    this.button.hidden = !!entry.done;this.button.disabled = !!entry.busy || !this.callbacks.available(entry.api);
    this.dismiss.disabled = !!entry.busy;
  }
  async apply() {
    const entry = this.entry;if (!entry || entry.busy || entry.done || !this.callbacks.available(entry.api)) return;
    entry.busy = true;entry.message = i18nText("ui.undoing_work_session_change");this.paint();
    try {
      await entry.api.request('/undo/' + entry.id, { method: 'POST', body: {}, key: entry.key });
      if (this.entry !== entry) return;
      entry.done = true;entry.uncertain = false;entry.message = i18nText("ui.work_session_change_undone");
      entry.duration = 5000;entry.hideAt = performance.now() + entry.duration;await this.callbacks.changed(entry.api);
    } catch (error) {
      if (this.entry !== entry) return;
      if (error.status === 401) {this.callbacks.unauthorized(error);return;}
      entry.uncertain = error.uncertain;
      entry.message = error.uncertain ? i18nText("ui.undo_could_not_be_confirmed_retry_safely_to_check_the_same_request") : error.status === 404 ? i18nText("ui.undo_is_no_longer_available") : error.message;
      entry.done = !error.uncertain;entry.duration = 10000;entry.hideAt = performance.now() + entry.duration;
    } finally {if (this.entry === entry) {entry.busy = false;this.paint();}}
  }
}

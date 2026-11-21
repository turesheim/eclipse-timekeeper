import {t} from './i18n.js';

const blank = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>';
const pngPrefix = 'data:image/png;base64,';
let configured;

export function drawioOrigin() {
  configured ??= fetch('/web/drawio-origin', {redirect: 'error'}).then(async response => {
    if (!response.ok) return null;
    const value = await response.text();
    if (!value) return null;
    const url = new URL(value);
    // Cookies are shared by different ports on the same hostname.
    return url.protocol === 'https:' && value === url.origin && url.hostname !== location.hostname ? value : null;
  }).catch(() => null);
  return configured;
}

function readPng(blob) {
  const reader = new FileReader();
  return new Promise((resolve, reject) => {
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob.slice(0, blob.size, 'image/png'));
  });
}

export class DrawioEditor {
  constructor(origin, signal) {
    this.origin = origin;
    this.signal = signal;
    this.frame = document.createElement('iframe');
    this.frame.title = t('drawio.editor');
    this.frame.referrerPolicy = 'no-referrer';
    this.frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
    window.addEventListener('message', event => this.receive(event), {signal});
    signal.addEventListener('abort', () => {
      this.pending?.finish(new DOMException('', 'AbortError'));
      this.frame.remove();
    }, {once: true});
  }

  receive(event) {
    if (event.origin !== this.origin || event.source !== this.frame.contentWindow
        || typeof event.data !== 'string' || event.data.length > 25_000_000) return;
    let message;
    try { message = JSON.parse(event.data); }
    catch { return; }
    const pending = this.pending;
    if (!pending || message?.event !== pending.event) return;
    if (pending.request && message.message?.request !== pending.request) return;
    pending.finish(null, message);
  }

  waitFor(event, start, request) {
    this.signal.throwIfAborted();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => finish(new Error(t(event === 'export' ? 'drawio.export_failed' : 'drawio.editor_unavailable'))), 15_000);
      const finish = (error, message) => {
        clearTimeout(timer);
        this.pending = null;
        if (error) reject(error); else resolve(message);
      };
      this.pending = {event, request, finish};
      start();
    });
  }

  send(message) {
    this.frame.contentWindow.postMessage(JSON.stringify(message), this.origin);
  }

  async load(blob) {
    const xml = blob ? await readPng(blob) : blank;
    const url = new URL('/', this.origin);
    url.search = new URLSearchParams({
      embed: '1', proto: 'json', ui: 'min', offline: '1', stealth: '1',
      noSaveBtn: '1', noExitBtn: '1', saveAndExit: '0',
    });
    await this.waitFor('init', () => { this.frame.src = url.href; });
    await this.waitFor('load', () => this.send({action: 'load', xml}));
  }

  async exportPng() {
    const request = crypto.randomUUID();
    const message = await this.waitFor('export',
      () => this.send({action: 'export', format: 'xmlpng', scale: 1, border: 12, request}), request);
    const data = message.data;
    if (message.format !== 'xmlpng' || typeof data !== 'string' || !data.startsWith(pngPrefix)) {
      throw new Error(t('drawio.export_failed'));
    }
    if (data.length > pngPrefix.length + 4 * Math.ceil(5_000_000 / 3)) {
      throw new Error(t('markdown.asset_too_large'));
    }
    try { return Uint8Array.from(atob(data.slice(pngPrefix.length)), char => char.charCodeAt(0)); }
    catch { throw new Error(t('drawio.export_failed')); }
  }
}

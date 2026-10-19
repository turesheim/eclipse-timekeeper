import {setLocale} from './i18n.js';
import { t as i18nText } from "./i18n.js";self.onmessage = async (event) => {
  try {
    setLocale(event.data.locale);
    const file = event.data.file;
    if (!file || file.size < 1 || file.size > 256 * 1024 * 1024) throw new Error();
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
    self.postMessage({ sha256: [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, '0')).join('') });
  } catch {self.postMessage({ error: i18nText("ui.the_archive_could_not_be_read_choose_a_timekeeper_zip_or_archive_json_up_to_256_mib") });}
};

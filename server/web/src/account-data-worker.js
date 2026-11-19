import {fileSha256} from './file-sha256.js';
import {setLocale,t} from './i18n.js';

self.onmessage = async ({data:{file,locale}}) => {
  try {
    setLocale(locale);
    self.postMessage({sha256:await fileSha256(file)});
  } catch {
    self.postMessage({error:t('ui.the_archive_could_not_be_read_choose_a_timekeeper_zip_or_archive_json_up_to_256_mib')});
  }
};

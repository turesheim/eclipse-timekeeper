import {parseTogglDetailedCsv} from './toggl-csv.js';
import {importPlan, importBatches} from './import-model.js';

// One file per short-lived worker. The page terminates it on cancel, navigation or logout.
self.onmessage = async ({data}) => {
  try {
    const {file, options} = data;
    if(data.operation === 'plan') {
      const plan = await importPlan(data.rows, data.personKey, options);
      const batches = importBatches(plan.rows, data.reportZone).map(rows => rows.length);
      self.postMessage({plan, batches}); return;
    }
    if (!(file instanceof Blob) || file.size > 20 * 1024 * 1024) throw new Error('Choose a UTF-8 CSV file no larger than 20 MiB.');
    const bytes = await file.arrayBuffer();
    const fileFingerprint = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(value => value.toString(16).padStart(2, '0')).join('');
    const parsed = parseTogglDetailedCsv(new TextDecoder('utf-8', {fatal: true}).decode(bytes), options);
    self.postMessage({parsed, fileFingerprint});
  } catch (error) {
    self.postMessage({error: error instanceof TypeError ? 'The file must use UTF-8 encoding.' : error.message});
  } finally {self.close();}
};

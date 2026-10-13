const key = 'timekeeper.access-token';
const browserStorage = () => globalThis.localStorage;

export function savedToken(storage = browserStorage) {
  try { return storage().getItem(key) || ''; } catch { return ''; }
}
export function rememberToken(token, storage = browserStorage) {
  try { storage().setItem(key, token); return true; } catch { return false; }
}
export function forgetToken(expected = null, storage = browserStorage) {
  try {
    const target = storage();
    if (expected === null || target.getItem(key) === expected) target.removeItem(key);
    return true;
  } catch { return false; }
}

const key = 'timekeeper.access-token';
const browserStorage = () => globalThis.localStorage;

export function savedToken(storage = browserStorage) {
  try { return storage().getItem(key) || ''; } catch { return ''; }
}
export function forgetToken(expected = null, storage = browserStorage) {
  try {
    const target = storage();
    if (expected === null || target.getItem(key) === expected) target.removeItem(key);
    return true;
  } catch { return false; }
}
export function isSecureOrigin(location = globalThis.location) {
  return location.protocol === 'https:' || (location.protocol === 'http:' && ['localhost','127.0.0.1','[::1]'].includes(location.hostname));
}

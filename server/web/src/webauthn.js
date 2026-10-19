import { t as i18nText } from "./i18n.js"; // WebAuthn verification belongs to the server library. These helpers only adapt
// JSON/binary browser values and manage the lifetime of one native prompt.
export function passkeysSupported(environment = globalThis) {
  return environment.isSecureContext === true && typeof environment.PublicKeyCredential === 'function' &&
  typeof environment.navigator?.credentials?.create === 'function' && typeof environment.navigator?.credentials?.get === 'function';
}
export const canceledPasskey = (error) => ['AbortError', 'NotAllowedError'].includes(error?.name);
export function decodeBinary(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error(i18nText("ui.invalid_passkey_options"));
  return Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), (c) => c.charCodeAt(0));
}
export function encodeBinary(value) {
  const bytes = new Uint8Array(value);let text = '';
  for (let offset = 0; offset < bytes.length; offset += 8192) text += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  return btoa(text).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
export function browserOptions(value, create, environment = globalThis) {
  const method = create ? 'parseCreationOptionsFromJSON' : 'parseRequestOptionsFromJSON';
  if (typeof environment.PublicKeyCredential?.[method] === 'function') return environment.PublicKeyCredential[method](value);
  const result = { ...value, challenge: decodeBinary(value.challenge) };
  if (create) result.user = { ...value.user, id: decodeBinary(value.user.id) };
  const list = create ? 'excludeCredentials' : 'allowCredentials';
  if (value[list]) result[list] = value[list].map((item) => ({ ...item, id: decodeBinary(item.id) }));
  return result;
}
export function credentialJSON(credential) {
  if (typeof credential?.toJSON === 'function') return credential.toJSON();
  const response = credential.response,result = {
      id: credential.id, rawId: encodeBinary(credential.rawId), type: credential.type,
      response: { clientDataJSON: encodeBinary(response.clientDataJSON) }, clientExtensionResults: credential.getClientExtensionResults()
    };
  if (response.attestationObject) {
    result.response.attestationObject = encodeBinary(response.attestationObject);
    if (typeof response.getTransports === 'function') result.response.transports = response.getTransports();
  } else {
    result.response.authenticatorData = encodeBinary(response.authenticatorData);
    result.response.signature = encodeBinary(response.signature);
    result.response.userHandle = response.userHandle === null ? null : encodeBinary(response.userHandle);
  }
  if (credential.authenticatorAttachment !== undefined) result.authenticatorAttachment = credential.authenticatorAttachment;
  return result;
}
export async function cancelPasskey(ceremony) {
  // This public, browser-bound route intentionally has no account-owner response
  // header. Keep it outside Api's authenticated response processing.
  try {
    await fetch('/api/v1/auth/passkeys/cancel', { method: 'POST', credentials: 'same-origin', redirect: 'error', referrerPolicy: 'origin', cache: 'no-store', keepalive: true,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ ceremony }) });
  } catch {/* Unknown requests expire under the server's short-lived limits. */}
}
const aborted = () => new DOMException(i18nText("ui.passkey_request_canceled"), 'AbortError');
export class PasskeyCeremony {
  constructor(request, { environment = globalThis, cancel = cancelPasskey } = {}) {this.request = request;this.environment = environment;this.cancelRequest = cancel;this.flow = null;}
  cancel() {
    const flow = this.flow;if (!flow) return;
    this.flow = null;flow.controller.abort();
    if (flow.ceremony) {const ceremony = flow.ceremony;flow.ceremony = null;void this.cancelRequest(ceremony);}
  }
  async run(optionsPath, finishPath, body, { create = false, valid = () => true, stage = () => {} } = {}) {
    this.cancel();
    if (!passkeysSupported(this.environment)) throw new Error(i18nText("ui.passkeys_are_unavailable_in_this_browser_use_your_username_and_password"));
    const flow = { controller: new AbortController(), ceremony: null };this.flow = flow;
    const current = () => this.flow === flow && !flow.controller.signal.aborted && valid();
    try {
      stage('options');const options = await this.request(optionsPath, body);flow.ceremony = options.ceremony;
      if (!current()) throw aborted();
      stage('prompt');
      let credential;
      try {credential = await this.environment.navigator.credentials[create ? 'create' : 'get']({ publicKey: browserOptions(options.publicKey, create, this.environment), signal: flow.controller.signal });}
      catch (error) {
        if (canceledPasskey(error)) throw error;
        throw new Error(i18nText("ui.your_browser_could_not_complete_the_passkey_request_use_your_password_or_try_another_"));
      }
      if (!current() || !credential) throw aborted();
      stage('finish');
      const result = await this.request(finishPath, { ceremony: options.ceremony, credential: credentialJSON(credential) });
      if (!current()) throw aborted();return result;
    } finally {
      if (this.flow === flow) this.flow = null;
      if (flow.ceremony) {const ceremony = flow.ceremony;flow.ceremony = null;void this.cancelRequest(ceremony);}
    }
  }
}

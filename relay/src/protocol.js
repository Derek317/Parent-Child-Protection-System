const encoder = new TextEncoder();
const scopePattern = /^ig_hk_[a-f0-9]{28}$/;
const hexPattern = /^[a-f0-9]{64}$/;
const noncePattern = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;

export function base64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}

export function decode64(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value) || value.length > 4096) throw new Error('Invalid encoding');
  const bytes = Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
  if (base64url(bytes) !== value) throw new Error('Noncanonical encoding');
  return bytes;
}

export async function digest(value) {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)))].map(b => b.toString(16).padStart(2, '0')).join('');
}

export async function roomId(publicKey, scope) {
  if (!scopePattern.test(scope)) throw new Error('Invalid scope');
  const key = base64url(decode64(publicKey));
  return digest(`igguard-relay-room-v1\n${key}\n${scope}`);
}

export async function childCapability(token) {
  const key = await crypto.subtle.importKey('raw', encoder.encode(token), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return base64url(await crypto.subtle.sign('HMAC', key, encoder.encode('igguard-relay-child-v1')));
}

export const capabilityHash = capability => digest(capability);

export function parseAuthorization(header) {
  if (typeof header !== 'string' || !header.startsWith('IGGuard ') || header.length > 2048) throw new Error('Invalid authorization');
  const auth = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(decode64(header.slice(8))));
  const fields = auth?.role === 'parent'
    ? ['v','role','room','scope','public_key','capability_hash','ts_ms','nonce','signature']
    : ['v','role','room','scope','capability'];
  if (!auth || Array.isArray(auth) || Object.keys(auth).length !== fields.length || fields.some(f => !Object.hasOwn(auth, f)) ||
      auth.v !== 1 || !['parent','child'].includes(auth.role) || !hexPattern.test(auth.room) || !scopePattern.test(auth.scope)) throw new Error('Invalid authorization');
  return auth;
}

function rawSignature(encoded) {
  const der = decode64(encoded);
  if (der.length < 8 || der.length > 72 || der[0] !== 0x30 || der[1] !== der.length - 2) throw new Error('Invalid signature');
  let offset = 2;
  const raw = new Uint8Array(64);
  for (let part = 0; part < 2; part++) {
    if (der[offset++] !== 2) throw new Error('Invalid signature');
    const length = der[offset++];
    let value = der.subarray(offset, offset + length);
    if (!length || length > 33 || value.length !== length || value[0] >= 128 || (length > 1 && value[0] === 0 && value[1] < 128)) throw new Error('Invalid signature');
    offset += length;
    if (value[0] === 0 && value.length > 1) value = value.subarray(1);
    if (value.length > 32) throw new Error('Invalid signature');
    raw.set(value, part * 32 + 32 - value.length);
  }
  if (offset !== der.length) throw new Error('Invalid signature');
  return raw;
}

export async function verifyParentAuthorization(auth, room, now = Date.now()) {
  if (auth.v !== 1 || auth.role !== 'parent' || auth.room !== room || !hexPattern.test(room) ||
      !hexPattern.test(auth.capability_hash) || !noncePattern.test(auth.nonce) ||
      !Number.isSafeInteger(auth.ts_ms) || Math.abs(now - auth.ts_ms) > 120_000 ||
      await roomId(auth.public_key, auth.scope) !== room) throw new Error('Unauthorized');
  const key = await crypto.subtle.importKey('spki', decode64(auth.public_key), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  const proof = ['igguard-relay-auth-v1','parent',room,auth.scope,auth.ts_ms,auth.nonce,auth.capability_hash].join('\n');
  if (!await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, rawSignature(auth.signature), encoder.encode(proof))) throw new Error('Unauthorized');
  return auth;
}

export function sameHash(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !hexPattern.test(a) || !hexPattern.test(b)) return false;
  let difference = 0;
  for (let i = 0; i < 64; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}

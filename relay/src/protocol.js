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

// Exact field set per (role, version); anything else is refused.
const shapes = {
  parent: { 1: ['v','role','room','scope','public_key','capability_hash','ts_ms','nonce','signature'],
    2: ['v','role','room','scope','public_key','capability_hash','child_key','ts_ms','nonce','signature'] },
  child: { 1: ['v','role','room','scope','capability'],
    2: ['v','role','room','scope','capability','child_key','ts_ms','nonce','signature'] },
};

export function parseAuthorization(header) {
  if (typeof header !== 'string' || !header.startsWith('IGGuard ') || header.length > 2048) throw new Error('Invalid authorization');
  const auth = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(decode64(header.slice(8))));
  const fields = (auth?.role === 'parent' ? shapes.parent : shapes.child)[auth?.v === 2 ? 2 : 1];
  if (!auth || Array.isArray(auth) || Object.keys(auth).length !== fields.length || fields.some(f => !Object.hasOwn(auth, f)) ||
      ![1,2].includes(auth.v) || !['parent','child'].includes(auth.role) || !hexPattern.test(auth.room) || !scopePattern.test(auth.scope)) throw new Error('Invalid authorization');
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

// Same encoding as the parent public_key: canonical base64url of a P-256 SPKI.
const importKey = encoded => crypto.subtle.importKey('spki', decode64(encoded), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
const verifySignature = async (key, signature, proof) => {
  if (!await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, rawSignature(signature), encoder.encode(proof))) throw new Error('Unauthorized');
};
const requireFresh = (auth, now) => { if (Math.abs(now - auth.ts_ms) > 120_000) throw new Error('stale_time'); };

export async function verifyParentAuthorization(auth, room, now = Date.now()) {
  if (![1,2].includes(auth.v) || auth.role !== 'parent' || auth.room !== room || !hexPattern.test(room) ||
      !hexPattern.test(auth.capability_hash) || !noncePattern.test(auth.nonce) ||
      !Number.isSafeInteger(auth.ts_ms) || await roomId(auth.public_key, auth.scope) !== room) throw new Error('Unauthorized');
  const key = await importKey(auth.public_key);
  const proof = auth.v === 1
    ? ['igguard-relay-auth-v1','parent',room,auth.scope,auth.ts_ms,auth.nonce,auth.capability_hash].join('\n')
    : ['igguard-relay-auth-v2','parent',room,auth.scope,auth.ts_ms,auth.nonce,auth.capability_hash,auth.child_key].join('\n');
  if (auth.v === 2) await importKey(auth.child_key);
  await verifySignature(key, auth.signature, proof);
  requireFresh(auth, now);
  return auth;
}

/** Proves possession of the child key only; the room checks the key and the capability against its owner record. */
export async function verifyChildAuthorization(auth, room, now = Date.now()) {
  if (auth.v !== 2 || auth.role !== 'child' || auth.room !== room || !hexPattern.test(room) || !scopePattern.test(auth.scope) ||
      typeof auth.capability !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(auth.capability) || !noncePattern.test(auth.nonce) ||
      !Number.isSafeInteger(auth.ts_ms)) throw new Error('Unauthorized');
  const key = await importKey(auth.child_key);
  const proof = ['igguard-relay-auth-v2','child',room,auth.scope,auth.ts_ms,auth.nonce,await capabilityHash(auth.capability)].join('\n');
  await verifySignature(key, auth.signature, proof);
  requireFresh(auth, now);
  return auth;
}

export function sameHash(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !hexPattern.test(a) || !hexPattern.test(b)) return false;
  let difference = 0;
  for (let i = 0; i < 64; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}

import { readFile } from 'node:fs/promises';
import { createHash, createHmac, createPrivateKey, generateKeyPairSync, sign, randomUUID } from 'node:crypto';

export const vector = JSON.parse(await readFile(new URL('./protocol-vector.json', import.meta.url)));
export const sha = value => createHash('sha256').update(value).digest('hex');
export const wire = value => JSON.stringify({ e2ee: Buffer.from('0'.repeat(28) + value).toString('base64') });

export function family(token = vector.token, identity = vector) {
  const scope = 'ig_hk_' + sha('ig_guard_hk_v4:' + token).slice(0, 28);
  const room = sha('igguard-relay-room-v1\n' + identity.publicKey + '\n' + scope);
  const capability = createHmac('sha256', token).update('igguard-relay-child-v1').digest('base64url');
  return { token, scope, room, capability, capability_hash: sha(capability), publicKey: identity.publicKey, privateKey: identity.privateKey };
}

export function header(f, role = 'parent', nonce = randomUUID()) {
  let auth;
  if (role === 'child') auth = { v: 1, role, room: f.room, scope: f.scope, capability: f.capability };
  else {
    auth = { v: 1, role, room: f.room, scope: f.scope, public_key: f.publicKey,
      capability_hash: f.capability_hash, ts_ms: Date.now(), nonce };
    const text = ['igguard-relay-auth-v1','parent',f.room,f.scope,auth.ts_ms,nonce,f.capability_hash].join('\n');
    const key = createPrivateKey({ key: Buffer.from(f.privateKey, 'base64url'), type: 'pkcs8', format: 'der' });
    auth.signature = sign('sha256', Buffer.from(text), key).toString('base64url');
  }
  return 'IGGuard ' + Buffer.from(JSON.stringify(auth)).toString('base64url');
}

export function childIdentity() {
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return { publicKey: keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64url'),
    privateKey: keys.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64url') };
}

// v2 proof: the parent signs with its key and names `child.publicKey`; the child signs with `signer` (default: its own key).
export function headerV2(f, role, child, { nonce = randomUUID(), ts = Date.now(), signer = child, capability = f.capability } = {}) {
  const auth = role === 'parent'
    ? { v: 2, role, room: f.room, scope: f.scope, public_key: f.publicKey, capability_hash: f.capability_hash, child_key: child.publicKey, ts_ms: ts, nonce }
    : { v: 2, role, room: f.room, scope: f.scope, capability, child_key: child.publicKey, ts_ms: ts, nonce };
  const text = role === 'parent'
    ? ['igguard-relay-auth-v2','parent',f.room,f.scope,ts,nonce,f.capability_hash,child.publicKey].join('\n')
    : ['igguard-relay-auth-v2','child',f.room,f.scope,ts,nonce,sha(capability)].join('\n');
  const key = createPrivateKey({ key: Buffer.from(role === 'parent' ? f.privateKey : signer.privateKey, 'base64url'), type: 'pkcs8', format: 'der' });
  auth.signature = sign('sha256', Buffer.from(text), key).toString('base64url');
  return 'IGGuard ' + Buffer.from(JSON.stringify(auth)).toString('base64url');
}

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { capabilityHash, childCapability, roomId, verifyParentAuthorization, verifyChildAuthorization, parseAuthorization } from '../src/protocol.js';

const vector = JSON.parse(await readFile(new URL('./protocol-vector.json', import.meta.url)));
const encode = auth => 'IGGuard ' + Buffer.from(JSON.stringify(auth)).toString('base64url');

test('Android-compatible room and child capability match independent crypto vectors', async () => {
  assert.equal(await roomId(vector.publicKey, vector.scope), vector.room);
  assert.equal(await childCapability(vector.token), vector.capability);
  assert.equal(await capabilityHash(vector.capability), vector.capability_hash);
  assert.notEqual(await roomId(vector.publicKey, 'ig_hk_aaaaaaaaaaaaaaaaaaaaaaaaaaaa'), vector.room);
  assert.notEqual(vector.capability, vector.token);
});

test('parent authorization verifies a Java-compatible DER signature and rejects altered routing', async () => {
  assert.equal((await verifyParentAuthorization(vector.auth, vector.room, vector.auth.ts_ms)).capability_hash, vector.capability_hash);
  for (const change of [
    { role: 'child' }, { room: 'a'.repeat(64) }, { scope: 'ig_hk_aaaaaaaaaaaaaaaaaaaaaaaaaaaa' },
    { capability_hash: 'a'.repeat(64) }, { nonce: 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa' },
    { ts_ms: vector.auth.ts_ms + 1 }, { signature: 'bad' }, { v: 2 },
  ]) {
    await assert.rejects(verifyParentAuthorization({ ...vector.auth, ...change }, vector.room, vector.auth.ts_ms));
  }
  await assert.rejects(verifyParentAuthorization(vector.auth, 'b'.repeat(64), vector.auth.ts_ms));
  await assert.rejects(verifyParentAuthorization(vector.auth, vector.room, vector.auth.ts_ms + 120_001));
  await assert.rejects(verifyParentAuthorization(vector.auth, vector.room, vector.auth.ts_ms - 120_001));
});

test('authorization rejects malformed, oversized and unexpected header fields', () => {
  const header = 'IGGuard ' + Buffer.from(JSON.stringify(vector.auth)).toString('base64url');
  assert.deepEqual(parseAuthorization(header), vector.auth);
  for (const bad of ['', 'Bearer password', 'IGGuard !!!', 'IGGuard ' + 'a'.repeat(4096),
    'IGGuard ' + Buffer.from('{"role":"parent","unexpected":true}').toString('base64url')]) {
    assert.throws(() => parseAuthorization(bad));
  }
});

test('v2 parent and child authorizations verify the shared vectors and their exact transcripts', async () => {
  const { auth_v2_parent: parent, auth_v2_child: child } = vector;
  assert.equal(await capabilityHash(child.capability), vector.capability_hash);
  assert.equal(vector.transcript_v2_parent, ['igguard-relay-auth-v2','parent',vector.room,vector.scope,parent.ts_ms,parent.nonce,vector.capability_hash,vector.childPublicKey].join('\n'));
  assert.equal(vector.transcript_v2_child, ['igguard-relay-auth-v2','child',vector.room,vector.scope,child.ts_ms,child.nonce,vector.capability_hash].join('\n'));
  assert.deepEqual(parseAuthorization(encode(parent)), parent);
  assert.deepEqual(parseAuthorization(encode(child)), child);
  assert.ok(encode(parent).length < 2048 && encode(child).length < 2048);
  assert.equal((await verifyParentAuthorization(parent, vector.room, parent.ts_ms)).child_key, vector.childPublicKey);
  assert.equal((await verifyChildAuthorization(child, vector.room, child.ts_ms)).child_key, vector.childPublicKey);
  for (const now of [parent.ts_ms + 120_000, parent.ts_ms - 120_000]) await verifyParentAuthorization(parent, vector.room, now);
  for (const now of [child.ts_ms + 120_000, child.ts_ms - 120_000]) await verifyChildAuthorization(child, vector.room, now);
});

test('v2 authorizations reject another key, a bad signature, stale or future time and altered fields', async () => {
  const { auth_v2_parent: parent, auth_v2_child: child } = vector;
  const otherKey = vector.publicKey; // a valid P-256 SPKI that did not sign the child transcript
  for (const change of [
    { child_key: otherKey }, { signature: parent.signature.slice(0, -2) + 'AA' }, { signature: 'bad' },
    { child_key: 'AAAA' }, { child_key: 1 }, { capability_hash: 'a'.repeat(64) }, { nonce: 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa' },
    { ts_ms: parent.ts_ms + 1 }, { v: 1 }, { role: 'child' },
  ]) await assert.rejects(verifyParentAuthorization({ ...parent, ...change }, vector.room, parent.ts_ms), JSON.stringify(change));
  await assert.rejects(verifyParentAuthorization(parent, vector.room, parent.ts_ms + 120_001));
  await assert.rejects(verifyParentAuthorization(parent, vector.room, parent.ts_ms - 120_001));
  for (const change of [
    { child_key: otherKey }, { signature: vector.auth.signature }, { signature: 'bad' }, { child_key: 'AAAA' },
    { capability: 'A'.repeat(43) }, { capability: 'short' }, { nonce: 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa' },
    { ts_ms: child.ts_ms + 1 }, { ts_ms: String(child.ts_ms) }, { scope: 'ig_hk_aaaaaaaaaaaaaaaaaaaaaaaaaaaa' }, { room: 'a'.repeat(64) }, { v: 1 }, { role: 'parent' },
  ]) await assert.rejects(verifyChildAuthorization({ ...child, ...change }, vector.room, child.ts_ms), JSON.stringify(change));
  await assert.rejects(verifyChildAuthorization(child, 'b'.repeat(64), child.ts_ms));
  await assert.rejects(verifyChildAuthorization(child, vector.room, child.ts_ms + 120_001));
  await assert.rejects(verifyChildAuthorization(child, vector.room, child.ts_ms - 120_001));
});

test('v2 headers need exactly their own field set', () => {
  for (const auth of [vector.auth_v2_parent, vector.auth_v2_child]) {
    for (const field of Object.keys(auth)) {
      const missing = { ...auth }; delete missing[field];
      assert.throws(() => parseAuthorization(encode(missing)), field);
    }
    assert.throws(() => parseAuthorization(encode({ ...auth, extra: 1 })));
    assert.throws(() => parseAuthorization(encode({ ...auth, v: 3 })));
    assert.throws(() => parseAuthorization(encode({ ...auth, v: '2' })));
  }
  // A v1 shape labelled v2 (and the reverse) is refused.
  assert.throws(() => parseAuthorization(encode({ ...vector.auth, v: 2 })));
  assert.throws(() => parseAuthorization(encode({ ...vector.auth_v2_child, v: 1 })));
});

test('a genuine proof outside the 2-minute window is refused as stale_time; a forged one never learns about the time', async () => {
  const { auth_v2_parent: parent, auth_v2_child: child } = vector;
  for (const offset of [120_001, -120_001]) {
    await assert.rejects(verifyParentAuthorization(vector.auth, vector.room, vector.auth.ts_ms + offset), { message: 'stale_time' });
    await assert.rejects(verifyParentAuthorization(parent, vector.room, parent.ts_ms + offset), { message: 'stale_time' });
    await assert.rejects(verifyChildAuthorization(child, vector.room, child.ts_ms + offset), { message: 'stale_time' });
    await assert.rejects(verifyParentAuthorization({ ...parent, signature: vector.auth.signature }, vector.room, parent.ts_ms + offset), { message: 'Unauthorized' });
    await assert.rejects(verifyChildAuthorization({ ...child, signature: vector.auth.signature }, vector.room, child.ts_ms + offset), { message: 'Unauthorized' });
    await assert.rejects(verifyParentAuthorization(vector.auth, 'b'.repeat(64), vector.auth.ts_ms + offset), { message: 'Unauthorized' });
  }
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { capabilityHash, childCapability, roomId, verifyParentAuthorization, parseAuthorization } from '../src/protocol.js';

const vector = JSON.parse(await readFile(new URL('./protocol-vector.json', import.meta.url)));

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

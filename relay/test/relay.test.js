import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { randomUUID } from 'node:crypto';
import { family, header, headerV2, childIdentity, runtime, connection, publish, consume, wire, sql } from './helpers.js';

test('only the signing parent bootstraps a room; child capability cannot cross families', async () => {
  const mf = runtime();
  try {
    const f = family(), other = family('22345678-1234-4234-9234-123456789abc');
    assert.equal((await connection(mf, f, 'child')).response.status, 409);
    const parent = await connection(mf, f); assert.equal(parent.response.status, 101);
    await parent.next(m => m.type === 'ready');
    assert.equal((await connection(mf, f, 'child', header({ ...f, capability: 'A'.repeat(43) }, 'child'))).response.status, 401);
    assert.equal((await connection(mf, other, 'child', header({ ...other, capability: f.capability }, 'child'))).response.status, 409);
    const child = await connection(mf, f, 'child'); assert.equal(child.response.status, 101);
    assert.equal((await publish(child, 'from-child')).mode, 'forwarded');
    const packet = await parent.next(m => m.type === 'message'); assert.equal(packet.wire, wire('from-child'));
    child.send({ type: 'consume', id: packet.id, digest: packet.digest });
    assert.equal((await child.next(m => m.type === 'error')).code, 'invalid_consumption');
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('identical parent proof is rejected concurrently and after runtime restart', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'ig-relay-'));
  let mf = runtime(directory);
  try {
    const f = family(), auth = header(f);
    const results = await Promise.all([connection(mf, f, 'parent', auth), connection(mf, f, 'parent', auth)]);
    assert.deepEqual(results.map(r => r.response.status).sort(), [101, 401]);
    for (const r of results) r.close?.();
    await mf.dispose(); mf = runtime(directory);
    assert.equal((await connection(mf, f, 'parent', auth)).response.status, 401);
    const fresh = await connection(mf, f); assert.equal(fresh.response.status, 101); fresh.close();
  } finally { await mf.dispose(); await rm(directory, { recursive: true, force: true }); }
});

test('offline ciphertext is durable, deduplicated and replayed with fresh delivery IDs until consumed', async () => {
  const mf = runtime();
  try {
    const f = family(), initial = await connection(mf, f); initial.close();
    const child = await connection(mf, f, 'child'); await child.next(m => m.type === 'ready');
    assert.equal((await publish(child, 'event:0')).mode, 'buffered');
    assert.equal((await publish(child, 'event:0')).mode, 'buffered');
    assert.equal((await publish(child, 'event:0', wire('conflict'))).code, 'id_conflict');
    let parent = await connection(mf, f);
    const first = await parent.next(m => m.type === 'message'); parent.close();
    parent = await connection(mf, f);
    const replay = await parent.next(m => m.type === 'message');
    assert.equal(replay.id, first.id); assert.notEqual(replay.delivery_id, first.delivery_id);
    consume(parent, replay);
    await parent.next(m => m.type === 'consumed');
    parent.close(); parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    parent.send({ type: 'replay' }); await parent.next(m => m.type === 'replay_complete');
    assert.equal(parent.messages.filter(m => m.type === 'message').length, 0);
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('critical controls are buffered before acceptance even when the receiver is online', async () => {
  const mf = runtime();
  try {
    const f = family(), parent = await connection(mf, f), child = await connection(mf, f, 'child');
    assert.equal((await publish(parent, 'rotation-command', wire('rotation'), 'control')).mode, 'buffered');
    const control = await child.next(m => m.type === 'message');
    const rows = await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM child_inbox');
    assert.equal(rows[0].count, 1);
    consume(child, control); await child.next(m => m.type === 'consumed');
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM child_inbox'))[0].count, 0);
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('malformed/plaintext/oversized frames are rejected without persisting or exposing content', async () => {
  const mf = runtime();
  try {
    const f = family(), parent = await connection(mf, f), child = await connection(mf, f, 'child');
    for (const value of [
      { type: 'publish', id: 'a', kind: 'data', wire: 'private plaintext' },
      { type: 'publish', id: 'a', kind: 'data', wire: wire('x'.repeat(4000)) },
      { type: 'publish', id: 'a', kind: 'data', wire: wire('okay'), destination: 'another-room' },
      { type: 'publish', id: '', kind: 'data', wire: wire('okay') },
    ]) {
      child.send(value); assert.equal((await child.next(m => m.type === 'error')).code, 'invalid_packet');
    }
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM parent_inbox'))[0].count, 0);
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('a full ordinary mailbox reserves urgent space and resumes after durable fragment consumption', async () => {
  const mf = runtime();
  try {
    const f = family(), initial = await connection(mf, f); initial.close();
    const child = await connection(mf, f, 'child');
    const sendFrame = async (id, kind = 'data') => {
      for (;;) {
        const result = await publish(child, id, wire(id), kind);
        if (result.code !== 'rate_limited') return result;
        await delay(result.retry_after_ms);
      }
    };
    for (let n = 0; n < 480; n++) assert.equal((await sendFrame(`long-event:${n}`)).type, 'accepted');
    assert.equal((await sendFrame('long-event:480')).code, 'queue_full');
    assert.equal((await sendFrame('urgent-alert:0', 'urgent')).type, 'accepted');
    const parent = await connection(mf, f);
    const fragments = new Set();
    for (let n = 0; n < 481; n++) {
      const packet = await parent.next(m => m.type === 'message'); fragments.add(packet.id); consume(parent, packet);
    }
    assert.equal(fragments.size, 481);
    // The whole event exceeds mailbox capacity, but committed partial fragments free space.
    for (let n = 480; n < 600; n++) {
      assert.equal((await sendFrame(`long-event:${n}`)).type, 'accepted');
      consume(parent, await parent.next(m => m.type === 'message'));
    }
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM parent_inbox'))[0].count, 0);
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('expired cloud copies are removed on reconnect and duplicates do not extend their expiry', async () => {
  const mf = runtime();
  try {
    const f = family(), initial = await connection(mf, f); initial.close();
    const child = await connection(mf, f, 'child');
    await publish(child, 'pending:0');
    const before = await sql(mf, f.room, 'SELECT expires FROM parent_inbox');
    await publish(child, 'pending:0');
    assert.equal((await sql(mf, f.room, 'SELECT expires FROM parent_inbox'))[0].expires, before[0].expires);
    await sql(mf, f.room, 'UPDATE parent_inbox SET expires=?', Date.now() - 1000);
    await sql(mf, f.room, 'UPDATE packet_lifetimes SET expires=?', Date.now() - 1000);
    const parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM parent_inbox'))[0].count, 0);
    assert.equal((await publish(child, 'pending:0')).code, 'expired');
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM parent_inbox'))[0].count, 0);
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('backend storage refusal is retryable and is not reported as incorrect pairing', async () => {
  const mf=runtime();
  try{
    const f=family(),parent=await connection(mf,f);parent.close();
    await sql(mf,f.room,"CREATE TRIGGER refuse_write BEFORE INSERT ON metadata BEGIN SELECT RAISE(FAIL,'test storage unavailable'); END");
    const response=(await connection(mf,f)).response;
    assert.equal(response.status,503);
    assert.equal((await response.json()).error.code,'relay_unavailable');
  }finally{await mf.dispose()}
});

const owner = async (mf, f) => JSON.parse((await sql(mf, f.room, "SELECT value FROM metadata WHERE key='owner'"))[0].value);
// Miniflare reports a server-side close to the test client only on dispose, so ask the relay instead:
// with no open child socket left, a parent record is buffered rather than forwarded.
const noOpenChild = async parent => assert.equal((await publish(parent, `probe:${randomUUID()}`)).mode, 'buffered');
// Status and Retry-After only: on this PC, reading a refused body after the relay has closed another socket in the
// same runtime makes Miniflare's dispose throw ECONNRESET (as in the storage-refusal test above).
const refused = async (mf, f, authorization, status = 401, retryAfter = '60') => {
  const { response } = await connection(mf, f, 'child', authorization);
  assert.equal(response.status, status);
  assert.equal(response.headers.get('Retry-After'), retryAfter);
};

test('a parent v2 proof locks the room; unproven child logins get 401 and never evict the proven child', async () => {
  const mf = runtime();
  try {
    const f = family(), key = childIdentity(), other = childIdentity();
    const parent = await connection(mf, f, 'parent', headerV2(f, 'parent', key)); assert.equal(parent.response.status, 101);
    const ready = await parent.next(m => m.type === 'ready');
    assert.equal(ready.protocol, 1); assert.deepEqual(ready.features, ['child_proof']);
    assert.equal((await owner(mf, f)).child_key, key.publicKey);
    const proven = headerV2(f, 'child', key);
    const child = await connection(mf, f, 'child', proven); assert.equal(child.response.status, 101);
    assert.deepEqual((await child.next(m => m.type === 'ready')).features, ['child_proof']);
    await refused(mf, f, header(f, 'child'));                                        // v1 capability alone
    await refused(mf, f, headerV2(f, 'child', other));                               // another key
    await refused(mf, f, headerV2(f, 'child', key, { signer: other }));              // registered key, wrong signature
    await refused(mf, f, headerV2(f, 'child', key, { ts: Date.now() - 121_000 }));   // stale
    await refused(mf, f, headerV2(f, 'child', key, { ts: Date.now() + 121_000 }));   // future
    await refused(mf, f, proven);                                                    // replayed nonce
    await refused(mf, f, headerV2(f, 'child', key, { capability: 'A'.repeat(43) })); // right key, wrong family secret
    assert.equal((await publish(parent, 'after-attempts')).mode, 'forwarded');
    assert.equal((await child.next(m => m.type === 'message')).wire, wire('after-attempts'));
    parent.send({ type: 'retire' }); await parent.next(m => m.type === 'retired');
    await refused(mf, f, headerV2(f, 'child', key), 410, String(90 * 24 * 60 * 60));
  } finally { await mf.dispose(); }
});

test('a v1 parent reconnect keeps the child key; a v2 parent replacing it closes the old key socket', async () => {
  const mf = runtime();
  try {
    const f = family(), first = childIdentity(), second = childIdentity();
    (await connection(mf, f, 'parent', headerV2(f, 'parent', first))).close();
    const child = await connection(mf, f, 'child', headerV2(f, 'child', first)); await child.next(m => m.type === 'ready');
    const locked = await owner(mf, f);
    assert.equal(locked.child_nonces.length, 1);
    const parent = await connection(mf, f); assert.equal(parent.response.status, 101); await parent.next(m => m.type === 'ready');
    const kept = await owner(mf, f);
    assert.equal(kept.child_key, first.publicKey); assert.deepEqual(kept.child_nonces, locked.child_nonces); assert.equal(kept.epoch, locked.epoch);
    await refused(mf, f, header(f, 'child'));
    assert.equal((await publish(parent, 'kept')).mode, 'forwarded');
    assert.equal((await child.next(m => m.type === 'message')).wire, wire('kept'));
    parent.close();
    const replacing = await connection(mf, f, 'parent', headerV2(f, 'parent', second)); assert.equal(replacing.response.status, 101);
    assert.equal((await replacing.next(m => m.type === 'ready')).peer_online, false);
    await noOpenChild(replacing);
    assert.equal((await owner(mf, f)).child_key, second.publicKey);
    await refused(mf, f, headerV2(f, 'child', first));
    const next = await connection(mf, f, 'child', headerV2(f, 'child', second)); assert.equal(next.response.status, 101);
    next.close(); replacing.close();
  } finally { await mf.dispose(); }
});

test('an unlocked room keeps last-login-wins for v1 and tells a v2 child to wait without evicting anyone', async () => {
  const mf = runtime();
  try {
    const f = family(), key = childIdentity();
    const parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    const before = await owner(mf, f);
    assert.equal(before.child_key, undefined); assert.equal(before.child_nonces, undefined);
    const first = await connection(mf, f, 'child'); await first.next(m => m.type === 'ready');
    await refused(mf, f, headerV2(f, 'child', key), 409, '15');
    assert.equal((await publish(parent, 'not-evicted')).mode, 'forwarded');
    assert.equal((await first.next(m => m.type === 'message')).wire, wire('not-evicted'));
    const second = await connection(mf, f, 'child'); await second.next(m => m.type === 'ready');
    assert.equal((await publish(parent, 'latest-login')).mode, 'forwarded');
    assert.equal((await second.next(m => m.type === 'message')).wire, wire('latest-login'));
    assert.equal(first.messages.some(m => m.type === 'message'), false);
    await refused(mf, f, headerV2(f, 'child', key), 409, '15');
    await refused(mf, f, headerV2(f, 'child', key, { signer: childIdentity() }));
    assert.equal((await publish(parent, 'still-here')).mode, 'forwarded');
    assert.equal((await second.next(m => m.type === 'message')).wire, wire('still-here'));
    assert.deepEqual(await owner(mf, f), { ...before, last_active: (await owner(mf, f)).last_active });
    parent.close(); second.close();
  } finally { await mf.dispose(); }
});

// This does not prove the re-check after the capability hash in FamilyRoom.fetch: with that re-check removed the
// test still passed (Miniflare did not interleave the parent inside that await), so it is covered by code reading only.
test('a concurrent child login and key replacement never leave an old-key socket open', async () => {
  const mf = runtime(), outcomes = { 101: 0, 401: 0 };
  try {
    for (let n = 0; n < 20; n++) {
      const f = family(randomUUID()), first = childIdentity(), second = childIdentity();
      (await connection(mf, f, 'parent', headerV2(f, 'parent', first))).close();
      const [child, parent] = await Promise.all([
        connection(mf, f, 'child', headerV2(f, 'child', first)), connection(mf, f, 'parent', headerV2(f, 'parent', second))]);
      assert.equal(parent.response.status, 101);
      // Either refused, or accepted before the replacement and then closed by it: never an open socket for the old key.
      assert.ok([101, 401].includes(child.response.status));
      outcomes[child.response.status]++;
      await noOpenChild(parent);
      assert.equal((await owner(mf, f)).child_key, second.publicKey);
      parent.close();
    }
    console.log(`race outcomes: ${JSON.stringify(outcomes)}`);
  } finally { await mf.dispose(); }
});

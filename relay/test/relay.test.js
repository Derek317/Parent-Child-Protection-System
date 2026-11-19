import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { family, header, runtime, connection, publish, consume, wire, sql } from './helpers.js';

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
    const parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM parent_inbox'))[0].count, 0);
    // Source phone is still free to resubmit its retained unconfirmed record.
    assert.equal((await publish(child, 'pending:0')).type, 'accepted');
    assert.equal((await parent.next(m => m.type === 'message')).wire, wire('pending:0'));
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

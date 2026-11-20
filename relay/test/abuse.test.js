import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { family, header, runtime, connection, publish, wire, sql, vector } from './helpers.js';

// Miniflare's local simulator keeps its own bookkeeping tables (__miniflare_*, _cf_*); only the relay's tables matter.
const tables = async (mf, room) => (await sql(mf, room, "SELECT name FROM sqlite_master WHERE type='table'")).map(r => r.name).filter(n => !/^(__miniflare|_cf_)/.test(n));
const DAY = 86_400_000;

test('unauthenticated upgrades to random rooms leave no tables or rows', async () => {
  const mf = runtime();
  try {
    const rooms = [];
    for (let n = 0; n < 25; n++) {
      const f = { ...family(), room: randomBytes(32).toString('hex') };
      rooms.push(f.room);
      // Child with a well-formed but unregistered room: the app-visible 409 is unchanged.
      assert.equal((await connection(mf, f, 'child', header(f, 'child'))).response.status, 409);
      // Parent whose proof does not match the room: refused before any storage write.
      assert.equal((await connection(mf, f, 'parent', header(f, 'parent'))).response.status, 401);
    }
    for (const room of rooms) assert.deepEqual(await tables(mf, room), [], `room ${room.slice(0, 8)} stored a schema`);
  } finally { await mf.dispose(); }
});

test('the schema appears only after a verified parent registration', async () => {
  const mf = runtime();
  try {
    const f = family();
    assert.equal((await connection(mf, f, 'child')).response.status, 409);
    assert.deepEqual(await tables(mf, f.room), []);
    const parent = await connection(mf, f); assert.equal(parent.response.status, 101);
    assert.deepEqual((await tables(mf, f.room)).sort(), ['child_inbox', 'metadata', 'packet_lifetimes', 'parent_inbox']);
    parent.close();
  } finally { await mf.dispose(); }
});

test('daily budget limits only ordinary data; no other class or direction is ever blocked by it', async () => {
  const mf = runtime();
  try {
    const f = family(), initial = await connection(mf, f); initial.close();
    const child = await connection(mf, f, 'child');
    const send = async (client, id, kind) => {
      for (;;) {
        const result = await publish(client, id, wire(id), kind);
        if (result.code !== 'rate_limited') return result;
        await delay(result.retry_after_ms);
      }
    };
    const today = Math.floor(Date.now() / DAY);
    await sql(mf, f.room, "INSERT OR REPLACE INTO metadata(key,value) VALUES('budget',?)", JSON.stringify({ day: today, data: 1999, total: 5999 }));
    assert.equal((await publish(child, 'budget:last')).mode, 'buffered');
    const refused = await publish(child, 'budget:over');
    assert.equal(refused.code, 'queue_full');
    assert.equal(refused.retry_after_ms, 30_000);
    // Duplicate of a stored frame is not a new write.
    assert.equal((await publish(child, 'budget:last')).type, 'accepted');
    // The child floods always-buffered control frames: they are not counted and never get a budget refusal.
    for (let n = 0; n < 200; n++) assert.equal((await send(child, `flood:${n}`, 'control')).type, 'accepted');
    assert.equal((await publish(child, 'budget:over2')).code, 'queue_full');
    // Other kinds and the other direction are unaffected.
    assert.equal((await send(child, 'u:1', 'urgent')).type, 'accepted');
    const parent = await connection(mf, f);
    await parent.next(m => m.type === 'ready');
    for (const kind of ['control', 'receipt']) {
      const result = await send(parent, `parent:${kind}`, kind);
      assert.equal(result.type, 'accepted', `${kind} refused: ${JSON.stringify(result)}`);
    }
    // Forwarding to an online peer writes nothing and is not budgeted.
    assert.equal((await publish(child, 'budget:live')).mode, 'forwarded');
    parent.close();
    // A new UTC day starts a new budget.
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: today - 1, data: 5000 }));
    assert.equal((await publish(child, 'budget:tomorrow')).mode, 'buffered');
    child.close();
  } finally { await mf.dispose(); }
});

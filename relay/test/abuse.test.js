import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
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
    assert.deepEqual((await tables(mf, f.room)).sort(), ['child_inbox', 'metadata', 'parent_inbox']);
    parent.close();
  } finally { await mf.dispose(); }
});

test('daily buffered-data budget answers queue_full for data, never blocks urgent or forwarded frames', async () => {
  const mf = runtime();
  try {
    const f = family(), initial = await connection(mf, f); initial.close();
    const child = await connection(mf, f, 'child');
    await sql(mf, f.room, "INSERT OR REPLACE INTO metadata(key,value) VALUES('budget',?)", JSON.stringify({ day: Math.floor(Date.now() / DAY), data: 1999, total: 1999 }));
    assert.equal((await publish(child, 'budget:last')).mode, 'buffered');
    const refused = await publish(child, 'budget:over');
    assert.equal(refused.code, 'queue_full');
    assert.ok(refused.retry_after_ms >= 1000 && refused.retry_after_ms <= DAY);
    assert.equal((await publish(child, 'budget:urgent', wire('u'), 'urgent')).mode, 'buffered');
    assert.equal((await publish(child, 'budget:over')).code, 'queue_full');
    // A duplicate of an already stored frame is not a new write.
    assert.equal((await publish(child, 'budget:last')).type, 'accepted');
    // Forwarding to an online peer writes nothing and is not budgeted.
    const parent = await connection(mf, f);
    await parent.next(m => m.type === 'ready');
    assert.equal((await publish(child, 'budget:live')).mode, 'forwarded');
    parent.close();
    // A new UTC day starts a new budget.
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: Math.floor(Date.now() / DAY) - 1, data: 5000, total: 9000 }));
    assert.equal((await publish(child, 'budget:tomorrow')).mode, 'buffered');
    // Total cap: any kind is refused once the room has spent its whole daily budget (retry-later path in the app).
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: Math.floor(Date.now() / DAY), data: 0, total: 5999 }));
    assert.equal((await publish(child, 'cap:1', wire('1'), 'urgent')).mode, 'buffered');
    assert.equal((await publish(child, 'cap:2', wire('2'), 'urgent')).code, 'queue_full');
    child.close();
  } finally { await mf.dispose(); }
});

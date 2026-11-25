import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { family, header, headerV2, childIdentity, runtime, connection, publish, consume, wire, sql, sha, vector } from './helpers.js';

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
    // Forwarding to an online peer stores no frame and spends no buffered-data budget, but it does write a lifetime row.
    assert.equal((await publish(child, 'budget:live')).mode, 'forwarded');
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS n FROM packet_lifetimes WHERE id=?', 'p' + sha('budget:live')))[0].n, 1);
    assert.equal(JSON.parse((await sql(mf, f.room, "SELECT value FROM metadata WHERE key='budget'"))[0].value).data, 2000);
    parent.close();
    // A new UTC day starts a new budget.
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: today - 1, data: 5000 }));
    assert.equal((await publish(child, 'budget:tomorrow')).mode, 'buffered');
    child.close();
  } finally { await mf.dispose(); }
});

test('200 bad child logins to a locked room write no storage and do not grow the nonce list', async () => {
  const mf = runtime();
  try {
    const f = family(), key = childIdentity(), other = childIdentity();
    const parent = await connection(mf, f, 'parent', headerV2(f, 'parent', key)); await parent.next(m => m.type === 'ready');
    const proven = headerV2(f, 'child', key);
    const child = await connection(mf, f, 'child', proven); await child.next(m => m.type === 'ready');
    const snapshot = async () => ({ tables: await tables(mf, f.room), metadata: await sql(mf, f.room, 'SELECT key,value FROM metadata ORDER BY key'),
      rows: await sql(mf, f.room, 'SELECT (SELECT COUNT(*) FROM parent_inbox) AS p, (SELECT COUNT(*) FROM child_inbox) AS c, (SELECT COUNT(*) FROM packet_lifetimes) AS l') });
    const before = await snapshot();
    assert.equal(JSON.parse(before.metadata.find(r => r.key === 'owner').value).child_nonces.length, 1);
    const attempts = [
      () => header(f, 'child'),
      () => headerV2(f, 'child', other),
      () => headerV2(f, 'child', key, { signer: other }),
      () => headerV2(f, 'child', key, { ts: Date.now() - 121_000 }),
      () => proven,
      () => headerV2(f, 'child', key, { capability: randomBytes(32).toString('base64url') }),
    ];
    for (let n = 0; n < 200; n++) {
      assert.equal((await connection(mf, f, 'child', attempts[n % attempts.length]())).response.status, 401, `attempt ${n}`);
    }
    assert.deepEqual(await snapshot(), before);
    assert.equal((await publish(parent, 'survived')).mode, 'forwarded');
    assert.equal((await child.next(m => m.type === 'message')).wire, wire('survived'));
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

// Retries a publish the per-socket rate limit turned away, as the app does.
const sendKind = async (client, id, kind = 'data') => {
  for (;;) {
    const result = await publish(client, id, wire(id), kind);
    if (result.code !== 'rate_limited') return result;
    await delay(result.retry_after_ms);
  }
};
const lifetimeRows = async (mf, room) => (await sql(mf, room, 'SELECT COUNT(*) AS n FROM packet_lifetimes'))[0].n;
const spent = async (mf, room) => JSON.parse((await sql(mf, room, "SELECT value FROM metadata WHERE key='budget'"))[0].value);

test('new packet ids are budgeted per day, data cannot use the share kept for alerts, receipts and controls, and a refusal is retried', async () => {
  const mf = runtime();
  try {
    const f = family(), parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    const child = await connection(mf, f, 'child'); await child.next(m => m.type === 'ready');
    const today = Math.floor(Date.now() / DAY);
    // Both phones online: forwarding is the path that used to write a 97-day row per publish without any limit.
    await sql(mf, f.room, "INSERT OR REPLACE INTO metadata(key,value) VALUES('budget',?)", JSON.stringify({ day: today, data: 0, fresh: { child: 9_999 } }));
    assert.equal((await publish(child, 'd:last')).mode, 'forwarded');
    const before = await lifetimeRows(mf, f.room);
    const refusedData = await publish(child, 'd:over');
    assert.deepEqual([refusedData.code, refusedData.retry_after_ms], ['queue_full', 30_000]);
    assert.equal(await lifetimeRows(mf, f.room), before, 'a refused publish wrote a lifetime row');
    // Alerts still pass once data has used its share.
    assert.equal((await publish(child, 'u:ok', wire('u:ok'), 'urgent')).mode, 'forwarded');
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: today, data: 0, fresh: { parent: 29_999, child: 30_000 } }));
    assert.equal((await publish(parent, 'r:last', wire('r:last'), 'receipt')).mode, 'forwarded');
    const full = await lifetimeRows(mf, f.room);
    for (const [client, id, kind] of [[parent, 'c:over', 'control'], [child, 'u:over', 'urgent'], [parent, 'r:over', 'receipt']]) {
      const refused = await publish(client, id, wire(id), kind);
      assert.deepEqual([refused.code, refused.retry_after_ms], ['queue_full', 30_000], `${kind}: ${JSON.stringify(refused)}`);
    }
    assert.equal(await lifetimeRows(mf, f.room), full);
    assert.equal(await sql(mf, f.room, 'SELECT COUNT(*) AS n FROM child_inbox').then(r => r[0].n), 0, 'a refused control was stored');
    // A frame the relay already holds is not a new id: its duplicate is still answered.
    assert.equal((await publish(child, 'u:ok', wire('u:ok'), 'urgent')).type, 'accepted');
    // The next UTC day the same ids, retried by the phones, go through and arrive.
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: today - 1, data: 0, fresh: { parent: 30_000, child: 30_000 } }));
    assert.equal((await publish(child, 'd:over')).mode, 'forwarded');
    assert.equal((await parent.next(m => m.type === 'message' && m.wire === wire('d:over'))).buffered, false);
    assert.equal((await publish(parent, 'c:over', wire('c:over'), 'control')).mode, 'buffered');
    consume(child, await child.next(m => m.type === 'message' && m.wire === wire('c:over')));
    assert.equal((await publish(child, 'u:over', wire('u:over'), 'urgent')).mode, 'forwarded');
    assert.deepEqual((await spent(mf, f.room)).fresh, { child: 2, parent: 1 });
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('one phone at its new-id limit never blocks the other phone; a stored shared count starts both roles at zero', async () => {
  const mf = runtime();
  try {
    const f = family(), parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    const child = await connection(mf, f, 'child'); await child.next(m => m.type === 'ready');
    const today = Math.floor(Date.now() / DAY);
    // A compromised or buggy child has spent its whole day.
    await sql(mf, f.room, "INSERT OR REPLACE INTO metadata(key,value) VALUES('budget',?)", JSON.stringify({ day: today, data: 0, fresh: { child: 30_000 } }));
    for (const kind of ['urgent', 'control']) assert.equal((await publish(child, `child:${kind}`, wire(`child:${kind}`), kind)).code, 'queue_full');
    // The parent's remote command, receipt and urgent record still go through.
    assert.equal((await publish(parent, 'parent:control', wire('parent:control'), 'control')).mode, 'buffered');
    assert.equal((await publish(parent, 'parent:receipt', wire('parent:receipt'), 'receipt')).mode, 'forwarded');
    assert.equal((await publish(parent, 'parent:urgent', wire('parent:urgent'), 'urgent')).mode, 'forwarded');
    assert.equal((await publish(parent, 'parent:data')).mode, 'forwarded');
    assert.deepEqual((await spent(mf, f.room)).fresh, { child: 30_000, parent: 4 });
    // And the other way round.
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: today, data: 0, fresh: { parent: 30_000 } }));
    assert.equal((await publish(parent, 'parent:over', wire('parent:over'), 'control')).code, 'queue_full');
    for (const kind of ['urgent', 'receipt', 'control']) assert.equal((await publish(child, `child2:${kind}`, wire(`child2:${kind}`), kind)).type, 'accepted', kind);
    // A row written with one shared number (or none) counts from zero for each role.
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='budget'", JSON.stringify({ day: today, data: 0, fresh: 29_999 }));
    assert.equal((await publish(child, 'legacy:child', wire('legacy:child'), 'urgent')).mode, 'forwarded');
    assert.deepEqual((await spent(mf, f.room)).fresh, { child: 1 });
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('a busy family day stays far inside the new-id budget and nothing is refused', async () => {
  const mf = runtime();
  try {
    const f = family(), parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    const child = await connection(mf, f, 'child'); await child.next(m => m.type === 'ready');
    // Several times a real day: 400 context frames and 40 alerts forwarded, each answered by a receipt, plus controls.
    let ids = 0;
    for (let n = 0; n < 440; n++, ids++) {
      const id = `day:${n}`, kind = n % 11 === 0 ? 'urgent' : 'data';
      assert.equal((await sendKind(child, id, kind)).mode, 'forwarded', id);
      assert.equal((await sendKind(parent, `receipt:${n}`, 'receipt')).mode, 'forwarded'); ids++;
    }
    for (let n = 0; n < 20; n++, ids++) assert.equal((await sendKind(parent, `control:${n}`, 'control')).mode, 'buffered');
    // Then the parent is offline for the evening: the child buffers its records.
    parent.close(); await delay(50);
    for (let n = 0; n < 200; n++, ids++) assert.equal((await sendKind(child, `evening:${n}`, n % 10 === 0 ? 'urgent' : 'data')).mode, 'buffered');
    const budget = await spent(mf, f.room);
    assert.equal(budget.fresh.parent + budget.fresh.child, ids);
    assert.equal(budget.data, 180);
    for (const role of ['parent', 'child']) assert.ok(budget.fresh[role] < 30_000 / 20, `a busy day used ${budget.fresh[role]} ${role} ids`);
    child.close();
  } finally { await mf.dispose(); }
});

test('an existing room gets the lifetime expiry index on its next wake and keeps its rows', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'ig-lifetime-index-'));
  let mf = runtime(directory);
  try {
    const f = family(), parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    for (let n = 0; n < 5; n++) assert.equal((await publish(parent, `old:${n}`, wire(`old:${n}`), 'control')).mode, 'buffered');
    parent.close();
    // A room stored by the previous relay: lifetime rows and no index.
    await sql(mf, f.room, 'DROP INDEX IF EXISTS packet_lifetimes_expires');
    const rows = await sql(mf, f.room, 'SELECT id,digest,kind,expires FROM packet_lifetimes ORDER BY id');
    assert.equal(rows.length, 5);
    await mf.dispose(); mf = runtime(directory);
    const child = await connection(mf, f, 'child'); assert.equal(child.response.status, 101);
    assert.deepEqual(await sql(mf, f.room, 'SELECT id,digest,kind,expires FROM packet_lifetimes ORDER BY id'), rows);
    const plan = async query => (await sql(mf, f.room, 'EXPLAIN QUERY PLAN ' + query, 0)).map(r => r.detail).join(' | ');
    assert.match(await plan('DELETE FROM packet_lifetimes WHERE expires<=?'), /packet_lifetimes_expires/);
    assert.match(await plan('SELECT MIN(expires) FROM packet_lifetimes WHERE expires>?'), /packet_lifetimes_expires/);
    child.close();
  } finally { await mf.dispose(); await rm(directory, { recursive: true, force: true }); }
});

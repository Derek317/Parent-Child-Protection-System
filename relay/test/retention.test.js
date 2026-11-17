import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { family, header, runtime, connection, publish, consume, sql } from './helpers.js';

const day = 86_400_000;
const owner = async (mf, f) => JSON.parse((await sql(mf, f.room, "SELECT value FROM metadata WHERE key='owner'"))[0].value);

test('HTTP retirement requires a fresh signed parent proof and purges before acknowledgment', async () => {
  const mf = runtime();
  try {
    const f = family(), auth = header(f), p = await connection(mf, f, 'parent', auth); p.close();
    const c = await connection(mf, f, 'child'); await publish(c, 'pending');
    const remove = authorization => mf.dispatchFetch(`https://relay.example/v1/rooms/${f.room}`, { method: 'DELETE', headers: { Authorization: authorization } });
    assert.equal((await remove(header(f, 'child'))).status, 401);
    assert.equal((await remove(auth)).status, 401);
    assert.equal((await remove(header({ ...f, capability_hash: 'a'.repeat(64) }))).status, 401);
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS n FROM parent_inbox'))[0].n, 1);
    assert.equal((await remove(header(f))).status, 204);
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS n FROM parent_inbox'))[0].n, 0);
    assert.equal((await connection(mf, f, 'child')).response.status, 410);
  } finally { await mf.dispose(); }
});

test('valid parent retirement also revokes an empty room before its first registration', async () => {
  const mf = runtime();
  try {
    const f = family();
    const response = await mf.dispatchFetch(`https://relay.example/v1/rooms/${f.room}`, { method: 'DELETE', headers: { Authorization: header(f) } });
    assert.equal(response.status, 204);
    assert.equal((await connection(mf, f, 'child')).response.status, 410);
  } finally { await mf.dispose(); }
});

test('relay ciphertext has a 72-hour nonrenewing deadline', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f); p.close();
    const c = await connection(mf, f, 'child'), now = Date.now();
    await publish(c, 'ttl:0');
    const first = (await sql(mf, f.room, 'SELECT expires FROM parent_inbox'))[0].expires;
    assert.ok(first >= now + 3 * day && first <= Date.now() + 3 * day);
    await publish(c, 'ttl:0');
    assert.equal((await sql(mf, f.room, 'SELECT expires FROM parent_inbox'))[0].expires, first);
    c.close();
  } finally { await mf.dispose(); }
});

test('only authenticated parent can retire; purge closes peers and prevents old capability reuse', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f), c = await connection(mf, f, 'child');
    await publish(p, 'control', undefined, 'control');
    c.send({ type: 'retire' });
    assert.equal((await c.next(m => m.type === 'error')).code, 'forbidden');
    p.send({ type: 'retire', extra: true });
    assert.equal((await p.next(m => m.type === 'error')).code, 'invalid_packet');
    const closed = Promise.all([p, c].map(client => new Promise(resolve => client.socket.addEventListener('close', resolve, { once: true }))));
    p.send({ type: 'retire' });
    await p.next(m => m.type === 'retired'); await closed;
    for (const table of ['parent_inbox', 'child_inbox']) assert.equal((await sql(mf, f.room, `SELECT COUNT(*) AS n FROM ${table}`))[0].n, 0);
    assert.equal((await sql(mf, f.room, "SELECT COUNT(*) AS n FROM metadata WHERE key='owner'"))[0].n, 0);
    assert.equal((await connection(mf, f, 'child')).response.status, 410);
    assert.equal((await connection(mf, f)).response.status, 410);
  } finally { await mf.dispose(); }
});

test('idle registration expiry purges data and enters a bounded revocation period', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f); p.close();
    const c = await connection(mf, f, 'child'); await publish(c, 'pending'); c.close();
    const metadata = await owner(mf, f);
    assert.ok(metadata.last_active >= Date.now() - 5000);
    metadata.last_active = Date.now() - 90 * day - 1;
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='owner'", JSON.stringify(metadata));
    assert.equal((await connection(mf, f, 'child')).response.status, 410);
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS n FROM parent_inbox'))[0].n, 0);
    assert.equal((await connection(mf, f)).response.status, 410);
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='revoked_until'", String(Date.now() - 1));
    assert.equal((await connection(mf, f, 'child')).response.status, 409);
    const fresh = await connection(mf, f); assert.equal(fresh.response.status, 101); fresh.close();
  } finally { await mf.dispose(); }
});

test('retirement survives restart and in-flight publish cannot recreate the room', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'ig-retention-'));
  let mf = runtime(directory);
  try {
    const f = family(), p = await connection(mf, f), c = await connection(mf, f, 'child');
    for (let i = 0; i < 20; i++) c.send({ type: 'publish', id: `race:${i}`, wire: JSON.stringify({ e2ee: btoa('0'.repeat(40)) }), kind: 'data' });
    p.send({ type: 'retire' }); await p.next(m => m.type === 'retired');
    await mf.dispose(); mf = runtime(directory);
    assert.equal((await connection(mf, f)).response.status, 410);
    assert.equal((await connection(mf, f, 'child')).response.status, 410);
    for (const table of ['parent_inbox', 'child_inbox', 'packet_lifetimes']) assert.equal((await sql(mf, f.room, `SELECT COUNT(*) AS n FROM ${table}`))[0].n, 0);
    assert.deepEqual((await sql(mf, f.room, 'SELECT key FROM metadata')).map(row => row.key), ['revoked_until']);
  } finally { await mf.dispose(); await rm(directory, { recursive: true, force: true }); }
});

test('forwarded and consumed packets retain their original deadline on later resubmission', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f), c = await connection(mf, f, 'child');
    await publish(c, 'forwarded'); await p.next(m => m.type === 'message');
    await sql(mf, f.room, 'UPDATE packet_lifetimes SET expires=?', Date.now() - 1);
    assert.equal((await publish(c, 'forwarded')).code, 'expired');
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS n FROM parent_inbox'))[0].n, 0);
    p.close(); c.close();
  } finally { await mf.dispose(); }
});

test('legacy registration gets an idle grace period while seven-day packets keep their first enqueue time', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f); p.close();
    const c = await connection(mf, f, 'child'); await publish(c, 'legacy'); c.close();
    const legacy = await owner(mf, f); delete legacy.last_active;
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='owner'", JSON.stringify(legacy));
    await sql(mf, f.room, 'DELETE FROM packet_lifetimes');
    const originalDeadline = Date.now() + 6 * day;
    await sql(mf, f.room, 'UPDATE parent_inbox SET expires=?', originalDeadline);
    const parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    assert.equal((await sql(mf, f.room, 'SELECT expires FROM parent_inbox'))[0].expires, originalDeadline - 4 * day);
    assert.ok((await owner(mf, f)).last_active >= Date.now() - 5000);
    parent.close();
    const again = await connection(mf, f);
    assert.equal((await sql(mf, f.room, 'SELECT expires FROM parent_inbox'))[0].expires, originalDeadline - 4 * day);
    again.close();
  } finally { await mf.dispose(); }
});

test('legacy packets already older than 72 hours are purged and cannot be resubmitted', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f); p.close();
    const c = await connection(mf, f, 'child'); await publish(c, 'legacy-expired'); c.close();
    await sql(mf, f.room, 'DELETE FROM packet_lifetimes');
    await sql(mf, f.room, 'UPDATE parent_inbox SET expires=?', Date.now() + 3 * day);
    const parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS n FROM parent_inbox'))[0].n, 0);
    const child = await connection(mf, f, 'child');
    assert.equal((await publish(child, 'legacy-expired')).code, 'expired');
    parent.close(); child.close();
  } finally { await mf.dispose(); }
});

test('legacy deadline conversion rolls back when lifetime storage fails', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f); p.close();
    const c = await connection(mf, f, 'child'); await publish(c, 'legacy-atomic'); c.close();
    await sql(mf, f.room, 'DELETE FROM packet_lifetimes');
    const original = Date.now() + 6 * day;
    await sql(mf, f.room, 'UPDATE parent_inbox SET expires=?', original);
    await sql(mf, f.room, "CREATE TRIGGER refuse_lifetime BEFORE INSERT ON packet_lifetimes BEGIN SELECT RAISE(FAIL,'test storage unavailable'); END");
    assert.equal((await connection(mf, f)).response.status, 503);
    assert.equal((await sql(mf, f.room, 'SELECT expires FROM parent_inbox'))[0].expires, original);
    await sql(mf, f.room, 'DROP TRIGGER refuse_lifetime');
    const parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
    assert.equal((await sql(mf, f.room, 'SELECT expires FROM parent_inbox'))[0].expires, original - 4 * day);
    parent.close();
  } finally { await mf.dispose(); }
});

test('consumption and repeated retirement never renew packet or revocation deadlines', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f), c = await connection(mf, f, 'child');
    await publish(p, 'consumed-control', undefined, 'control');
    const packet = await c.next(m => m.type === 'message'); consume(c, packet);
    await c.next(m => m.type === 'consumed');
    const original = (await sql(mf, f.room, 'SELECT expires FROM packet_lifetimes'))[0].expires;
    await publish(p, 'consumed-control', undefined, 'control');
    assert.equal((await sql(mf, f.room, 'SELECT expires FROM child_inbox'))[0].expires, original);
    await sql(mf, f.room, 'UPDATE child_inbox SET expires=1');
    await sql(mf, f.room, 'UPDATE packet_lifetimes SET expires=1');
    assert.equal((await publish(p, 'consumed-control', undefined, 'control')).code, 'expired');
    const remove = authorization => mf.dispatchFetch(`https://relay.example/v1/rooms/${f.room}`, { method: 'DELETE', headers: { Authorization: authorization } });
    assert.equal((await remove(header(f))).status, 204);
    const until = (await sql(mf, f.room, "SELECT value FROM metadata WHERE key='revoked_until'"))[0].value;
    assert.equal((await remove(header(f, 'child'))).status, 401);
    const forged = JSON.parse(Buffer.from(header(f).slice(8), 'base64url'));
    forged.signature = 'a'.repeat(forged.signature.length);
    assert.equal((await remove('IGGuard ' + Buffer.from(JSON.stringify(forged)).toString('base64url'))).status, 401);
    assert.equal((await remove(header(f))).status, 204);
    assert.equal((await sql(mf, f.room, "SELECT value FROM metadata WHERE key='revoked_until'"))[0].value, until);
  } finally { await mf.dispose(); }
});

test('legacy owners without lifecycle metadata still authenticate child access and support parent retirement', async () => {
  const mf = runtime();
  try {
    const f = family(), p = await connection(mf, f); p.close();
    const legacy = await owner(mf, f); delete legacy.last_active; delete legacy.epoch;
    await sql(mf, f.room, "UPDATE metadata SET value=? WHERE key='owner'", JSON.stringify(legacy));
    const badChild = { ...f, capability: 'a'.repeat(43) };
    assert.equal((await connection(mf, badChild, 'child')).response.status, 401);
    const c = await connection(mf, f, 'child'); assert.equal(c.response.status, 101);
    assert.ok((await owner(mf, f)).last_active >= Date.now() - 5000);
    assert.equal((await publish(c, 'legacy-owner')).type, 'accepted');
    const parent = await connection(mf, f); await parent.next(m => m.type === 'message');
    parent.send({ type: 'retire' }); await parent.next(m => m.type === 'retired');
    assert.equal((await connection(mf, f, 'child')).response.status, 410);
  } finally { await mf.dispose(); }
});

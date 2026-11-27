import { test } from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { DatabaseSync } from 'node:sqlite';
import { family, header, headerV2, childIdentity } from './proofs.js';

registerHooks({ resolve: (specifier, context, next) => specifier === 'cloudflare:workers'
  ? { url: 'data:text/javascript,export class DurableObject {}', shortCircuit: true } : next(specifier, context) });
globalThis.WebSocketRequestResponsePair ??= class {};
const { default: worker, FamilyRoom } = await import('../src/worker.js');

async function route(authorization, { room, method = 'GET' } = {}) {
  let reached = 0;
  const env = { FAMILIES: { idFromName: name => name, get: () => ({ fetch: async () => { reached++; return new Response(null, { status: 204 }); } }) } };
  const headers = new Headers({ Authorization: authorization });
  if (method === 'GET') headers.set('Upgrade', 'websocket');
  const response = await worker.fetch({ method, url: `https://relay.example/v1/rooms/${room}`, headers }, env);
  return { status: response.status, code: response.status === 204 ? null : (await response.json()).error.code, reached };
}
const forge = (authorization, change) => 'IGGuard ' + Buffer.from(JSON.stringify({
  ...JSON.parse(Buffer.from(authorization.slice(8), 'base64url')), ...change })).toString('base64url');

test('audit M5: a proof that does not verify never reaches a Durable Object', async () => {
  const f = family(), other = family('01234567-89ab-cdef-0123-456789abcdef'), child = childIdentity();
  const otherSignature = JSON.parse(Buffer.from(header(other).slice(8), 'base64url')).signature;
  for (const [name, authorization, room = f.room, method] of [
    ['parent, bad signature', forge(header(f), { signature: otherSignature })],
    ['parent v2, bad signature', forge(headerV2(f, 'parent', child), { signature: otherSignature })],
    ['parent, signed for another room', header(other)],
    ['parent, room id not its key', forge(header(f), { room: other.room }), other.room],
    ['parent, stale', forge(header(f), { ts_ms: Date.now() - 121_000 })],
    ['child v2, signed by another key', headerV2(f, 'child', child, { signer: childIdentity() })],
    ['child v2, another room', headerV2(other, 'child', child)],
    ['child v1, another room', header(other, 'child')],
    ['child DELETE', header(f, 'child'), f.room, 'DELETE'],
  ]) {
    const result = await route(authorization, { room, method });
    assert.equal(result.status, 401, name);
    assert.equal(result.reached, 0, name);
  }
});

test('a genuine proof reaches its room; a genuine one outside the time window is answered stale_time', async () => {
  const f = family(), child = childIdentity();
  for (const authorization of [header(f), headerV2(f, 'parent', child), headerV2(f, 'child', child), header(f, 'child')]) {
    assert.equal((await route(authorization, { room: f.room })).reached, 1);
  }
  assert.equal((await route(header(f), { room: f.room, method: 'DELETE' })).reached, 1);
  for (const authorization of [headerV2(f, 'parent', child, { ts: Date.now() - 121_000 }), headerV2(f, 'child', child, { ts: Date.now() + 121_000 })]) {
    assert.deepEqual(await route(authorization, { room: f.room }), { status: 401, code: 'stale_time', reached: 0 });
  }
});

function room() {
  const db = new DatabaseSync(':memory:');
  let alarm = null;
  const exec = (query, ...params) => {
    const statement = db.prepare(query);
    const rows = /^\s*SELECT/i.test(query) ? statement.all(...params) : (statement.run(...params), []);
    return { toArray: () => rows, one: () => rows[0] };
  };
  const transactionSync = action => { db.exec('BEGIN'); try { const result = action(); db.exec('COMMIT'); return result; } catch (e) { db.exec('ROLLBACK'); throw e; } };
  const ctx = { storage: { sql: { exec }, transactionSync, getAlarm: async () => alarm, setAlarm: async at => { alarm = at; } },
    getWebSockets: () => [], setWebSocketAutoResponse() {} };
  const r = new FamilyRoom(ctx, {});
  return Object.assign(r, { alarmAt: () => alarm, tables: () => exec("SELECT name FROM sqlite_master WHERE type='table'").toArray().map(t => t.name),
    marker: () => exec("SELECT value FROM metadata WHERE key='retired'").toArray()[0]?.value,
    setMarker: value => exec("UPDATE metadata SET value=? WHERE key='retired'", value) });
}

test('audit L6: a retirement marker is kept a year, then the room is forgotten', async () => {
  const year = 365 * 86_400_000, r = room();
  r.ensureSchema(); r.retire(); await r.ensureAlarm();
  assert.ok(Math.abs(Number(r.marker()) - Date.now()) < 5000, 'the marker records when the room was retired');
  assert.ok(Math.abs(r.alarmAt() - (Number(r.marker()) + year)) < 5000, 'an alarm is set for its end');
  r.setMarker(String(Date.now() - year + 60_000)); await r.alarm();
  assert.ok(r.retired(), 'a marker younger than a year stays');
  r.setMarker('1'); await r.alarm();
  assert.ok(r.retired() && Math.abs(Number(r.marker()) - Date.now()) < 5000, 'a marker from before dates had a year from now');
  r.setMarker(String(Date.now() - year - 1)); await r.alarm();
  assert.equal(r.retired(), false);
  assert.deepEqual(r.tables(), [], 'nothing of the room is left');
  await r.ensureAlarm(); r.cleanupExpired();
  assert.deepEqual(r.tables(), [], 'and nothing comes back by itself');
});

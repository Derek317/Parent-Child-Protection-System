import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { family, header, headerV2, childIdentity, runtime, connection, publish, consume, wire, sha, sql } from './helpers.js';

const owner = async (mf, f) => JSON.parse((await sql(mf, f.room, "SELECT value FROM metadata WHERE key='owner'"))[0].value);
const count = async (mf, f, table) => (await sql(mf, f.room, `SELECT COUNT(*) AS n FROM ${table}`))[0].n;
// Miniflare reports a relay-side close to the client only at shutdown: with no open child, a parent record is buffered.
const noOpenChild = async parent => assert.equal((await publish(parent, `probe:${randomUUID()}`)).mode, 'buffered');
// Status and Retry-After only (reading a refused body can make Miniflare's shutdown throw ECONNRESET on this PC).
const refused = async (mf, f, authorization, status = 401, retryAfter = '60', role = 'child') => {
  const { response } = await connection(mf, f, role, authorization);
  assert.equal(response.status, status);
  assert.equal(response.headers.get('Retry-After'), retryAfter);
};
const RETIRED = String(90 * 24 * 60 * 60);
// connection() plus a log of every server message, in arrival order (ready first).
async function join(mf, f, role = 'parent', authorization = header(f, role)) {
  const c = await connection(mf, f, role, authorization);
  assert.equal(c.response.status, 101);
  c.log = [];
  c.socket.addEventListener('message', e => c.log.push(JSON.parse(e.data)));
  c.log.push(await c.next(m => m.type === 'ready'));
  return c;
}
const slug = name => name.replace(/[^a-z0-9]+/gi, '_');
const got = async (c, id) => { const m = await c.next(x => x.type === 'message'); assert.equal(m.wire, wire(id)); return m; };

// ---- 1. compatibility: a room that never gets a child key behaves exactly as before ----
// Server message key sets before commit 0beb6d9; `ready` gained only `features`.
const V1_SHAPES = {
  ready: ['peer_online,protocol,role,type'], peer: ['online,type'], message: ['buffered,delivery_id,digest,id,type,wire'],
  accepted: ['id,mode,type'], consumed: ['id,type'], replay_complete: ['type'], retired: ['type'],
  error: ['code,id,retry_after_ms,type', 'code,retry_after_ms,type'],
};
const shape = m => Object.keys(m).sort().join(',');

test('v1 life cycle in a room without a child key: same statuses, message types and key sets as before, plus only ready.features', async () => {
  const mf = runtime();
  try {
    const f = family(), logs = [];
    assert.equal((await connection(mf, f, 'child')).response.status, 409);
    assert.equal((await mf.dispatchFetch(`https://relay.example/v1/rooms/${f.room}`)).status, 426);
    assert.equal((await mf.dispatchFetch('https://relay.example/nope')).status, 404);
    assert.deepEqual(await (await mf.dispatchFetch('https://relay.example/health')).json(), { service: 'ig-guard-relay', protocol: 1 });

    const parent = await join(mf, f); logs.push(parent.log);
    assert.equal(parent.log[0].peer_online, false);
    const child = await join(mf, f, 'child'); logs.push(child.log);
    assert.equal(child.log[0].peer_online, true);
    assert.deepEqual(await parent.next(m => m.type === 'peer'), { type: 'peer', online: true });

    assert.equal((await publish(child, 'a')).mode, 'forwarded');
    assert.equal((await got(parent, 'a')).buffered, false);
    assert.equal((await publish(parent, 'b')).mode, 'forwarded');
    assert.equal((await got(child, 'b')).buffered, false);
    assert.equal((await publish(parent, 'ctl', wire('ctl'), 'control')).mode, 'buffered');
    const control = await got(child, 'ctl'); assert.equal(control.buffered, true);
    consume(child, control); assert.equal((await child.next(m => m.type === 'consumed')).id, control.id);
    // error shapes
    child.send({ type: 'publish', id: 'x', kind: 'data', wire: 'plaintext' });
    assert.deepEqual(Object.keys(await child.next(m => m.type === 'error')).sort(), ['code', 'retry_after_ms', 'type']);
    assert.equal((await publish(child, 'a', wire('different'))).code, 'id_conflict');
    child.send({ type: 'consume', id: 'bad', digest: 'bad' });
    assert.equal((await child.next(m => m.type === 'error')).code, 'invalid_consumption');
    // offline peer: mailbox, peer notices, replay, acknowledgement
    parent.close();
    assert.deepEqual(await child.next(m => m.type === 'peer'), { type: 'peer', online: false });
    assert.equal((await publish(child, 'off')).mode, 'buffered');
    const again = await join(mf, f); logs.push(again.log);
    assert.equal(again.log[0].peer_online, true);
    const mail = await got(again, 'off'); assert.equal(mail.buffered, true);
    consume(again, mail); await again.next(m => m.type === 'consumed');
    again.send({ type: 'replay' }); await again.next(m => m.type === 'replay_complete');
    // last login wins
    const second = await join(mf, f, 'child'); logs.push(second.log);
    assert.equal((await publish(again, 'to-second')).mode, 'forwarded');
    await got(second, 'to-second');
    assert.equal(child.log.some(m => m.type === 'message' && m.wire === wire('to-second')), false);
    logs.push(child.log);

    const record = await owner(mf, f);
    assert.deepEqual(Object.keys(record).sort(), ['capability_hash', 'epoch', 'last_active', 'nonces', 'public_key', 'scope']);
    // retirement
    again.send({ type: 'retire' }); await again.next(m => m.type === 'retired');
    await refused(mf, f, header(f, 'child'), 410, RETIRED);
    await refused(mf, f, header(f), 410, RETIRED, 'parent');
    assert.equal((await mf.dispatchFetch(`https://relay.example/v1/rooms/${f.room}`, { method: 'DELETE', headers: { Authorization: header(f) } })).status, 204);

    let seen = 0;
    for (const m of logs.flat()) {
      seen++;
      const { features, ...rest } = m.type === 'ready' ? m : { ...m };
      if (m.type === 'ready') assert.deepEqual(features, ['child_proof']);
      assert.ok(V1_SHAPES[m.type]?.includes(shape(rest)), `unexpected ${m.type} shape ${shape(m)}`);
    }
    assert.ok(seen >= 20);
  } finally { await mf.dispose(); }
});

// ---- 2. v1-only client model ----
// Mirrors CloudRelay.kt (repo root), RelaySocket.incoming:
//   :229 frame <= 8192 bytes, valid JSON;  :235-236 "ready" requires optInt("protocol")==1 and reads no other field;
//   :241-243 "accepted" mode in {forwarded,buffered};  :250-254 "message" needs id /[pc][0-3][a-f0-9]{64}/, digest /[a-f0-9]{64}/,
//   boolean `buffered`, wire < 4096 bytes, delivery_id /[a-f0-9-]{36}/, digest == sha256(wire);  :264 "peer" uses `online` only;
//   :265 "retired"; :266 "error" uses code/id/retry_after_ms.  Other types ("consumed", "replay_complete") have no branch.
// The real client silently ignores a type with no branch (the `when` at :234 has no else), and unknown fields are never read.
// This model is STRICTER: it also fails on any type outside the ones the 5.x relay is known to send.
const KNOWN = new Set(['ready', 'peer', 'message', 'accepted', 'consumed', 'replay_complete', 'retired', 'error']);
function v1Client(c, bad) {
  const check = (raw, m) => {
    if (Buffer.byteLength(raw) > 8192) bad.push('frame too large');
    if (!KNOWN.has(m.type)) bad.push(`unknown type ${m.type}`);
    if (m.type === 'ready' && m.protocol !== 1) bad.push('ready.protocol');
    if (m.type === 'accepted' && !['forwarded', 'buffered'].includes(m.mode)) bad.push('accepted.mode');
    if (m.type === 'message' && !(/^[pc][0-3][a-f0-9]{64}$/.test(m.id) && /^[a-f0-9]{64}$/.test(m.digest) && typeof m.buffered === 'boolean' &&
      Buffer.byteLength(m.wire) < 4096 && /^[a-f0-9-]{36}$/.test(m.delivery_id) && sha(m.wire) === m.digest)) bad.push('message fields');
    if (m.type === 'peer' && typeof m.online !== 'boolean') bad.push('peer.online');
    if (m.type === 'error' && !/^[a-z_]{1,40}$/.test(m.code)) bad.push('error.code');
  };
  c.log.forEach(m => check(JSON.stringify(m), m));
  c.socket.addEventListener('message', e => check(e.data, JSON.parse(e.data)));
}

test('a v1-only client model (requires ready.protocol 1, rejects unknown types, ignores unknown fields) completes a full session', async () => {
  const mf = runtime(), bad = [];
  try {
    const f = family();
    const parent = await join(mf, f); v1Client(parent, bad);
    const child = await join(mf, f, 'child'); v1Client(child, bad);
    for (const c of [parent, child]) { assert.equal(c.log[0].protocol, 1); assert.ok('features' in c.log[0]); }
    assert.equal((await publish(child, 'one')).mode, 'forwarded'); await got(parent, 'one');
    assert.equal((await publish(parent, 'two', wire('two'), 'urgent')).mode, 'forwarded'); await got(child, 'two');
    child.close(); await parent.next(m => m.type === 'peer' && !m.online);
    assert.equal((await publish(parent, 'three')).mode, 'buffered');
    const back = await join(mf, f, 'child'); v1Client(back, bad);
    const mail = await got(back, 'three'); consume(back, mail); await back.next(m => m.type === 'consumed');
    await parent.next(m => m.type === 'peer' && m.online);
    back.send({ type: 'replay' }); await back.next(m => m.type === 'replay_complete');
    parent.send({ type: 'retire' }); await parent.next(m => m.type === 'retired');
    assert.deepEqual(bad, []);
  } finally { await mf.dispose(); }
});

// ---- 3. locked room, end to end ----
test('locked room: proven child only; unproven attempts never disturb it or the mailbox; key replacement cuts the old child', async () => {
  const mf = runtime();
  try {
    const f = family(), key = childIdentity(), other = childIdentity(), next = childIdentity();
    const parent = await join(mf, f, 'parent', headerV2(f, 'parent', key));
    const first = headerV2(f, 'child', key);
    const child = await join(mf, f, 'child', first);
    assert.deepEqual(child.log[0].features, ['child_proof']);
    await parent.next(m => m.type === 'peer' && m.online);
    assert.equal((await publish(child, 'up')).mode, 'forwarded'); await got(parent, 'up');
    const attempts = [['v1 capability', () => header(f, 'child')], ['other key', () => headerV2(f, 'child', other)], ['replayed nonce', () => first]];
    for (const [name, make] of attempts) {
      await refused(mf, f, make());
      assert.equal((await publish(parent, `down:${slug(name)}`)).mode, 'forwarded'); await got(child, `down:${slug(name)}`);
      assert.equal((await publish(child, `up:${slug(name)}`)).mode, 'forwarded'); await got(parent, `up:${slug(name)}`);
    }
    // proven child offline: records are buffered, attempts in between get nothing
    child.close(); await parent.next(m => m.type === 'peer' && !m.online);
    for (const [name, make] of attempts) {
      assert.equal((await publish(parent, `off:${slug(name)}`)).mode, 'buffered');
      await refused(mf, f, make());
      assert.equal(await count(mf, f, 'child_inbox'), attempts.findIndex(a => a[0] === name) + 1);
    }
    const back = await join(mf, f, 'child', headerV2(f, 'child', key));
    assert.equal(back.log[0].peer_online, true);
    const delivered = [];
    for (let n = 0; n < attempts.length; n++) { const m = await back.next(x => x.type === 'message'); assert.equal(m.buffered, true); delivered.push(m.wire); consume(back, m); }
    assert.deepEqual(delivered.sort(), attempts.map(([name]) => wire(`off:${slug(name)}`)).sort());
    await back.next(m => m.type === 'consumed');
    // key replaced by the parent: the old child is cut (relay side), the new key is admitted
    const replacing = await join(mf, f, 'parent', headerV2(f, 'parent', next));
    assert.equal(replacing.log[0].peer_online, false);
    await noOpenChild(replacing);
    await refused(mf, f, headerV2(f, 'child', key));
    const fresh = await join(mf, f, 'child', headerV2(f, 'child', next));
    consume(fresh, await fresh.next(m => m.type === 'message')); // the probe above was buffered for the new child
    assert.equal((await publish(replacing, 'to-new')).mode, 'forwarded');
    assert.equal((await publish(fresh, 'from-new')).mode, 'forwarded'); await got(replacing, 'from-new');
    assert.equal(back.log.some(m => m.type === 'message' && m.wire === wire('to-new')), false);
  } finally { await mf.dispose(); }
});

// ---- 4. only the parent key holder can lock, unlock or re-key ----
test('a locked room is not unlocked or re-keyed by a v1 parent, a foreign key, a changed capability or a replay', async () => {
  const mf = runtime();
  try {
    const f = family(), key = childIdentity(), mine = childIdentity(), foreign = childIdentity();
    const v2 = headerV2(f, 'parent', key);
    const parent = await join(mf, f, 'parent', v2);
    const child = await join(mf, f, 'child', headerV2(f, 'child', key));
    const reconnect = await join(mf, f);                           // v1 parent: keeps the lock
    const kept = await owner(mf, f); assert.equal(kept.child_key, key.publicKey);
    await refused(mf, f, header(f, 'child'));
    // a v2 parent with the same key and child key leaves the child connected
    const same = await join(mf, f, 'parent', headerV2(f, 'parent', key));
    assert.equal((await publish(same, 'same-key')).mode, 'forwarded'); await got(child, 'same-key');
    const before = await owner(mf, f);
    const attacks = {
      'foreign parent key': headerV2({ ...f, publicKey: foreign.publicKey, privateKey: foreign.privateKey }, 'parent', mine),
      'owner key, foreign signature': headerV2({ ...f, privateKey: foreign.privateKey }, 'parent', mine),
      'owner signature, other capability': headerV2({ ...f, capability_hash: sha('other') }, 'parent', mine),
      'v1 with foreign parent key': header({ ...f, publicKey: foreign.publicKey, privateKey: foreign.privateKey }),
      'v1 with other capability': header({ ...f, capability_hash: sha('other') }),
      'replayed v2 proof': v2,
    };
    for (const [name, authorization] of Object.entries(attacks)) {
      await refused(mf, f, authorization, 401, '60', 'parent');
      assert.deepEqual(await owner(mf, f), before, name);
      assert.equal((await publish(same, `after:${slug(name)}`)).mode, 'forwarded'); await got(child, `after:${slug(name)}`);
    }
    await refused(mf, f, headerV2(f, 'child', mine));
    parent.close(); reconnect.close();
  } finally { await mf.dispose(); }
});

// ---- 5. retirement of a locked room ----
test('a retired locked room answers 410 to everyone, including a correctly proven child, and keeps only the marker', async () => {
  const mf = runtime();
  try {
    const f = family(), key = childIdentity();
    const parent = await join(mf, f, 'parent', headerV2(f, 'parent', key));
    const child = await join(mf, f, 'child', headerV2(f, 'child', key));
    assert.equal((await publish(parent, 'ctl', wire('ctl'), 'control')).mode, 'buffered');
    assert.equal(await count(mf, f, 'child_inbox'), 1);
    parent.send({ type: 'retire' }); await parent.next(m => m.type === 'retired');
    assert.equal(await count(mf, f, 'child_inbox'), 0);
    await refused(mf, f, headerV2(f, 'child', key), 410, RETIRED);
    await refused(mf, f, headerV2(f, 'child', childIdentity()), 410, RETIRED);
    await refused(mf, f, header(f, 'child'), 410, RETIRED);
    await refused(mf, f, header(f), 410, RETIRED, 'parent');
    await refused(mf, f, headerV2(f, 'parent', key), 410, RETIRED, 'parent');
    assert.equal((await mf.dispatchFetch(`https://relay.example/v1/rooms/${f.room}`, { method: 'DELETE', headers: { Authorization: header(f) } })).status, 204);
    assert.deepEqual((await sql(mf, f.room, 'SELECT key FROM metadata')).map(r => r.key), ['retired']);
  } finally { await mf.dispose(); }
});

// ---- 7. isolation between rooms of one parent ----
test('a child proven for room A is refused in room B of the same parent, and its nonce use in A does not touch B', async () => {
  const mf = runtime();
  try {
    const identity = childIdentity(); // used as the parent's signing key
    const A = family(randomUUID(), identity), B = family(randomUUID(), identity), U = family(randomUUID(), identity);
    const ka = childIdentity(), kb = childIdentity();
    assert.notEqual(A.room, B.room);
    const pa = await join(mf, A, 'parent', headerV2(A, 'parent', ka));
    const pb = await join(mf, B, 'parent', headerV2(B, 'parent', kb));
    const pu = await join(mf, U);                                            // unlocked sibling room
    const boundB = JSON.stringify(await owner(mf, B));
    const nonce = randomUUID();
    const inA = await join(mf, A, 'child', headerV2(A, 'child', ka, { nonce }));
    assert.equal(JSON.stringify(await owner(mf, B)), boundB);                // A's nonce use left B untouched
    // ka's valid proof for A, replayed at B, retargeted to B, or valid for B's transcript but signed by ka
    const asB = JSON.parse(Buffer.from(headerV2(A, 'child', ka).slice(8), 'base64url'));
    await refused(mf, B, 'IGGuard ' + Buffer.from(JSON.stringify({ ...asB, room: B.room, scope: B.scope })).toString('base64url'));
    await refused(mf, B, headerV2(A, 'child', ka));                          // room field says A, URL says B
    await refused(mf, B, headerV2({ ...B, capability: A.capability }, 'child', ka));  // B's transcript, A's key
    await refused(mf, B, headerV2(B, 'child', ka));
    await refused(mf, U, headerV2(U, 'child', ka), 409, '15');               // unlocked sibling: wait, evict no one
    assert.equal(JSON.stringify(await owner(mf, B)), boundB);
    // the same nonce value is independent per room
    const inB = await join(mf, B, 'child', headerV2(B, 'child', kb, { nonce }));
    await refused(mf, A, headerV2(A, 'child', ka, { nonce }));               // replay in A still refused
    assert.equal((await publish(inA, 'only-a')).mode, 'forwarded'); await got(pa, 'only-a');
    assert.equal((await publish(inB, 'only-b')).mode, 'forwarded'); await got(pb, 'only-b');
    assert.equal(pb.log.some(m => m.type === 'message' && m.wire === wire('only-a')), false);
    assert.equal(pu.log.some(m => m.type === 'message'), false);
  } finally { await mf.dispose(); }
});

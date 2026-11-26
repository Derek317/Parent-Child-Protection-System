import { parseAuthorization, verifyParentAuthorization, verifyChildAuthorization, capabilityHash, sameHash, digest } from './protocol.js';
import { DurableObject } from 'cloudflare:workers';

const TTL = 7 * 24 * 60 * 60 * 1000;
const REGISTRATION_TTL = 90 * 24 * 60 * 60 * 1000;
const MAX_QUEUE = 512;
const WINDOW = 8;
// Per-room buffered ordinary-data writes per UTC day. A real family buffers a few hundred frames a day at most.
// Only `data` is budgeted: the app keeps a refused data frame on the phone and carries on, whereas any other
// class answered with a long retry would make the app close the socket and back off for hours.
const DAY = 86_400_000;
const DAILY_DATA_BUDGET = 2000;
// New packet ids per sending role (parent, child) per UTC day, forwarded or buffered: each leaves a packet_lifetimes row for
// TTL + REGISTRATION_TTL. Per role, so one phone can never spend the other's. A phone's data is refused once its new ids that
// day reach 10 000; its alerts, receipts and controls are refused only at 30 000. A real family sends a few hundred a day.
const DAILY_NEW_IDS = 30_000;
const DAILY_NEW_DATA_IDS = 10_000;
const ranks = { control: '0', receipt: '1', urgent: '2', data: '3' };
const encoder = new TextEncoder();
class AccessDenied extends Error {}
const table = role => role === 'parent' ? 'parent_inbox' : 'child_inbox';
const peerRole = role => role === 'parent' ? 'child' : 'parent';
const prefix = role => role === 'parent' ? 'p' : 'c';
const json = (status, code, delay = 60_000) => Response.json({ error: { code, retry_after_ms: delay } }, {
  status, headers: { 'Cache-Control': 'no-store', 'Retry-After': String(Math.ceil(delay / 1000)) },
});
const send = (socket, message) => socket.send(JSON.stringify(message));

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Body must stay exactly this: scripts/relay-config.py --probe (CI) compares the whole object. Features are in `ready`.
    if (request.method === 'GET' && url.pathname === '/health' && !url.search) {
      return Response.json({ service: 'ig-guard-relay', protocol: 1 }, { headers: { 'Cache-Control': 'no-store' } });
    }
    const match = /^\/v1\/rooms\/([a-f0-9]{64})$/.exec(url.pathname);
    if (!match || url.search) return json(404, 'not_found');
    if (request.method !== 'DELETE' && (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket')) return json(426, 'websocket_required');
    // Verified here, before any Durable Object exists for the room: everything that needs no room state. A parent proof binds its
    // key to the room id; a v2 child proof is signed by its child key. A v1 child proof carries only a capability, which only the
    // room can check: for it this checks the shape and the room id, so it can still instantiate a room (rate limited at the edge).
    try {
      const auth = parseAuthorization(request.headers.get('Authorization'));
      if (auth.room !== match[1] || (request.method === 'DELETE' && auth.role !== 'parent')) return json(401, 'unauthorized');
      if (auth.role === 'parent') await verifyParentAuthorization(auth, match[1]);
      else if (auth.v === 2) await verifyChildAuthorization(auth, match[1]);
      else if (typeof auth.capability !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(auth.capability)) return json(401, 'unauthorized');
    } catch (error) { return json(401, error?.message === 'stale_time' ? 'stale_time' : 'unauthorized'); }
    try {
      return await env.FAMILIES.get(env.FAMILIES.idFromName(match[1])).fetch(request);
    } catch { return json(503, 'relay_unavailable'); }
  },
};

/** Ciphertext mailboxes only. Parent authority remains the phone's signing key. */
export class FamilyRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.sql = ctx.storage.sql;
    this.schemaReady = false;
    this.registering = false;
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  // Storage is created only after a parent proof is verified, so unauthenticated requests to
  // arbitrary room ids write nothing. Reading sqlite_master does not create anything.
  hasSchema() {
    if (this.schemaReady) return true;
    const present = this.sql.exec("SELECT 1 FROM sqlite_master WHERE type='table' AND name='metadata'").toArray().length > 0;
    // Never cache while the registration transaction runs: it may still roll back.
    if (present && !this.registering) {
      // Rooms registered before lifetimes existed have storage but not this table; a room with no storage still gets nothing.
      this.sql.exec('CREATE TABLE IF NOT EXISTS packet_lifetimes (id TEXT PRIMARY KEY, digest TEXT NOT NULL, kind TEXT NOT NULL, expires INTEGER NOT NULL) WITHOUT ROWID');
      this.sql.exec('CREATE INDEX IF NOT EXISTS packet_lifetimes_expires ON packet_lifetimes(expires)');
      this.schemaReady = true;
    }
    return present;
  }

  ensureSchema() {
    if (this.hasSchema()) return;
    this.sql.exec('CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID');
    this.sql.exec('CREATE TABLE IF NOT EXISTS packet_lifetimes (id TEXT PRIMARY KEY, digest TEXT NOT NULL, kind TEXT NOT NULL, expires INTEGER NOT NULL) WITHOUT ROWID');
    this.sql.exec('CREATE INDEX IF NOT EXISTS packet_lifetimes_expires ON packet_lifetimes(expires)');
    for (const name of ['parent_inbox', 'child_inbox']) {
      // Primary-key order places controls before context without another write-amplifying index.
      this.sql.exec(`CREATE TABLE IF NOT EXISTS ${name} (id TEXT PRIMARY KEY, wire TEXT NOT NULL, digest TEXT NOT NULL, kind TEXT NOT NULL, expires INTEGER NOT NULL) WITHOUT ROWID`);
    }
  }

  metadata() {
    if (!this.hasSchema()) return null;
    const row = this.sql.exec("SELECT value FROM metadata WHERE key='owner'").toArray()[0];
    return row ? JSON.parse(row.value) : null;
  }

  // A deleted or expired room is never usable again: this content-free marker is the only thing kept, and it has no expiry.
  retired() {
    return this.hasSchema() && this.sql.exec("SELECT 1 FROM metadata WHERE key='retired'").toArray().length > 0;
  }

  retire(socket) {
    this.ctx.storage.transactionSync(() => {
      for (const inbox of ['parent_inbox', 'child_inbox', 'packet_lifetimes']) this.sql.exec(`DELETE FROM ${inbox}`);
      this.sql.exec('DELETE FROM metadata');
      this.sql.exec("INSERT INTO metadata(key,value) VALUES('retired','1')");
    });
    if (socket?.readyState === 1) send(socket, { type: 'retired' });
    for (const peer of this.ctx.getWebSockets()) {
      try { peer.close(1000, 'Room retired'); } catch { /* Already closed. */ }
    }
  }

  cleanupRegistration() {
    const now = Date.now(), owner = this.metadata();
    if (owner && owner.last_active === undefined) {
      // Existing registrations get a full idle period when this schema is first adopted.
      this.sql.exec("UPDATE metadata SET value=? WHERE key='owner'", JSON.stringify({ ...owner, last_active: now }));
    } else if (owner && owner.last_active + REGISTRATION_TTL <= now) this.retire();
  }

  active(socket) {
    this.cleanupRegistration();
    const owner = this.metadata(), state = socket.deserializeAttachment();
    return Boolean(owner && state && owner.epoch === state.epoch && socket.readyState === 1);
  }

  touch() {
    const owner = this.metadata();
    if (owner && Date.now() - owner.last_active >= 60_000) this.sql.exec("UPDATE metadata SET value=? WHERE key='owner'", JSON.stringify({ ...owner, last_active: Date.now() }));
  }

  async fetch(request) {
    const room = new URL(request.url).pathname.split('/').at(-1);
    let auth;
    try {
      auth = parseAuthorization(request.headers.get('Authorization'));
      if (auth.room !== room) return json(401, 'unauthorized');
      if (request.method === 'DELETE' && auth.role !== 'parent') return json(401, 'unauthorized');
      if(auth.role === 'parent')await verifyParentAuthorization(auth, room);
      // Before any room state is read: a child proof that does not verify touches nothing.
      else if (auth.v === 2) await verifyChildAuthorization(auth, room);
    } catch (error) { return json(401, error?.message === 'stale_time' ? 'stale_time' : 'unauthorized'); }
    try {
      this.cleanupRegistration();
      if (this.retired()) {
        // A valid signing proof is required even for an idempotent purge acknowledgment.
        if (request.method === 'DELETE') return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
        return json(410, 'room_retired', REGISTRATION_TTL);
      }
      if (auth.role === 'parent') {
        // No await between replay check and commit: concurrent upgrades cannot spend a nonce twice.
        this.registering = true;
        try {
        this.ctx.storage.transactionSync(() => {
          this.ensureSchema();
          const owner = this.metadata();
          if (owner && (owner.public_key !== auth.public_key || owner.scope !== auth.scope || !sameHash(owner.capability_hash, auth.capability_hash))) throw new AccessDenied();
          const nonces = (owner?.nonces ?? []).filter(n => n[1] >= Date.now());
          if (nonces.some(n => n[0] === auth.nonce) || nonces.length >= 128) throw new AccessDenied();
          nonces.push([auth.nonce, auth.ts_ms + 120_000]);
          // A v1 parent keeps the child lock; only a v2 parent proof sets or replaces child_key.
          const childKey = auth.v === 2 ? auth.child_key : owner?.child_key;
          this.sql.exec("INSERT OR REPLACE INTO metadata(key,value) VALUES('owner',?)", JSON.stringify({
            public_key: auth.public_key, scope: auth.scope, capability_hash: auth.capability_hash, nonces,
            last_active: Date.now(), epoch: owner ? owner.epoch : crypto.randomUUID(),
            ...(childKey ? { child_key: childKey } : {}), ...(owner?.child_nonces ? { child_nonces: owner.child_nonces } : {}),
          }));
        });
        this.schemaReady = true; // only after the transaction committed
        } finally { this.registering = false; }
        if (auth.v === 2) {
          for (const child of this.ctx.getWebSockets('child')) {
            if (child.deserializeAttachment()?.child_key !== auth.child_key) child.close(1000, 'Child key replaced');
          }
        }
      } else {
        const owner = this.metadata();
        if (!owner) return json(409, 'parent_not_registered', 15_000);
        // A locked room answers anything but its own child key here, before the code below can replace the proven socket.
        if (owner.child_key && (auth.v !== 2 || auth.child_key !== owner.child_key)) return json(401, 'unauthorized');
        if (owner.scope !== auth.scope || typeof auth.capability !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(auth.capability) ||
            !sameHash(owner.capability_hash, await capabilityHash(auth.capability))) return json(401, 'unauthorized');
        // Hashing yields; a concurrent parent purge, lock or key replacement must win over an in-flight child upgrade.
        const current = this.metadata();
        if (current?.epoch !== owner.epoch || this.retired()) return json(410, 'room_retired', REGISTRATION_TTL);
        if (current?.child_key !== owner.child_key) return json(401, 'unauthorized');
        // Not locked yet: a child with a key waits for its parent rather than evicting the socket in use.
        if (auth.v === 2 && !current.child_key) return json(409, 'child_key_not_registered', 15_000);
        if (auth.v === 2) {
          // No await between replay check and commit, as for the parent; only verified proofs reach this.
          this.ctx.storage.transactionSync(() => {
            const latest = this.metadata();
            const childNonces = (latest.child_nonces ?? []).filter(n => n[1] >= Date.now());
            if (childNonces.some(n => n[0] === auth.nonce) || childNonces.length >= 128) throw new AccessDenied();
            childNonces.push([auth.nonce, auth.ts_ms + 120_000]);
            this.sql.exec("UPDATE metadata SET value=? WHERE key='owner'", JSON.stringify({ ...latest, child_nonces: childNonces }));
          });
        }
        this.touch();
      }
    } catch(error) { return error instanceof AccessDenied ? json(401, 'unauthorized') : json(503, 'relay_unavailable'); }

    this.cleanupExpired();
    if (request.method === 'DELETE') {
      this.retire();
      await this.ensureAlarm();
      return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    // The current app supports one parent and one child endpoint in a family.
    for (const old of this.ctx.getWebSockets(auth.role)) old.close(1000, 'Replaced by authenticated reconnect');
    this.ctx.acceptWebSocket(server, [auth.role]);
    server.serializeAttachment({ role: auth.role, epoch: this.metadata().epoch, inFlight: [], rateAt: Date.now(), rateCount: 0,
      child_key: auth.role === 'child' && auth.v === 2 ? auth.child_key : null });
    send(server, { type: 'ready', protocol: 1, role: auth.role, peer_online: Boolean(this.peer(auth.role)), features: ['child_proof'] });
    const peer = this.peer(auth.role);
    if (peer) send(peer, { type: 'peer', online: true });
    this.pump(server);
    await this.ensureAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  peer(role) {
    return this.ctx.getWebSockets(peerRole(role)).find(s => s.readyState === 1);
  }

  async webSocketMessage(socket, raw) {
    const state = socket.deserializeAttachment();
    if (!state || !this.active(socket)) return;
    try {
      const now = Date.now();
      if (now - state.rateAt >= 1000) { state.rateAt = now; state.rateCount = 0; }
      if (typeof raw !== 'string' || encoder.encode(raw).length > 6144) throw new Error('invalid_packet');
      const packet = JSON.parse(raw);
      if (!packet || typeof packet !== 'object' || Array.isArray(packet)) throw new Error('invalid_packet');
      this.touch();
      // Throttle producers, never prevent a receiver from consuming its bounded in-flight window.
      if (packet.type === 'publish' && ++state.rateCount > 60) { send(socket, { type: 'error', id: packet.id, code: 'rate_limited', retry_after_ms: 1000 }); return; }
      socket.serializeAttachment(state);
      if (packet.type === 'publish') await this.publish(socket, packet);
      else if (packet.type === 'consume') this.consume(socket, packet);
      else if (packet.type === 'retire' && Object.keys(packet).length === 1) {
        if (state.role !== 'parent') throw new Error('forbidden');
        this.retire(socket); await this.ensureAlarm();
      }
      else if (packet.type === 'replay' && Object.keys(packet).length === 1) {
        this.pump(socket);
        send(socket, { type: 'replay_complete' });
      } else throw new Error('invalid_packet');
    } catch (error) {
      const code = ['invalid_packet','invalid_consumption','forbidden'].includes(error.message) ? error.message : 'relay_unavailable';
      try { send(socket, { type: 'error', code, retry_after_ms: 60_000 }); } catch { /* Phone retains its source outbox. */ }
    }
  }

  async publish(socket, packet) {
    if (Object.keys(packet).length !== 4 || typeof packet.id !== 'string' || !/^[A-Za-z0-9:_-]{1,200}$/.test(packet.id) ||
        !Object.hasOwn(ranks, packet.kind) || typeof packet.wire !== 'string' || encoder.encode(packet.wire).length >= 4096) throw new Error('invalid_packet');
    let envelope;
    try { envelope = JSON.parse(packet.wire); } catch { throw new Error('invalid_packet'); }
    if (!envelope || Object.keys(envelope).length !== 1 || typeof envelope.e2ee !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(envelope.e2ee) ||
        envelope.e2ee.length % 4 !== 0 || atob(envelope.e2ee).length < 28) throw new Error('invalid_packet');
    const role = socket.deserializeAttachment().role;
    const target = peerRole(role);
    const localHash = await digest(packet.id);
    const key = prefix(target) + ranks[packet.kind] + localHash;
    const bodyHash = await digest(packet.wire);
    if (!this.active(socket)) return;
    const inbox = table(target);
    let existing;
    let full = false;
    let budget = false;
    let forwarded = false;
    let expired = false;
    this.ctx.storage.transactionSync(() => {
      const keys = Object.values(ranks).map(rank => prefix(target) + rank + localHash);
      existing = this.sql.exec(`SELECT id,digest FROM ${inbox} WHERE id IN (?,?,?,?)`, ...keys).toArray()[0];
      const lifetimeId = prefix(target) + localHash;
      let lifetime = this.sql.exec('SELECT digest,kind,expires FROM packet_lifetimes WHERE id=?', lifetimeId).toArray()[0];
      if (lifetime && (lifetime.digest !== bodyHash || lifetime.kind !== packet.kind)) {
        existing = { id: key, digest: lifetime.digest === bodyHash ? '' : lifetime.digest }; return;
      }
      if (lifetime?.expires <= Date.now()) { expired = true; return; }
      if (existing) return;
      const peer = this.peer(role);
      if (packet.kind !== 'control' && peer) {
        if (!this.spendBudget(role, packet.kind, false, !lifetime)) { budget = true; return; }
        if (!lifetime) this.sql.exec('INSERT INTO packet_lifetimes(id,digest,kind,expires) VALUES(?,?,?,?)', lifetimeId, bodyHash, packet.kind, Date.now() + TTL);
        send(peer, { type: 'message', id: key, delivery_id: crypto.randomUUID(), wire: packet.wire, digest: bodyHash, buffered: false });
        forwarded = true;
        return;
      }
      const count = this.sql.exec(`SELECT COUNT(*) AS n FROM ${inbox}`).one().n;
      if (count >= (packet.kind === 'data' ? MAX_QUEUE - 32 : MAX_QUEUE)) { full = true; return; }
      if (!this.spendBudget(role, packet.kind, packet.kind === 'data', !lifetime)) { budget = true; return; }
      if (!lifetime) {
        lifetime = { expires: Date.now() + TTL };
        this.sql.exec('INSERT INTO packet_lifetimes(id,digest,kind,expires) VALUES(?,?,?,?)', lifetimeId, bodyHash, packet.kind, lifetime.expires);
      }
      this.sql.exec(`INSERT INTO ${inbox}(id,wire,digest,kind,expires) VALUES(?,?,?,?,?)`, key, packet.wire, bodyHash, packet.kind, lifetime.expires);
    });
    if (existing && (!sameHash(existing.digest, bodyHash) || existing.id !== key)) {
      send(socket, { type: 'error', id: packet.id, code: 'id_conflict', retry_after_ms: 60_000 }); return;
    }
    if (full) { send(socket, { type: 'error', id: packet.id, code: 'queue_full', retry_after_ms: 30_000 }); return; }
    // The same answer as a full mailbox. The app keeps a refused data record on the phone and sends on; any other class keeps its
    // record and reconnects after 30 s. A reply arriving after the app's 15 s publish timeout reaches its fail() path, which honours the delay.
    if (budget) { send(socket, { type: 'error', id: packet.id, code: 'queue_full', retry_after_ms: 30_000 }); return; }
    if (expired) { send(socket, { type: 'error', id: packet.id, code: 'expired', retry_after_ms: 0 }); return; }
    if (!forwarded) {
      await this.ensureAlarm();
      if (!this.active(socket)) return;
      const peer = this.peer(role); if (peer) this.pump(peer);
    }
    send(socket, { type: 'accepted', id: packet.id, mode: forwarded ? 'forwarded' : 'buffered' });
  }

  // Counts, per UTC day inside the caller's transaction, the room's buffered data inserts and the sender's new packet ids;
  // returns false, spending nothing, once either is spent.
  spendBudget(role, kind, data, fresh) {
    if (!data && !fresh) return true;
    const day = Math.floor(Date.now() / DAY);
    const row = this.sql.exec("SELECT value FROM metadata WHERE key='budget'").toArray()[0];
    let spent = row ? JSON.parse(row.value) : null;
    if (!spent || spent.day !== day) spent = { day, data: 0 };
    // A row without per-role counts (none, or one shared number) starts both roles at zero.
    if (typeof spent.fresh !== 'object' || spent.fresh === null) spent.fresh = {};
    const used = spent.fresh[role] ?? 0;
    if (data && spent.data >= DAILY_DATA_BUDGET) return false;
    if (fresh && used >= (kind === 'data' ? DAILY_NEW_DATA_IDS : DAILY_NEW_IDS)) return false;
    if (data) spent.data++;
    if (fresh) spent.fresh[role] = used + 1;
    this.sql.exec("INSERT OR REPLACE INTO metadata(key,value) VALUES('budget',?)", JSON.stringify(spent));
    return true;
  }

  consume(socket, packet) {
    const state = socket.deserializeAttachment();
    if (Object.keys(packet).length !== 3 || typeof packet.id !== 'string' || !new RegExp(`^${prefix(state.role)}[0-3][a-f0-9]{64}$`).test(packet.id) ||
        typeof packet.digest !== 'string' || !/^[a-f0-9]{64}$/.test(packet.digest)) throw new Error('invalid_consumption');
    const inbox = table(state.role);
    const stored = this.sql.exec(`SELECT digest FROM ${inbox} WHERE id=?`, packet.id).toArray()[0];
    if (stored && !sameHash(stored.digest, packet.digest)) throw new Error('invalid_consumption');
    if (stored) this.sql.exec(`DELETE FROM ${inbox} WHERE id=?`, packet.id);
    state.inFlight = state.inFlight.filter(id => id !== packet.id);
    socket.serializeAttachment(state);
    send(socket, { type: 'consumed', id: packet.id });
    this.pump(socket);
  }

  pump(socket) {
    if (socket.readyState !== 1) return;
    const state = socket.deserializeAttachment();
    const slots = WINDOW - state.inFlight.length;
    if (slots <= 0) return;
    const exclusions = state.inFlight.length ? `AND id NOT IN (${state.inFlight.map(() => '?').join(',')})` : '';
    const rows = this.sql.exec(`SELECT id,wire,digest FROM ${table(state.role)} WHERE expires>? ${exclusions} ORDER BY id LIMIT ?`, Date.now(), ...state.inFlight, slots).toArray();
    for (const row of rows) {
      send(socket, { type: 'message', ...row, delivery_id: crypto.randomUUID(), buffered: true });
      state.inFlight.push(row.id);
    }
    socket.serializeAttachment(state);
  }

  cleanupExpired() {
    if (!this.hasSchema()) return;
    this.cleanupRegistration();
    this.ctx.storage.transactionSync(() => {
      // Backfill existing queues with their own deadline; never renew on first reconnect.
      for (const inbox of ['parent_inbox','child_inbox']) {
        // A packet stored before lifetimes existed keeps the deadline it was given.
        this.sql.exec(`INSERT OR IGNORE INTO packet_lifetimes(id,digest,kind,expires) SELECT substr(id,1,1)||substr(id,3),digest,kind,expires FROM ${inbox}`);
      }
      for (const inbox of ['parent_inbox','child_inbox']) this.sql.exec(`DELETE FROM ${inbox} WHERE expires<=?`, Date.now());
      this.sql.exec('DELETE FROM packet_lifetimes WHERE expires<=?', Date.now() - REGISTRATION_TTL);
    });
  }

  async ensureAlarm() {
    if (!this.hasSchema()) return;
    const current = await this.ctx.storage.getAlarm();
    const nextPacket = this.sql.exec('SELECT MIN(expires) AS expiry FROM (SELECT MIN(expires) AS expires FROM parent_inbox UNION ALL SELECT MIN(expires) FROM child_inbox UNION ALL SELECT MIN(expires)+? FROM packet_lifetimes)', REGISTRATION_TTL).one().expiry;
    const owner = this.metadata();
    const next = Math.min(nextPacket ?? Infinity, owner ? owner.last_active + REGISTRATION_TTL : Infinity);
    if (Number.isFinite(next) && (current === null || current > next || current <= Date.now())) await this.ctx.storage.setAlarm(Math.max(Date.now() + 1000, next));
  }

  async alarm() {
    this.cleanupExpired();
    await this.ensureAlarm();
  }

  webSocketClose(socket, code) {
    const state = socket.deserializeAttachment();
    try { socket.close(code === 1006 ? 1001 : code, 'Disconnected'); } catch { /* Already closed. */ }
    if (state) {
      const peer = this.peer(state.role);
      if (peer && !this.ctx.getWebSockets(state.role).some(s => s !== socket && s.readyState === 1)) send(peer, { type: 'peer', online: false });
    }
  }

  webSocketError(socket) { this.webSocketClose(socket, 1011); }
}

import { parseAuthorization, verifyParentAuthorization, capabilityHash, sameHash, digest } from './protocol.js';
import { DurableObject } from 'cloudflare:workers';

const TTL = 7 * 24 * 60 * 60 * 1000;
const MAX_QUEUE = 512;
const WINDOW = 8;
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
    if (request.method === 'GET' && url.pathname === '/health' && !url.search) {
      return Response.json({ service: 'ig-guard-relay', protocol: 1 }, { headers: { 'Cache-Control': 'no-store' } });
    }
    const match = /^\/v1\/rooms\/([a-f0-9]{64})$/.exec(url.pathname);
    if (!match || url.search) return json(404, 'not_found');
    if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json(426, 'websocket_required');
    try {
      parseAuthorization(request.headers.get('Authorization'));
    } catch { return json(401, 'unauthorized'); }
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
    this.sql.exec('CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID');
    for (const name of ['parent_inbox', 'child_inbox']) {
      // Primary-key order places controls before context without another write-amplifying index.
      this.sql.exec(`CREATE TABLE IF NOT EXISTS ${name} (id TEXT PRIMARY KEY, wire TEXT NOT NULL, digest TEXT NOT NULL, kind TEXT NOT NULL, expires INTEGER NOT NULL) WITHOUT ROWID`);
    }
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  metadata() {
    const row = this.sql.exec("SELECT value FROM metadata WHERE key='owner'").toArray()[0];
    return row ? JSON.parse(row.value) : null;
  }

  async fetch(request) {
    const room = new URL(request.url).pathname.split('/').at(-1);
    let auth;
    try {
      auth = parseAuthorization(request.headers.get('Authorization'));
      if (auth.room !== room) return json(401, 'unauthorized');
      if(auth.role === 'parent')await verifyParentAuthorization(auth, room);
    } catch { return json(401, 'unauthorized'); }
    try {
      if (auth.role === 'parent') {
        // No await between replay check and commit: concurrent upgrades cannot spend a nonce twice.
        this.ctx.storage.transactionSync(() => {
          const owner = this.metadata();
          if (owner && (owner.public_key !== auth.public_key || owner.scope !== auth.scope || !sameHash(owner.capability_hash, auth.capability_hash))) throw new AccessDenied();
          const nonces = (owner?.nonces ?? []).filter(n => n[1] >= Date.now());
          if (nonces.some(n => n[0] === auth.nonce) || nonces.length >= 128) throw new AccessDenied();
          nonces.push([auth.nonce, auth.ts_ms + 120_000]);
          this.sql.exec("INSERT OR REPLACE INTO metadata(key,value) VALUES('owner',?)", JSON.stringify({
            public_key: auth.public_key, scope: auth.scope, capability_hash: auth.capability_hash, nonces,
          }));
        });
      } else {
        const owner = this.metadata();
        if (!owner) return json(409, 'parent_not_registered', 15_000);
        if (owner.scope !== auth.scope || typeof auth.capability !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(auth.capability) ||
            !sameHash(owner.capability_hash, await capabilityHash(auth.capability))) return json(401, 'unauthorized');
      }
    } catch(error) { return error instanceof AccessDenied ? json(401, 'unauthorized') : json(503, 'relay_unavailable'); }

    this.cleanupExpired();
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    // The current app supports one parent and one child endpoint in a family.
    for (const old of this.ctx.getWebSockets(auth.role)) old.close(1000, 'Replaced by authenticated reconnect');
    this.ctx.acceptWebSocket(server, [auth.role]);
    server.serializeAttachment({ role: auth.role, inFlight: [], rateAt: Date.now(), rateCount: 0 });
    send(server, { type: 'ready', protocol: 1, role: auth.role, peer_online: Boolean(this.peer(auth.role)) });
    const peer = this.peer(auth.role);
    if (peer) send(peer, { type: 'peer', online: true });
    this.pump(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  peer(role) {
    return this.ctx.getWebSockets(peerRole(role)).find(s => s.readyState === 1);
  }

  async webSocketMessage(socket, raw) {
    const state = socket.deserializeAttachment();
    if (!state || socket.readyState !== 1) return;
    try {
      const now = Date.now();
      if (now - state.rateAt >= 1000) { state.rateAt = now; state.rateCount = 0; }
      if (typeof raw !== 'string' || encoder.encode(raw).length > 6144) throw new Error('invalid_packet');
      const packet = JSON.parse(raw);
      // Throttle producers, never prevent a receiver from consuming its bounded in-flight window.
      if (packet.type === 'publish' && ++state.rateCount > 60) { send(socket, { type: 'error', id: packet.id, code: 'rate_limited', retry_after_ms: 1000 }); return; }
      socket.serializeAttachment(state);
      if (packet.type === 'publish') await this.publish(socket, packet);
      else if (packet.type === 'consume') this.consume(socket, packet);
      else if (packet.type === 'replay' && Object.keys(packet).length === 1) {
        this.pump(socket);
        send(socket, { type: 'replay_complete' });
      } else throw new Error('invalid_packet');
    } catch (error) {
      const code = ['invalid_packet','invalid_consumption'].includes(error.message) ? error.message : 'relay_unavailable';
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
    const inbox = table(target);
    let existing;
    let full = false;
    let forwarded = false;
    this.ctx.storage.transactionSync(() => {
      const keys = Object.values(ranks).map(rank => prefix(target) + rank + localHash);
      existing = this.sql.exec(`SELECT id,digest FROM ${inbox} WHERE id IN (?,?,?,?)`, ...keys).toArray()[0];
      if (existing) return;
      const peer = this.peer(role);
      if (packet.kind !== 'control' && peer) {
        send(peer, { type: 'message', id: key, delivery_id: crypto.randomUUID(), wire: packet.wire, digest: bodyHash, buffered: false });
        forwarded = true;
        return;
      }
      const count = this.sql.exec(`SELECT COUNT(*) AS n FROM ${inbox}`).one().n;
      if (count >= (packet.kind === 'data' ? MAX_QUEUE - 32 : MAX_QUEUE)) { full = true; return; }
      this.sql.exec(`INSERT INTO ${inbox}(id,wire,digest,kind,expires) VALUES(?,?,?,?,?)`, key, packet.wire, bodyHash, packet.kind, Date.now() + TTL);
    });
    if (existing && (!sameHash(existing.digest, bodyHash) || existing.id !== key)) {
      send(socket, { type: 'error', id: packet.id, code: 'id_conflict', retry_after_ms: 60_000 }); return;
    }
    if (full) { send(socket, { type: 'error', id: packet.id, code: 'queue_full', retry_after_ms: 30_000 }); return; }
    if (!forwarded) {
      await this.ensureAlarm();
      const peer = this.peer(role); if (peer) this.pump(peer);
    }
    send(socket, { type: 'accepted', id: packet.id, mode: forwarded ? 'forwarded' : 'buffered' });
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
    for (const inbox of ['parent_inbox','child_inbox']) this.sql.exec(`DELETE FROM ${inbox} WHERE expires<=?`, Date.now());
  }

  async ensureAlarm() {
    if (await this.ctx.storage.getAlarm() !== null) return;
    const next = this.sql.exec('SELECT MIN(expires) AS expiry FROM (SELECT expires FROM parent_inbox UNION ALL SELECT expires FROM child_inbox)').one().expiry;
    if (next !== null) await this.ctx.storage.setAlarm(Math.max(Date.now() + 1000, next));
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

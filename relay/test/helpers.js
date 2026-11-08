import { Miniflare } from 'miniflare';
import { readFile } from 'node:fs/promises';
import { createHash, createHmac, createPrivateKey, sign, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const vector = JSON.parse(await readFile(new URL('./protocol-vector.json', import.meta.url)));
export const sha = value => createHash('sha256').update(value).digest('hex');
export const wire = value => JSON.stringify({ e2ee: Buffer.from('0'.repeat(28) + value).toString('base64') });

export function family(token = vector.token, identity = vector) {
  const scope = 'ig_hk_' + sha('ig_guard_hk_v4:' + token).slice(0, 28);
  const room = sha('igguard-relay-room-v1\n' + identity.publicKey + '\n' + scope);
  const capability = createHmac('sha256', token).update('igguard-relay-child-v1').digest('base64url');
  return { token, scope, room, capability, capability_hash: sha(capability), publicKey: identity.publicKey, privateKey: identity.privateKey };
}

export function header(f, role = 'parent', nonce = randomUUID()) {
  let auth;
  if (role === 'child') auth = { v: 1, role, room: f.room, scope: f.scope, capability: f.capability };
  else {
    auth = { v: 1, role, room: f.room, scope: f.scope, public_key: f.publicKey,
      capability_hash: f.capability_hash, ts_ms: Date.now(), nonce };
    const text = ['igguard-relay-auth-v1','parent',f.room,f.scope,auth.ts_ms,nonce,f.capability_hash].join('\n');
    const key = createPrivateKey({ key: Buffer.from(f.privateKey, 'base64url'), type: 'pkcs8', format: 'der' });
    auth.signature = sign('sha256', Buffer.from(text), key).toString('base64url');
  }
  return 'IGGuard ' + Buffer.from(JSON.stringify(auth)).toString('base64url');
}

export function runtime(persist) {
  return new Miniflare({
    name: 'ig-guard-relay', modules: true, scriptPath: fileURLToPath(new URL('../src/worker.js', import.meta.url)),
    modulesRules: [{ type: 'ESModule', include: ['**/*.js'] }],
    compatibilityDate: '2026-07-30',
    durableObjects: { FAMILIES: { className: 'FamilyRoom', useSQLite: true } },
    durableObjectsPersist: persist ?? false,
    unsafeInspectDurableObjects: true,
    host: '127.0.0.1', port: 0,
    log: undefined,
  });
}

export async function connection(mf, f, role = 'parent', authorization = header(f, role)) {
  const response = await mf.dispatchFetch(`https://relay.example/v1/rooms/${f.room}`, {
    headers: { Upgrade: 'websocket', Authorization: authorization },
  });
  if (response.status !== 101) return { response };
  const socket = response.webSocket;
  const messages = [];
  const waiters = [];
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const index = waiters.findIndex(w => w.predicate(message));
    if (index < 0) messages.push(message);
    else { const [w] = waiters.splice(index, 1); clearTimeout(w.timer); w.resolve(message); }
  });
  socket.accept();
  return { response, socket, messages,
    send: value => socket.send(JSON.stringify(value)),
    next: (predicate, timeout = 5000) => {
      const index = messages.findIndex(predicate);
      if (index >= 0) return Promise.resolve(messages.splice(index, 1)[0]);
      return new Promise((resolve, reject) => {
        const waiter = { predicate, resolve };
        waiter.timer = setTimeout(() => { waiters.splice(waiters.indexOf(waiter), 1); reject(new Error(`Timed out: ${role} ${f.room.slice(0, 8)}; queued types: ${messages.map(m => m.type).join(',')}`)); }, timeout);
        waiters.push(waiter);
      });
    },
    close: () => socket.close(1000, 'test complete'),
  };
}

export const publish = (client, id, body = wire(id), kind = 'data') => {
  client.send({ type: 'publish', id, wire: body, kind });
  return client.next(m => (m.type === 'accepted' && m.id === id) || m.type === 'error');
};
export const consume = (client, packet) => { if (packet.buffered) client.send({ type: 'consume', id: packet.id, digest: packet.digest }); };

export async function sql(mf, room, statement, ...parameters) {
  const bindings = await mf.getBindings();
  const id = bindings.FAMILIES.idFromName(room).toString();
  const store = await mf.unsafeGetDurableObjectStorage('ig-guard-relay', 'FamilyRoom', { id });
  return store.exec(statement, ...parameters);
}

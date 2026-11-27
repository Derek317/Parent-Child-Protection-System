import { Miniflare } from 'miniflare';
import { fileURLToPath } from 'node:url';

export * from './proofs.js';
import { header, wire } from './proofs.js';

export function runtime(persist, options = {}) {
  return new Miniflare({
    name: 'ig-guard-relay', modules: true, scriptPath: fileURLToPath(new URL('../src/worker.js', import.meta.url)),
    modulesRules: [{ type: 'ESModule', include: ['**/*.js'] }],
    compatibilityDate: '2026-07-30',
    durableObjects: { FAMILIES: { className: 'FamilyRoom', useSQLite: true } },
    durableObjectsPersist: persist ?? false,
    unsafeInspectDurableObjects: true,
    host: '127.0.0.1', port: 0,
    log: undefined,
    ...options,
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

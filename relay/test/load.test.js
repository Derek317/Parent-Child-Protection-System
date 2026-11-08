import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createCipheriv, createDecipheriv, randomBytes, randomUUID, generateKeyPairSync, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { family, runtime, connection, publish, consume, sql } from './helpers.js';

const encrypt = (token, value) => {
  const key = createHash('sha256').update('ig_guard_e2ee_aes256_v4.2:' + token).digest();
  const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final(), cipher.getAuthTag()]);
  return JSON.stringify({ e2ee: Buffer.concat([iv, body]).toString('base64') });
};
const decrypt = (token, wire) => {
  const data = Buffer.from(JSON.parse(wire).e2ee, 'base64');
  const key = createHash('sha256').update('ig_guard_e2ee_aes256_v4.2:' + token).digest();
  const decipher = createDecipheriv('aes-256-gcm', key, data.subarray(0, 12));
  decipher.setAuthTag(data.subarray(-16));
  return JSON.parse(Buffer.concat([decipher.update(data.subarray(12, -16)), decipher.final()]));
};

test('100 independent two-phone families exchange encrypted records and signed receipts with offline recovery', { timeout: 240_000 }, async () => {
  const mf = runtime();
  const perFamily = Number(process.env.LOAD_MESSAGES ?? 25);
  const familyCount = Number(process.env.LOAD_FAMILIES ?? 100);
  assert.ok(Number.isInteger(perFamily) && perFamily > 0 && perFamily <= 500);
  const started = performance.now();
  let publishes = 0, buffered = 0, throttleResponses = 0, bytes = 0;
  try {
    await Promise.all(Array.from({ length: familyCount }, async (_, index) => {
      const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
      const identity = { publicKey: keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64url'),
        privateKey: keys.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64url') };
      const f = family(randomUUID(), identity);
      const privateKey = createPrivateKey({ key: Buffer.from(f.privateKey, 'base64url'), type: 'pkcs8', format: 'der' });
      const publicKey = createPublicKey({ key: Buffer.from(f.publicKey, 'base64url'), type: 'spki', format: 'der' });
      let parent = await connection(mf, f); const child = await connection(mf, f, 'child');
      await parent.next(m => m.type === 'ready'); await child.next(m => m.type === 'ready');
      const pending = new Set(), archive = new Map(), retained = [];
      const retryPublish = async (client, id, body, kind) => {
        for (;;) {
          const result = await publish(client, id, body, kind); publishes++; bytes += Buffer.byteLength(body);
          if (result.code !== 'rate_limited') { if (result.mode === 'buffered') buffered++; return result; }
          throttleResponses++; await delay(result.retry_after_ms);
        }
      };
      const records = Array.from({ length: perFamily }, (_, n) => {
        const event = { type: 'captured_message', event_id: randomUUID(), direction: n % 2 ? 'outgoing' : 'incoming',
          text: `Family ${index}, record ${n}. ` + '繁體中文 English context '.repeat(20), ts_ms: started + n, capture_order: n };
        pending.add(event.event_id);
        return { id: event.event_id + ':0', body: encrypt(f.token, event) };
      });
      if (index % 5 === 0) {
        parent.close();
        // Begin the offline interval only after the relay has observed the disconnect.
        await child.next(m => m.type === 'peer' && !m.online);
        for (const record of records) {
          const result = await retryPublish(child, record.id, record.body, 'data');
          if (result.code === 'queue_full') retained.push(record); else assert.equal(result.type, 'accepted');
        }
        parent = await connection(mf, f); await parent.next(m => m.type === 'ready');
      } else retained.push(...records);

      const receiveOnParent = (async () => {
        while (archive.size < perFamily) {
          const packet = await parent.next(m => m.type === 'message', 60_000);
          const event = decrypt(f.token, packet.wire); archive.set(event.event_id, event); consume(parent, packet);
          const payload = JSON.stringify({ type: 'alert_receipt', outcome: 'stored', scope: f.scope, event_id: event.event_id, command_id: randomUUID(), ts_ms: Date.now() });
          const body = encrypt(f.token, { payload, signature: sign('sha256', Buffer.from(payload), privateKey).toString('base64url') });
          assert.equal((await retryPublish(parent, 'receipt:' + event.event_id, body, 'receipt')).type, 'accepted');
        }
      })();
      const receiveOnChild = (async () => {
        while (pending.size) {
          const packet = await child.next(m => m.type === 'message', 60_000);
          const envelope = decrypt(f.token, packet.wire);
          assert.equal(verify('sha256', Buffer.from(envelope.payload), publicKey, Buffer.from(envelope.signature, 'base64url')), true);
          const receipt = JSON.parse(envelope.payload); assert.equal(receipt.scope, f.scope);
          assert.equal(pending.delete(receipt.event_id), true); consume(child, packet);
        }
      })();
      for (const record of retained) assert.equal((await retryPublish(child, record.id, record.body, 'data')).type, 'accepted');
      await Promise.all([receiveOnParent, receiveOnChild]);
      assert.equal(archive.size, perFamily); assert.equal(pending.size, 0);
      assert.deepEqual([...archive.values()].sort((a, b) => a.ts_ms - b.ts_ms).map(e => e.capture_order), Array.from({ length: perFamily }, (_, n) => n));
      assert.equal((await sql(mf, f.room, 'SELECT COUNT(*) AS count FROM parent_inbox'))[0].count, 0);
      parent.close(); child.close();
      console.log(`Completed family ${index}: ${archive.size} records, ${pending.size} pending`);
    }));
    console.log(JSON.stringify({ families: familyCount, phoneConnections: familyCount * 2, records: perFamily * familyCount, publishes, buffered, throttleResponses, encryptedBytesPublished: bytes, elapsedMs: Math.round(performance.now() - started) }));
  } finally { await mf.dispose(); }
});

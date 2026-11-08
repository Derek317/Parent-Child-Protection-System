import WebSocket from 'ws';
import { readFile } from 'node:fs/promises';
import { createHash, createCipheriv, createDecipheriv, generateKeyPairSync, randomBytes, randomUUID, sign, verify } from 'node:crypto';
import { capabilityHash, childCapability, roomId } from '../src/protocol.js';

const saved=JSON.parse(await readFile(new URL('../endpoint.json',import.meta.url)));
const endpoint=new URL(process.env.RELAY_BASE_URL || saved.base_url);
if(endpoint.protocol!=='https:' || endpoint.username || endpoint.password || endpoint.search || endpoint.hash || !['','/'].includes(endpoint.pathname))throw new Error('Invalid relay configuration');
const token=randomUUID(), scope='ig_hk_'+createHash('sha256').update('ig_guard_hk_v4:'+token).digest('hex').slice(0,28);
const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const publicKey=keys.publicKey.export({type:'spki',format:'der'}).toString('base64url');
const room=await roomId(publicKey,scope),capability=await childCapability(token),capHash=await capabilityHash(capability);
const sockets=[];
const key=createHash('sha256').update('ig_guard_e2ee_aes256_v4.2:'+token).digest();
const encrypt=value=>{
  const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
  return JSON.stringify({e2ee:Buffer.concat([iv,cipher.update(JSON.stringify(value)),cipher.final(),cipher.getAuthTag()]).toString('base64')});
};
const decrypt=wire=>{
  const data=Buffer.from(JSON.parse(wire).e2ee,'base64'),cipher=createDecipheriv('aes-256-gcm',key,data.subarray(0,12));
  cipher.setAuthTag(data.subarray(-16));return JSON.parse(Buffer.concat([cipher.update(data.subarray(12,-16)),cipher.final()]));
};
function authorization(role,bad=false){
  let auth={v:1,role,room,scope};
  if(role==='child')auth.capability=bad?'A'.repeat(43):capability;
  else{
    Object.assign(auth,{public_key:publicKey,capability_hash:capHash,ts_ms:Date.now(),nonce:randomUUID()});
    const text=['igguard-relay-auth-v1','parent',room,scope,auth.ts_ms,auth.nonce,capHash].join('\n');
    auth.signature=sign('sha256',Buffer.from(text),keys.privateKey).toString('base64url');
  }
  return 'IGGuard '+Buffer.from(JSON.stringify(auth)).toString('base64url');
}
async function connect(role,bad=false){
  const url='wss://'+endpoint.host+'/v1/rooms/'+room;
  const socket=new WebSocket(url,{headers:{Authorization:authorization(role,bad)},handshakeTimeout:15_000});sockets.push(socket);
  const queue=[],waiters=[];
  let failure;
  socket.on('message',data=>{
    let value;try{value=JSON.parse(data)}catch{return}
    const index=waiters.findIndex(w=>w.predicate(value));
    if(index<0)queue.push(value);else{const [w]=waiters.splice(index,1);clearTimeout(w.timer);w.resolve(value)}
  });
  const failed=status=>{
    failure=new Error((Number.isInteger(status)?'[RELAY_SOCKET_HTTP_'+status+'] ':'')+'Relay connection or authentication failed');
    for(const w of waiters.splice(0)){clearTimeout(w.timer);w.reject(failure)}
  };
  socket.on('error',()=>failed());socket.on('close',()=>{if(waiters.length)failed()});
  socket.on('unexpected-response',(_,response)=>{failed(response.statusCode);response.resume();socket.terminate()});
  const next=predicate=>{
    const index=queue.findIndex(predicate);if(index>=0)return Promise.resolve(queue.splice(index,1)[0]);
    if(failure)return Promise.reject(failure);
    return new Promise((resolve,reject)=>{
      const w={predicate,resolve,reject};w.timer=setTimeout(()=>{waiters.splice(waiters.indexOf(w),1);reject(new Error('Live relay check timed out'))},20_000);waiters.push(w);
    });
  };
  await next(v=>v.type==='ready');
  return {socket,next,send:value=>socket.send(JSON.stringify(value))};
}
function requireValue(value){if(!value)throw new Error('Live relay behavior did not match the verified protocol')}
try{
  let parent=await connect('parent');const child=await connect('child');
  let rejected=false;try{await connect('child',true)}catch{rejected=true}requireValue(rejected);
  parent.socket.close();await child.next(v=>v.type==='peer' && !v.online);
  const eventId=randomUUID();const event={type:'alert',event_id:eventId,reason:'test_alert',sender:'Relay self-test',text:'Synthetic encrypted delivery check',ts_ms:Date.now(),receipt_requested:true};
  child.send({type:'publish',id:eventId+':0',kind:'urgent',wire:encrypt(event)});
  const accepted=await child.next(v=>v.type==='accepted' || v.type==='error');requireValue(accepted.mode==='buffered');
  parent=await connect('parent');const received=await parent.next(v=>v.type==='message');
  requireValue(decrypt(received.wire).event_id===eventId && received.buffered);
  parent.send({type:'consume',id:received.id,digest:received.digest});await parent.next(v=>v.type==='consumed');
  const commandId=randomUUID();const payload=JSON.stringify({type:'alert_receipt',outcome:'stored',event_id:eventId,scope,command_id:commandId,ts_ms:Date.now()});
  const envelope={payload,signature:sign('sha256',Buffer.from(payload),keys.privateKey).toString('base64url')};
  const wrapped={type:'alert',event_id:'control:'+commandId,relay_control:true,envelope};
  child.send({type:'replay'});
  parent.send({type:'publish',id:'control:'+commandId+':0',kind:'receipt',wire:encrypt(wrapped)});
  requireValue((await parent.next(v=>v.type==='accepted' || v.type==='error')).type==='accepted');
  const receipt=decrypt((await child.next(v=>v.type==='message')).wire).envelope;
  requireValue(verify('sha256',Buffer.from(receipt.payload),keys.publicKey,Buffer.from(receipt.signature,'base64url')));
  requireValue(JSON.parse(receipt.payload).event_id===eventId);
  console.log('Live relay verified: owner authentication, wrong-capability rejection, offline ciphertext delivery, decryption and signed parent receipt.');
}catch(error){
  if(process.env.GITHUB_ACTIONS==='true'){
    const code=/\[RELAY_SOCKET_HTTP_[1-5][0-9]{2}\]/.exec(error.message)?.[0] || '[RELAY_PROTOCOL_CHECK]';
    console.error('::error title=Relay protocol::'+code+' Authenticated WebSocket or offline receipt verification failed.');
  }
  throw error;
}finally{
  for(const socket of sockets)socket.terminate();
}

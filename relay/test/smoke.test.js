import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { runtime } from './helpers.js';

test('the release smoke command verifies the real TLS WebSocket and offline receipt flow', async () => {
  const directory=await mkdtemp(join(tmpdir(),'ig-relay-tls-'));
  let mf;
  try{
    const cert=join(directory,'cert.pem'),key=join(directory,'key.pem');
    execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',key,'-out',cert,'-days','1','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1'],{stdio:'ignore'});
    mf=runtime(undefined,{https:true,httpsCertPath:cert,httpsKeyPath:key});
    const url=await mf.ready;
    const result=await new Promise((resolve,reject)=>{
      const child=spawn(process.execPath,['--use-env-proxy',fileURLToPath(new URL('../scripts/smoke.js',import.meta.url))],{
        env:{...process.env,RELAY_BASE_URL:url.origin,NODE_EXTRA_CA_CERTS:cert,NO_PROXY:[process.env.NO_PROXY,'127.0.0.1'].filter(Boolean).join(',')},
      });
      let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);
      child.on('error',reject);child.on('exit',code=>resolve({code,output}));
    });
    assert.equal(result.code,0,result.output);
    assert.match(result.output,/signed parent receipt/);
  }finally{await mf?.dispose();await rm(directory,{recursive:true,force:true})}
});

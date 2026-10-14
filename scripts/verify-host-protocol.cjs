#!/usr/bin/env node
/**
 * verify-host-protocol.cjs — independent Node harness for the shell bridge protocol.
 *
 * Verifies the claims made by the post-`c285ad4` remediation for
 * `app/src/main/assets/shell/host.js` without a device or WebView:
 *   1. SHA-256 sender checksum matches Node's crypto (empty, "abc", 1000 B, 300 KiB).
 *   2. Sub-view payloads are sliced to [byteOffset, byteOffset + byteLength].
 *   3. A duplicate initial chunk (seq=0 twice) is rejected and never assembled.
 *   4. A valid multi-chunk import assembles to the exact bytes and opens it.
 *   5. Chunked exports stream seq0 -> seq1 -> saveComplete under native acks.
 *   6. Chunked exports still progress via the 150 ms ack fallback when native never acks.
 *
 * Run:  node scripts/verify-host-protocol.cjs
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

const REPO = path.resolve(__dirname, '..');
const SRC = path.join(REPO, 'app', 'src', 'main', 'assets', 'shell', 'host.js');
const hostSrc = fs.readFileSync(SRC, 'utf8');
const ORIGIN = 'https://appassets.androidplatform.net';
const CHUNK = 256 * 1024;

let failures = 0;
async function check(name, fn) {
  try { await fn(); console.log('PASS: ' + name); }
  catch (e) { failures++; console.log('FAIL: ' + name + ' -> ' + e.message); }
}
const delay = (ms) => new Promise(r => setTimeout(r, ms));

function makeHost() {
  const native = [], iframePosts = [], listeners = [], errors = [];
  const frame = {
    contentWindow: { postMessage: (...a) => iframePosts.push(a) },
    addEventListener: () => {},
    getAttribute: (k) => (k === 'data-src' ? '../pdfcraft/index.html?host=parent' : null),
    setAttribute: () => {},
    contentDocument: null,
  };
  const ctx = vm.createContext({
    console: { log() {}, warn() {}, error: (...a) => errors.push(a.map(String).join(' ')) },
    document: { getElementById: () => frame },
    btoa: s => Buffer.from(s, 'binary').toString('base64'),
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    addEventListener: (t, cb) => listeners.push(cb),
    setTimeout, clearTimeout, requestAnimationFrame: () => {},
  });
  ctx.window = ctx;
  ctx.WebMessageListener = { postMessage: d => native.push(JSON.parse(d)) };
  vm.runInContext(hostSrc, ctx);
  ctx.deliver = (ev) => listeners.forEach(cb => cb(ev));
  // Create a VM-realm ArrayBuffer from a Node Buffer (avoids cross-realm pitfalls).
  ctx.makeBuf = (arr) => { ctx.__tmp = arr; return vm.runInContext('new Uint8Array(__tmp).buffer', ctx); };
  ctx.fromIframe = (data) => ctx.deliver({ origin: ORIGIN, source: frame.contentWindow, data });
  ctx.fromNative = (obj) => ctx.deliver({ origin: '', source: null, data: JSON.stringify(obj) });
  return { ctx, native, iframePosts, errors, frame };
}

async function main() {
  await check('sha256Hex matches Node crypto (empty, "abc", 1000 bytes, 300 KiB)', () => {
    const { ctx } = makeHost();
    for (const len of [0, 3, 1000, 300 * 1024]) {
      const nodeBuf = Buffer.alloc(len);
      for (let i = 0; i < len; i++) nodeBuf[i] = (i * 31 + 7) & 0xff;
      const expected = crypto.createHash('sha256').update(nodeBuf).digest('hex');
      const actual = ctx.window.PdfCraftHost.sha256Hex(ctx.makeBuf([...nodeBuf]));
      assert.equal(actual, expected, `sha256 mismatch at len=${len}`);
    }
  });

  await check('toArrayBuffer slices a 4-byte view over a 6-byte backing buffer', () => {
    const { ctx } = makeHost();
    ctx.__six = [10, 20, 30, 40, 50, 60];
    const out = vm.runInContext(`
      const backing = new Uint8Array(__six);
      const view = new Uint8Array(backing.buffer, 1, 4);
      const ab = PdfCraftHost.toArrayBuffer(view);
      Array.from(new Uint8Array(ab));
    `, ctx);
    assert.deepEqual(Array.from(out), [20, 30, 40, 50], 'view should slice to its exact 4 bytes');
  });

  await check('duplicate seq=0 chunk is rejected and never assembled', () => {
    const { ctx, iframePosts, errors } = makeHost();
    ctx.fromIframe({ type: 'pdfcraft:ready', version: '1' });
    const chunk = Buffer.from([1, 2, 3, 4]).toString('base64');
    const meta = { type: 'host:importChunk', transferId: 'dup1', name: 'a.pdf', totalBytes: 8, totalChunks: 2, data: chunk };
    ctx.fromNative({ ...meta, seq: 0 });
    ctx.fromNative({ ...meta, seq: 0 });
    ctx.fromNative({ ...meta, seq: 1 });
    assert.equal(iframePosts.length, 0, 'no pdfcraft:open should be emitted');
    assert.ok(errors.some(e => /duplicate or out-of-order|expected initial seq/i.test(e)), 'rejection should be logged');
  });

  await check('valid 2-chunk import assembles to the exact bytes', () => {
    const { ctx, iframePosts } = makeHost();
    ctx.fromIframe({ type: 'pdfcraft:ready', version: '1' });
    const c0 = Buffer.from([1, 2, 3]), c1 = Buffer.from([4, 5]);
    const sha = crypto.createHash('sha256').update(Buffer.concat([c0, c1])).digest('hex');
    ctx.fromNative({ type: 'host:importChunk', transferId: 'ok1', name: 'd.pdf', totalBytes: 5, totalChunks: 2, seq: 0, data: c0.toString('base64'), sha256: sha });
    ctx.fromNative({ type: 'host:importChunk', transferId: 'ok1', name: 'd.pdf', totalBytes: 5, totalChunks: 2, seq: 1, data: c1.toString('base64'), sha256: sha });
    assert.equal(iframePosts.length, 1, 'exactly one pdfcraft:open');
    const [msg, origin] = iframePosts[0];
    assert.equal(msg.type, 'pdfcraft:open');
    assert.equal(origin, ORIGIN);
    assert.deepEqual(Array.from(new Uint8Array(msg.bytes)), [1, 2, 3, 4, 5]);
  });

  await check('chunked export sends seq0, seq1, then saveComplete on acks', async () => {
    const { ctx, native } = makeHost();
    const big = Buffer.alloc(CHUNK + 100, 9);
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'big.pdf', saveAs: true, bytes: ctx.makeBuf([...big]) });
    await delay(20);
    assert.equal(native.length, 1, 'chunk 0 sent');
    assert.equal(native[0].type, 'host:saveChunk');
    assert.equal(native[0].seq, 0);
    const id = native[0].transferId;
    assert.equal(native[0].totalChunks, 2);

    ctx.fromNative({ type: 'host:saveChunkAck', transferId: id, seq: 0, success: true });
    await delay(20);
    assert.equal(native[1].type, 'host:saveChunk');
    assert.equal(native[1].seq, 1);

    ctx.fromNative({ type: 'host:saveChunkAck', transferId: id, seq: 1, success: true });
    await delay(20);
    assert.equal(native[2].type, 'host:saveComplete');
    assert.equal(native[2].transferId, id);
    assert.ok(typeof native[2].sha256 === 'string' && native[2].sha256.length === 64, 'saveComplete carries sha256');
  });

  await check('chunked export advances via 150ms ack fallback when native never acks', async () => {
    const { ctx, native } = makeHost();
    const big = Buffer.alloc(CHUNK + 100, 9);
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'big2.pdf', saveAs: true, bytes: ctx.makeBuf([...big]) });
    await delay(400);
    assert.ok(native.map(m => m.type).includes('host:saveComplete'), 'should complete via fallback');
  });

  console.log('');
  console.log(failures === 0 ? 'ALL PROTOCOL CHECKS PASSED.' : failures + ' CHECK(S) FAILED.');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch(e => { console.error('HARNESS ERROR:', e); process.exit(2); });

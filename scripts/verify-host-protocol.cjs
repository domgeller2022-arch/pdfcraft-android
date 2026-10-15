#!/usr/bin/env node
/**
 * verify-host-protocol.cjs — independent Node harness for the shell bridge protocol.
 *
 * Verifies the claims made for `app/src/main/assets/shell/host.js` without a device:
 *   1. SHA-256 sender checksum matches Node's crypto (empty, "abc", 1000 B, 300 KiB).
 *   2. Sub-view payloads are sliced to [byteOffset, byteOffset + byteLength].
 *   3. A duplicate initial chunk (seq=0 twice) is rejected and never assembled.
 *   4. A valid multi-chunk import assembles to the exact bytes and opens it.
 *   5. Chunked export streams seq0 -> seq1 -> saveComplete under explicit native acks.
 *   6. DELAYED ACK: chunk 1 is NOT sent before ACK 0 is received (no timer bypass).
 *   7. MISSING ACK: no ack -> no saveComplete; the transfer is retained and surfaced.
 *   8. BATCH: ten multi-chunk exports all complete; none is silently dropped.
 *   9. OVERFLOW: over the queued-byte bound -> retained + surfaced (not dropped),
 *      and retry re-sends it.
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
  ctx.makeBuf = (arr) => { ctx.__tmp = arr; return vm.runInContext('new Uint8Array(__tmp).buffer', ctx); };
  ctx.fromIframe = (data) => ctx.deliver({ origin: ORIGIN, source: frame.contentWindow, data });
  ctx.fromNative = (obj) => ctx.deliver({ origin: '', source: null, data: JSON.stringify(obj) });
  ctx.types = () => native.map(m => m.type);
  ctx.find = (type) => native.filter(m => m.type === type);
  return { ctx, native, iframePosts, errors, frame };
}

// Synchronously acknowledge every chunk host.js sends, until it stops producing new ones.
function driveToCompletion(ctx) {
  const seen = new Set();
  for (let i = 0; i < 10000; i++) {
    let progressed = false;
    for (const m of ctx.find('host:saveChunk')) {
      const key = m.transferId + ':' + m.seq;
      if (!seen.has(key)) {
        seen.add(key);
        progressed = true;
        ctx.fromNative({ type: 'host:saveChunkAck', transferId: m.transferId, seq: m.seq, success: true });
      }
    }
    if (!progressed) break;
  }
}

async function main() {
  await check('sha256Hex matches Node crypto (empty, "abc", 1000, 300 KiB)', () => {
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
      Array.from(new Uint8Array(PdfCraftHost.toArrayBuffer(view)));
    `, ctx);
    assert.deepEqual(Array.from(out), [20, 30, 40, 50]);
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
    assert.ok(errors.some(e => /duplicate or out-of-order|expected initial seq/i.test(e)), 'rejection logged');
  });

  await check('valid 2-chunk import assembles to the exact bytes', () => {
    const { ctx, iframePosts } = makeHost();
    ctx.fromIframe({ type: 'pdfcraft:ready', version: '1' });
    const c0 = Buffer.from([1, 2, 3]), c1 = Buffer.from([4, 5]);
    const sha = crypto.createHash('sha256').update(Buffer.concat([c0, c1])).digest('hex');
    ctx.fromNative({ type: 'host:importChunk', transferId: 'ok1', name: 'd.pdf', totalBytes: 5, totalChunks: 2, seq: 0, data: c0.toString('base64'), sha256: sha });
    ctx.fromNative({ type: 'host:importChunk', transferId: 'ok1', name: 'd.pdf', totalBytes: 5, totalChunks: 2, seq: 1, data: c1.toString('base64'), sha256: sha });
    assert.equal(iframePosts.length, 1, 'exactly one pdfcraft:open');
    assert.deepEqual(Array.from(new Uint8Array(iframePosts[0][0].bytes)), [1, 2, 3, 4, 5]);
  });

  await check('chunked export sends seq0, seq1, then saveComplete on explicit acks', () => {
    const { ctx } = makeHost();
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'big.pdf', saveAs: true, bytes: ctx.makeBuf([...Buffer.alloc(CHUNK + 100, 9)]) });
    let chunks = ctx.find('host:saveChunk');
    assert.equal(chunks.length, 1, 'only chunk 0 sent initially');
    assert.equal(chunks[0].seq, 0);
    driveToCompletion(ctx);
    chunks = ctx.find('host:saveChunk');
    assert.equal(chunks.length, 2, 'both chunks sent after acks');
    assert.equal(ctx.find('host:saveComplete').length, 1, 'one saveComplete');
  });

  await check('DELAYED ACK: chunk 1 is NOT sent before ACK 0 (no timer bypass)', async () => {
    const { ctx } = makeHost();
    ctx.window.PdfCraftHost.config.ackTimeoutMs = 10000;
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'd.pdf', saveAs: true, bytes: ctx.makeBuf([...Buffer.alloc(CHUNK + 100, 7)]) });
    // Wait far longer than the old 150 ms fallback; nothing may advance without an ack.
    await delay(500);
    assert.equal(ctx.find('host:saveChunk').length, 1, 'chunk 1 must NOT be sent before ACK 0');
    assert.equal(ctx.find('host:saveComplete').length, 0, 'must not complete without acks');
    const id = ctx.find('host:saveChunk')[0].transferId;
    ctx.fromNative({ type: 'host:saveChunkAck', transferId: id, seq: 0, success: true });
    assert.equal(ctx.find('host:saveChunk').length, 2, 'chunk 1 sent after ACK 0');
  });

  await check('MISSING ACK: no ack -> no saveComplete; transfer retained and surfaced', async () => {
    const { ctx } = makeHost();
    ctx.window.PdfCraftHost.config.ackTimeoutMs = 200;
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'stuck.pdf', saveAs: true, bytes: ctx.makeBuf([...Buffer.alloc(CHUNK + 100, 3)]) });
    await delay(500);
    assert.equal(ctx.find('host:saveComplete').length, 0, 'must NOT complete without an ack');
    const failed = ctx.find('host:saveFailed');
    assert.equal(failed.length, 1, 'exactly one host:saveFailed surfaced');
    assert.equal(failed[0].reason, 'ack-timeout');
    assert.equal(ctx.window.PdfCraftHost.getPendingWork().failed, 1, 'retained as failed work');
  });

  await check('BATCH: ten multi-chunk exports all complete; none dropped', () => {
    const { ctx } = makeHost();
    ctx.window.PdfCraftHost.config.ackTimeoutMs = 10000;
    for (let i = 0; i < 10; i++) {
      ctx.fromIframe({ type: 'pdfcraft:save', name: `part-${i}.pdf`, saveAs: true, bytes: ctx.makeBuf([...Buffer.alloc(CHUNK + 100, i)]) });
    }
    driveToCompletion(ctx);
    const completes = ctx.find('host:saveComplete');
    const failed = ctx.find('host:saveFailed');
    assert.equal(completes.length, 10, `expected 10 completions, got ${completes.length}`);
    assert.equal(failed.length, 0, `no export should be dropped/failed; got ${failed.length}`);
    assert.equal(ctx.window.PdfCraftHost.getPendingWork().queued, 0, 'queue drained');
  });

  await check('OVERFLOW: over the queued-byte bound is retained + surfaced, and retry re-sends', () => {
    const { ctx } = makeHost();
    ctx.window.PdfCraftHost.config.ackTimeoutMs = 10000;
    ctx.window.PdfCraftHost.config.maxQueuedExportBytes = 300 * 1024; // fits only one waiting export
    const mk = () => ctx.makeBuf([...Buffer.alloc(CHUNK + 100, 1)]);
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'a.pdf', saveAs: true, bytes: mk() }); // active
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'b.pdf', saveAs: true, bytes: mk() }); // queued
    ctx.fromIframe({ type: 'pdfcraft:save', name: 'c.pdf', saveAs: true, bytes: mk() }); // over bound
    const failed = ctx.find('host:saveFailed');
    assert.equal(failed.length, 1, 'exactly one retained due to overflow');
    assert.equal(failed[0].reason, 'queue-full');
    assert.equal(failed[0].name, 'c.pdf');
    assert.equal(ctx.window.PdfCraftHost.getPendingWork().failed, 1, 'overflow retained, not dropped');
    // Drain the queue so there is room, then retry — the retained payload must be re-sent.
    driveToCompletion(ctx);
    const before = ctx.find('host:saveChunk').length;
    ctx.window.PdfCraftHost.retryFailedExport(failed[0].transferId);
    assert.ok(ctx.find('host:saveChunk').length > before, 'retry re-sent the retained payload');
    assert.equal(ctx.window.PdfCraftHost.getPendingWork().failed, 0, 'retry cleared the failed entry');
  });

  console.log('');
  console.log(failures === 0 ? 'ALL PROTOCOL CHECKS PASSED.' : failures + ' CHECK(S) FAILED.');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch(e => { console.error('HARNESS ERROR:', e); process.exit(2); });

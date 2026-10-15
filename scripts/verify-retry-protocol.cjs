const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const CHUNK = 256 * 1024;
const origin = 'https://appassets.androidplatform.net';
function host(src) {
  const messages = [], listeners = new Map(), timers = new Map(); let timerId = 0;
  const frame = { style: {}, contentWindow: {postMessage(){}}, contentDocument: null,
    addEventListener(){}, getAttribute(){return null}, setAttribute(){} };
  const ctx = vm.createContext({
    console: {log(){},warn(){},error(){}}, document: {getElementById(){return frame}},
    setTimeout(fn){timers.set(++timerId,fn);return timerId}, clearTimeout(id){timers.delete(id)},
    requestAnimationFrame(){}, addEventListener(t,fn){listeners.set(t,fn)},
    btoa:s=>Buffer.from(s,'binary').toString('base64'), atob:s=>Buffer.from(s,'base64').toString('binary'),
    WebMessageListener:{postMessage(s){messages.push(JSON.parse(s))}},
  });
  ctx.window = ctx;
  vm.runInContext(src,ctx);
  const deliver = data => listeners.get('message')({origin:'',source:null,data:JSON.stringify(data)});
  const save = () => listeners.get('message')({origin,source:frame.contentWindow,data:{type:'pdfcraft:save',name:'test.pdf',bytes:vm.runInContext(`new Uint8Array(${CHUNK*2+100}).buffer`,ctx)}});
  return {messages,deliver,save,timers};
}
const src=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/shell/host.js'),'utf8');
for (const name of ['consolidated host']) {
  let h=host(src); h.save();
  const first=h.messages.find(m=>m.type==='host:saveChunk');
  h.deliver({type:'host:saveChunkAck',transferId:first.transferId,seq:0,success:false,error:'first attempt failed'});
  h.deliver({type:'host:retryExport',transferId:first.transferId});
  const retry=h.messages.filter(m=>m.type==='host:saveChunk').at(-1);
  const before=h.messages.length;
  h.deliver({type:'host:saveChunkAck',transferId:first.transferId,seq:0,success:true});
  const advanced=h.messages.slice(before).some(m=>m.type==='host:saveChunk'&&m.seq===1);
  console.log(name+': retry reuses transfer ID = '+(retry.transferId===first.transferId)+'; old-attempt ACK advances retry = '+advanced);
  assert.equal(advanced,false);

  h=host(src);h.save();
  const tx=h.messages.find(m=>m.type==='host:saveChunk');
  h.deliver({type:'host:saveChunkAck',transferId:tx.transferId,seq:0,success:true});
  const mark=h.messages.length;
  h.deliver({type:'host:saveChunkAck',transferId:tx.transferId,seq:0,success:false,error:'stale failure for already-acknowledged chunk'});
  const failed=h.messages.slice(mark).some(m=>m.type==='host:saveFailed');
  console.log(name+': stale negative ACK for seq0 aborts seq1 = '+failed);
  assert.equal(failed,false);
  console.log(name+': emits host:pendingWork = '+h.messages.some(m=>m.type==='host:pendingWork'));
}

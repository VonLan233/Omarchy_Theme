const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../phone/voice');
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() {let resolve, reject; const promise = new Promise((r,j) => {resolve=r; reject=j;}); return {promise,resolve,reject};}
function harness(options = {}) {
  const elements = Object.fromEntries(['status','start','standby','stop','conversation','speaker'].map(id => [id,{textContent:'',disabled:false,play:async()=>{},pause(){}}]));
  const prepares=[], events=[], original={enabled:true,stopped:false,stop(){this.stopped=true; events.push('original stopped');}};
  const outgoing={...original,stop(){this.stopped=true; events.push('outgoing stopped');}};
  const stream={getTracks:()=>[original],clone:()=>({getTracks:()=>[outgoing]})};
  const posts=[],peers=[],timers=new Map(); let mediaCount=0, serial=0;
  const document={hidden:false,querySelector:selector=>elements[selector.slice(1)],addEventListener(name,fn){this[name]=fn;}};
  class Peer {
    constructor(){this.connectionState='new';this.iceGatheringState='complete';peers.push(this);}
    addTrack(track){this.track=track;}
    createDataChannel(){return {};}
    createOffer(){return options.offer || Promise.resolve({type:'offer',sdp:'v=0'});}
    async setLocalDescription(offer){this.localDescription=offer;this.localSet=true;}
    async setRemoteDescription(){this.remoteSet=true;this.connectionState='connected';this.onconnectionstatechange();}
    close(){this.closed=true;}
  }
  const native = options.native ? {
    startStandby(){events.push('standby');},stopStandby(){events.push('stop standby');},
    prepareConversation(generation){prepares.push(generation);events.push('prepare');},conversationEnded(){events.push('rearm');},assistantSelected(){return !!options.assistant;}
  } : undefined;
  const context={document,window:{NativeVoice:native,addEventListener(name,fn){this[name]=fn;}},
    navigator:{mediaDevices:{getUserMedia(){mediaCount++;events.push('browser microphone');return typeof options.media === 'function' ? options.media() : (options.media || Promise.resolve(stream));}}},
    crypto:{randomUUID:()=> 'a'.repeat(32)},RTCPeerConnection:Peer,AbortController,AbortSignal,performance,
    setTimeout(fn){timers.set(++serial,fn);return serial;},clearTimeout(id){timers.delete(id);},setInterval:()=>++serial,clearInterval(){},
    fetch:async(url,init)=>{posts.push({url,init});return {ok:true,json:async()=>({sdp:'v=0',ok:true})};}};
  vm.runInNewContext(fs.readFileSync(path.join(root,'voice.js'),'utf8'),context);
  return {elements,original,outgoing,stream,posts,peers,timers,document,context,events,prepares,ready:()=>context.window.nativeVoiceConversationReady(prepares.at(-1)),get mediaCount(){return mediaCount;}};
}
test('native standby opens no browser microphone, outgoing connection, or cloud session',async()=>{
  const h=harness({native:true}); h.elements.standby.onclick();
  assert.deepEqual(h.events,['standby']);assert.equal(h.mediaCount,0);assert.equal(h.peers.length,0);assert.equal(h.posts.length,0);
  h.context.window.nativeVoiceState('ready');assert.match(h.elements.status.textContent,/点通知/);
  h.document.hidden=true;h.document.visibilitychange();
  assert.equal(h.events.includes('stop standby'),false,'leaving page preserves explicit local standby');
});
test('assistant readiness is honestly labelled and native capture must release before WebRTC begins',async()=>{
  const offer=deferred(),h=harness({native:true,assistant:true,offer:offer.promise});
  h.context.window.nativeVoiceState('ready');assert.match(h.elements.status.textContent,/说「大肥鱼」唤醒/);
  h.context.window.nativeVoiceWake();assert.deepEqual(h.events,['prepare']);assert.equal(h.mediaCount,0);
  const ready=h.ready();await tick();
  assert.equal(h.mediaCount,1);assert.equal(h.outgoing.enabled,false);assert.equal(h.elements.speaker.muted,true);
  offer.resolve({type:'offer',sdp:'v=0'});await ready;
  assert.equal(h.outgoing.enabled,true);assert.equal(h.elements.speaker.muted,false);
  h.elements.stop.onclick();assert.equal(h.original.stopped,true);assert.equal(h.outgoing.stopped,true);assert.equal(h.peers[0].closed,true);
  assert.ok(h.events.indexOf('rearm') > h.events.indexOf('original stopped'));
});
test('cancel before native handoff ignores late callback and closes standby',async()=>{
  const h=harness({native:true});h.elements.start.onclick();h.elements.stop.onclick();
  await h.ready();assert.equal(h.mediaCount,0);assert.equal(h.posts.some(p=>p.url==='/start'),false);
  assert.deepEqual(h.events,['prepare','stop standby','rearm']);
});
test('background during native handoff re-arms local standby and cannot open browser capture',async()=>{
  const h=harness({native:true});h.elements.start.onclick();h.document.hidden=true;h.document.visibilitychange();
  await h.ready();assert.equal(h.mediaCount,0);assert.deepEqual(h.events,['prepare','rearm']);
});
test('cancel during browser permission releases the late stream before re-arming local detector',async()=>{
  const media=deferred(),h=harness({native:true,media:media.promise});h.elements.start.onclick();
  const ready=h.ready();await tick();
  h.document.hidden=true;h.document.visibilitychange();assert.equal(h.events.includes('rearm'),false);
  media.resolve(h.stream);await ready;
  assert.deepEqual(h.events.slice(-2),['original stopped','rearm']);assert.equal(h.peers.length,0);
});
test('browser manual start remains functional and unsupported native standby stays disabled',async()=>{
  const h=harness();assert.equal(h.elements.standby.disabled,true);
  await h.elements.start.onclick();assert.equal(h.outgoing.enabled,true);assert.equal(h.elements.speaker.muted,false);
  h.elements.stop.onclick();assert.equal(h.original.stopped,true);assert.equal(h.outgoing.stopped,true);assert.equal(h.elements.standby.disabled,true);
});
test('stop during offer prevents late SDP write and authenticated session start',async()=>{
  const offer=deferred(),h=harness({offer:offer.promise});const ready=h.elements.start.onclick();await tick();
  h.elements.stop.onclick();offer.resolve({sdp:'v=0'});await ready;
  assert.equal(h.peers[0].closed,true);assert.equal(h.peers[0].localSet,undefined);assert.equal(h.posts.some(p=>p.url==='/start'),false);
});
test('hidden page closes conversation and stale transport callbacks cannot revive it',async()=>{
  const h=harness();await h.elements.start.onclick();h.document.hidden=true;h.document.visibilitychange();
  h.peers[0].ontrack({streams:[{}]});
  assert.equal(h.elements.speaker.srcObject,null);assert.equal(h.original.stopped,true);assert.equal(h.outgoing.stopped,true);assert.equal(h.peers[0].closed,true);
  await h.elements.start.onclick();assert.equal(h.peers.length,1);
});

test('late canceled microphone resolution cannot rearm over a newer native conversation',async()=>{
  const media=deferred();let count=0;const oldTrack={stopped:false,stop(){this.stopped=true;}};
  const h=harness({native:true,media:()=>count++===0?media.promise:Promise.resolve(h.stream)});
  h.elements.start.onclick();const first=h.ready();await tick();
  h.document.hidden=true;h.document.visibilitychange();h.document.hidden=false;
  h.elements.start.onclick();await h.ready();
  media.resolve({getTracks:()=>[oldTrack]});await first;
  assert.equal(oldTrack.stopped,true,'old stream is always cleaned');assert.equal(h.original.stopped,false,'new stream remains live');
  assert.equal(h.events.filter(event=>event==='rearm').length,0,'old owner must not restart local capture over B');
  assert.equal(h.outgoing.enabled,true);h.elements.stop.onclick();
});
test('late canceled microphone rejection cannot rearm during a newer native handoff',async()=>{
  const media=deferred(),h=harness({native:true,media:media.promise});
  h.elements.start.onclick();const first=h.ready();await tick();
  h.document.hidden=true;h.document.visibilitychange();h.document.hidden=false;
  h.elements.start.onclick();media.reject(new Error('late denied'));await first;
  assert.equal(h.events.filter(event=>event==='rearm').length,0);
  assert.equal(h.mediaCount,1);h.elements.stop.onclick();
});
test('a stale native release acknowledgement cannot start the next generation',async()=>{
  const h=harness({native:true});h.elements.start.onclick();const old=h.prepares.at(-1);
  h.document.hidden=true;h.document.visibilitychange();h.document.hidden=false;h.elements.start.onclick();
  await h.context.window.nativeVoiceConversationReady(old);assert.equal(h.mediaCount,0);
  await h.ready();assert.equal(h.mediaCount,1);h.elements.stop.onclick();
});

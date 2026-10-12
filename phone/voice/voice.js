const status = document.querySelector('#status'), start = document.querySelector('#start');
const stop = document.querySelector('#stop'), conversation = document.querySelector('#conversation');
const speaker = document.querySelector('#speaker'), standby = document.querySelector('#standby');
window.voiceMetrics = {};
let active = null, nativeStarting = false, generation = 0;
const nativeVoice = window.NativeVoice;
async function post(path, body, keepalive = false, signal) {
  const response = await fetch(path, {method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify(body), keepalive, signal:keepalive ? undefined : (signal || AbortSignal.timeout(100000))});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '连接失败，请重试');
  return data;
}
function finish(message = '对话已结束。') {
  const wasStarting = nativeStarting;
  nativeStarting = false;
  const call = active; active = null;
  if (call) {
    clearInterval(call.pulse); clearTimeout(call.timeout); clearTimeout(call.deadline);
    call.abort.abort();
    call.outgoing?.getTracks().forEach(track => track.stop());
    call.stream?.getTracks().forEach(track => track.stop());
    call.pc?.close(); speaker.pause(); speaker.srcObject = null; speaker.muted = true;
    post('/stop', {id:call.id}, true).catch(() => {});
    if (call.stream) nativeVoice?.conversationEnded();
    else call.rearmOnMedia = true;
  }
  if (!call && wasStarting) nativeVoice?.conversationEnded();
  start.disabled = false; standby.disabled = !nativeVoice; stop.disabled = true; start.textContent = '开始对话 ▸'; status.textContent = message;
}
function transcript(call, event) {
  const turn = event.turn, key = turn?.id || event.turn_id;
  if (!key || !['turn.created','turn.delta','turn.done'].includes(event.type)) return;
  if (!call.turns.has(key)) {
    if (!turn || !['user','assistant'].includes(turn.role)) return;
    conversation.querySelector('.hint')?.remove();
    const line = document.createElement('p'); line.className = 'message' + (turn.role === 'assistant' ? ' assistant' : '');
    const label = document.createElement('span'); label.className = 'label'; label.textContent = turn.role === 'user' ? '你' : '语音助手';
    const text = document.createElement('span');
    line.append(label, text); conversation.append(line); call.turns.set(key, text);
    if (call.turns.size > 40) call.turns.delete(call.turns.keys().next().value);
    while (conversation.children.length > 40) conversation.firstElementChild.remove();
  }
  const text = call.turns.get(key);
  if (event.type === 'turn.delta') text.textContent += event.delta || '';
  else text.textContent = turn.transcript || '';
  conversation.scrollTop = conversation.scrollHeight;
}
function activate(call) {
  if (active !== call || !call.ready || call.talking || document.hidden) return;
  call.talking = true; clearTimeout(call.deadline);
  call.outgoing.getTracks().forEach(track => {track.enabled = true;});
  speaker.muted = false;
  if (speaker.srcObject) speaker.play().catch(() => {if (active === call) status.textContent = '请点按播放或调高媒体音量';});
  start.disabled = true; status.textContent = '正在聆听 · 可以说话了';
}
async function begin() {
  if (active || document.hidden) return;
  const call = {id:crypto.randomUUID().replaceAll('-', ''), turns:new Map(),
    abort:new AbortController(), metrics:{}, began:performance.now(), generation};
  active = call; window.voiceMetrics = call.metrics;
  start.disabled = true; standby.disabled = true; stop.disabled = false;
  status.textContent = '正在开启麦克风…';
  call.deadline = setTimeout(() => {if (active === call) finish('连接超时，请重试。');}, 100000);
  try {
    const stream = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
    if (active !== call) {
      stream.getTracks().forEach(track => track.stop());
      if (call.rearmOnMedia && call.generation === generation && !active && !nativeStarting) nativeVoice?.conversationEnded();
      return;
    }
    call.metrics.microphoneMs = performance.now() - call.began;
    call.stream = stream;
    call.outgoing = stream.clone();
    call.outgoing.getTracks().forEach(track => {track.enabled = false;});
    speaker.muted = true;
    const pc = call.pc = new RTCPeerConnection();
    call.outgoing.getTracks().forEach(track => pc.addTrack(track, call.outgoing));
    pc.ontrack = event => {
      if (active !== call) return;
      speaker.srcObject = event.streams[0];
      speaker.play().catch(() => {if (active === call && call.talking) status.textContent = '请调高媒体音量';});
    };
    pc.onconnectionstatechange = () => {
      if (active !== call) return;
      if (pc.connectionState === 'connected' && !call.ready) {
        clearTimeout(call.timeout); call.ready = true;
        call.metrics.cold_ready_ms = performance.now() - call.began;
        activate(call);
      }
      if (['failed','closed','disconnected'].includes(pc.connectionState)) finish('连接已中断，点按重试。');
    };
    const channel = pc.createDataChannel('oai-events');
    channel.onmessage = event => {
      if (active !== call) return;
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'error') {finish('语音服务暂不可用，请稍后重试。'); return;}
        if (!call.talking) return;
        transcript(call, data);
        if (data.type === 'input_transcript.added') status.textContent = '正在聆听…';
        if (data.type === 'output_transcript.added') status.textContent = '正在回答 · 可以继续说';
        if (data.type === 'turn.done' && data.turn?.role === 'assistant') status.textContent = '正在聆听 · 可以继续说';
      } catch {}
    };
    const offerBegan = performance.now();
    const offer = await pc.createOffer();
    if (active !== call) return;
    await pc.setLocalDescription(offer);
    if (active !== call) return;
    if (pc.iceGatheringState !== 'complete') await new Promise(resolve => {
      const done = () => {clearTimeout(timer); pc.removeEventListener('icegatheringstatechange', change); call.abort.signal.removeEventListener('abort', done); resolve();};
      const change = () => {if (pc.iceGatheringState === 'complete') done();};
      const timer = setTimeout(done, 2500);
      pc.addEventListener('icegatheringstatechange', change);
      call.abort.signal.addEventListener('abort', done, {once:true});
    });
    if (active !== call) return;
    call.metrics.offerMs = performance.now() - offerBegan;
    status.textContent = '正在连接语音助手…';
    // Each visible call renews its lease; the bridge closes abandoned sessions.
    call.pulse = setInterval(() => post('/ping', {id:call.id}, false, call.abort.signal).then(data => {
      if (!data.ok && active === call) finish('语音会话已结束，请重新开始。');
    }).catch(() => {if (active === call) finish('本机语音服务已停止。');}), 20000);
    const bridgeBegan = performance.now();
    const answer = await post('/start', {id:call.id, sdp:pc.localDescription.sdp}, false, call.abort.signal);
    if (active !== call) {post('/stop', {id:call.id}, true).catch(() => {}); return;}
    call.metrics.bridgeMs = performance.now() - bridgeBegan;
    await pc.setRemoteDescription({type:'answer',sdp:answer.sdp});
    if (active !== call) return;
    if (!call.ready) call.timeout = setTimeout(() => {if (active === call) finish('连接超时，请重试。');}, 30000);
  } catch (error) {
    if (active === call) finish(error.name === 'NotAllowedError' ? '需要麦克风权限才能对话。' : (error.message || '连接失败，请重试。'));
    if (call.rearmOnMedia) {
      call.rearmOnMedia = false;
      if (call.generation === generation && !active && !nativeStarting) nativeVoice?.conversationEnded();
    }
  }
}
function requestConversation() {
  if (active || nativeStarting || document.hidden) return;
  generation++;
  if (!nativeVoice) return begin();
  nativeStarting = true;
  stop.disabled = false; start.disabled = true; standby.disabled = true;
  status.textContent = '正在切换麦克风…';
  nativeVoice.prepareConversation(generation);
}
window.nativeVoiceConversationReady = readyGeneration => {
  if (!nativeStarting || document.hidden || readyGeneration !== generation) return;
  nativeStarting = false;
  return begin();
};
window.nativeVoiceWake = () => requestConversation();
window.nativeVoiceState = state => {
  if (active || nativeStarting) return;
  const messages = {
    loading:'正在加载本地「大肥鱼」模型…',
    ready:nativeVoice?.assistantSelected() ? '后台待机 · 说「大肥鱼」唤醒' : '后台待机 · 说「大肥鱼」后点通知对话（可设为系统助手）',
    paused:'本地检测已暂停。',
    wake:'已听到「大肥鱼」· 点按开始对话',
    stopped:'后台待机已关闭。',
    error:'本地唤醒失败，请点按开始对话。',
    permission:'后台待机需要麦克风和通知权限。',
    notification_permission:'请在系统设置开启通知，再启用后台待机。'
  };
  status.textContent = messages[state] || messages.error;
  start.disabled = false; standby.disabled = ['loading','ready','paused','wake'].includes(state);
  stop.disabled = !standby.disabled;
};
start.onclick = () => requestConversation();
standby.onclick = () => {
  if (!nativeVoice) {status.textContent = '本地「大肥鱼」后台待机需从手机像素语音应用开启。'; return;}
  if (active || nativeStarting || document.hidden) return;
  generation++;
  standby.disabled = true; stop.disabled = false;
  status.textContent = '正在开启本地后台待机…';
  nativeVoice.startStandby();
};
stop.onclick = () => {nativeVoice?.stopStandby(); finish();};
if (!nativeVoice) standby.disabled = true;
document.addEventListener('visibilitychange', () => {if (document.hidden) finish();});
window.addEventListener('pagehide', () => finish());

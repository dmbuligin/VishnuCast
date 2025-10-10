/* VishnuCast client — with optional logging (default: quiet), recv-only audio, single-button UI */
(function () {
  'use strict';

  // ---------- logger (opt-in) ----------
  var LOG = (function(){
    var on = false;
    try {
      var q = new URLSearchParams(location.search || '');
      if (q.get('log') === '1') on = true;
    } catch(_) {}
    try {
      if (localStorage.getItem('vishnucast.log') === '1') on = true;
    } catch(_) {}
    try {
      if (typeof window.__vc_log === 'boolean') on = on || !!window.__vc_log;
    } catch(_) {}
    function ts(){
      var d = new Date();
      var z = function(n,l){ n = String(n); while(n.length<l) n='0'+n; return n; };
      return d.getFullYear() + '-' + z(d.getMonth()+1,2) + '-' + z(d.getDate(),2) + ' ' +
             z(d.getHours(),2) + ':' + z(d.getMinutes(),2) + ':' + z(d.getSeconds(),2) + '.' + z(d.getMilliseconds(),3);
    }
    function log(){
      if (!on) return;
      var args = Array.prototype.slice.call(arguments);
      try { console.log.apply(console, ['[VishnuCast] ' + ts()].concat(args)); }
      catch (_) { /* no-op */ }
    }
    return { on:on, log:log };
  })();
  var log = LOG.log;

  // ---------- tiny UI CSS ----------
  (function injectStyles(){
    var css = [
      ':root{--vc-btn:#2563eb;--vc-btn-hover:#1d4ed8;--vc-btn-on:#16a34a;--vc-fg:#fff}',
      '#btn{appearance:none;-webkit-appearance:none;display:inline-flex;align-items:center;gap:8px;',
      '  border:none;border-radius:12px;background:var(--vc-btn);color:var(--vc-fg);',
      '  font-weight:600;line-height:1;user-select:none;cursor:pointer;',
      '  box-shadow:0 1px 0 rgba(0,0,0,.05),0 6px 12px rgba(37,99,235,.25);',
      '  transition:transform .12s ease,box-shadow .2s ease,background-color .2s ease,opacity .2s ease;',
      '  -webkit-tap-highlight-color: rgba(0,0,0,.06)}',
      '#btn:hover:not(:disabled){transform:translateY(-1px);box-shadow:0 2px 0 rgba(0,0,0,.05),0 8px 16px rgba(37,99,235,.25)}',
      '#btn:active:not(:disabled){transform:translateY(0)}',
      '#btn:disabled{opacity:.5;cursor:not-allowed}',
      '#btn .spin{width:16px;height:16px;border-radius:50%;border:2px solid rgba(255,255,255,.5);',
      '  border-top-color:#fff;animation:vc-spin .9s linear infinite}',
      '@keyframes vc-spin{to{transform:rotate(360deg)}}',
      '#status.connected{color:#059669}',
      '#a{display:none}'
    ].join('\n');
    var st = document.createElement('style');
    st.type = 'text/css';
    st.appendChild(document.createTextNode(css));
    document.head.appendChild(st);
  })();

  // ---------- i18n ----------
  var texts = (function(){
    var isRu = (navigator.language||'').toLowerCase().startsWith('ru');
    try {
      var stored = localStorage.getItem('vishnucast.lang');
      if (stored === 'en') isRu = false;
      if (stored === 'ru') isRu = true;
    } catch(_) {}
    var dict = {
      ru: { btn_connect:'Подключить', btn_disconnect:'Отключить',
            status_idle:'Готов', status_connecting:'Подключение…', status_connected:'Подключено', status_error:'Ошибка',
            lang_ru:'RU', lang_en:'EN' },
      en: { btn_connect:'Connect', btn_disconnect:'Disconnect',
            status_idle:'Ready', status_connecting:'Connecting…', status_connected:'Connected', status_error:'Error',
            lang_ru:'RU', lang_en:'EN' }
    };
    function t(key){ return (isRu?dict.ru:dict.en)[key] || key; }
    function setLang(l){
      if (l==='ru') isRu=true; else if (l==='en') isRu=false;
      try { localStorage.setItem('vishnucast.lang', isRu?'ru':'en'); } catch(_) {}
      apply();
    }
    function apply(){
      var btn = document.getElementById('btn');
      var status = document.getElementById('status');
      var ru = document.getElementById('lang-ru');
      var en = document.getElementById('lang-en');
      if (btn) btn.textContent = (state==='connected' ? t('btn_disconnect') : t('btn_connect'));
      if (status) status.textContent = t('status_idle');
      if (ru) ru.textContent = t('lang_ru');
      if (en) en.textContent = t('lang_en');
    }
    return { t:t, setLang:setLang, apply:apply };
  })();

  // ---------- DOM ----------
  var btn = document.getElementById('btn');
  var statusEl = document.getElementById('status');
  var audioEl = document.getElementById('a');
  var langRuBtn = document.getElementById('lang-ru');
  var langEnBtn = document.getElementById('lang-en');

  // гарантированно иметь <audio id="a">
  function ensureAudioEl(){
    if (audioEl && audioEl.nodeName === 'AUDIO') return audioEl;
    var el = document.getElementById('a');
    if (!el || el.nodeName !== 'AUDIO') {
      el = document.createElement('audio');
      el.id = 'a';
      el.setAttribute('playsinline', '');
      el.setAttribute('autoplay', '');
      el.style.display = 'none';
      document.body.appendChild(el);
      log('Created hidden <audio id="a">');
    }
    audioEl = el;
    return audioEl;
  }

  // ---------- State ----------
  var pc = null;
  var ws = null;
  var userStopped = false;
  var state = 'idle'; // 'idle' | 'connecting' | 'connected'
  var stopping = false;
  var reofferTimer = null;
  var keepAliveTimer = null;

  // --- WebAudio mixer state (MIC + PLAYER) ---
  var mix = { alpha: 0.0, micMuted: false };
  var ac = null, gainMic = null, gainPlayer = null, srcMic = null, srcPlayer = null, mixOut = null;
  var currentOutStream = null; // что сейчас выведено в <audio>



  var currentOutStream = null; // ← что сейчас выведено в <audio>


  function ensureMixer() {
    try {
      ensureAudioEl(); // до любых setAttribute/play

      ac = window.__vc_ac || ac;
      if (!ac && (window.AudioContext || window.webkitAudioContext)) {
        var AC = window.AudioContext || window.webkitAudioContext;
        ac = new AC();
        window.__vc_ac = ac;
      }
      if (!ac) return false;

      // важный жест: на Android/мобилках иногда стартует "suspended"
      try { if (ac.state === 'suspended') ac.resume(); } catch(_){}

      // Создаём гейны
      if (!gainMic)    { gainMic    = ac.createGain();   gainMic.gain.value = 1.0; }
      if (!gainPlayer) { gainPlayer = ac.createGain();   gainPlayer.gain.value = 0.0; }

      // Миксуем в MediaStreamDestination, srcObject назначим в ontrack при необходимости
      if (!mixOut) {
        mixOut = ac.createMediaStreamDestination();
        try { gainMic.connect(mixOut); } catch(_) {}
        try { gainPlayer.connect(mixOut); } catch(_) {}
      }

      updateGains();
      return true;
    } catch(e) {
      log('ensureMixer error:', e && e.message);
      return false;
    }
  }

  function updateGains() {
    if (!gainMic || !gainPlayer) return;
    var a = Math.max(0, Math.min(1, Number(mix.alpha) || 0)); // clamp [0..1]
    gainPlayer.gain.value = a;
    gainMic.gain.value = mix.micMuted ? 0 : (1 - a);
  }

  // ---------- Language switches ----------
  (function initLang(){
    if (langRuBtn) langRuBtn.addEventListener('click', function(){ texts.setLang('ru'); setBtn(); setStatus(); });
    if (langEnBtn) langEnBtn.addEventListener('click', function(){ texts.setLang('en'); setBtn(); setStatus(); });
    texts.apply();
  })();

  // ---------- Button handler ----------
  if (btn) {
    btn.addEventListener('click', function(){
      if (state === 'connected' || state === 'connecting') {
        log('Button: Disconnect clicked');
        stopAll(true);
      } else {
        log('Button: Connect clicked');
        start();
      }
    });
  }

  // ---------- Helpers ----------
  function setStatus(txt) {
    if (!statusEl) return;
    if (txt == null) {
      txt = (state === 'connected') ? texts.t('status_connected')
          : (state === 'connecting') ? texts.t('status_connecting') : texts.t('status_idle');
    }
    statusEl.textContent = txt;
    statusEl.className = (state === 'connected') ? 'connected' : '';
  }

  function setBtn() {
    if (!btn) return;
    btn.disabled = (state === 'connecting');
    btn.textContent = (state === 'connected' ? texts.t('btn_disconnect') : texts.t('btn_connect'));
    btn.style.backgroundColor = (state === 'connected' ? 'var(--vc-btn-on)' : 'var(--vc-btn)');
  }

  function resetBuffers(){
    try { ensureAudioEl(); audioEl.pause(); audioEl.removeAttribute('src'); audioEl.srcObject = null; } catch(e){ log('audio src reset error:', e && e.message); }
    try { if (srcMic) { srcMic.disconnect(); srcMic = null; } } catch(_) {}
    try { if (srcPlayer) { srcPlayer.disconnect(); srcPlayer = null; } } catch(_) {}
  }

  function stopAll(byUser){
    if (stopping) return;
    stopping = true;
    userStopped = !!byUser;

    log('Stopping…');
    if (reofferTimer) { try { clearTimeout(reofferTimer); } catch(_){} reofferTimer = null; }

    try { if (mixOut) { /* keep created */ } } catch(_){}
    try { if (gainMic) gainMic.gain.value = 1.0; } catch(_){}
    try { if (gainPlayer) gainPlayer.gain.value = 0.0; } catch(_){}

    try { if (pc && pc.ontrack) pc.ontrack = null; } catch(_){}
    try { if (pc) pc.onicecandidate = null; } catch(_){}
    try { if (pc && pc.signalingState !== 'closed') pc.close(); } catch(_){}
    if (pc) log('PC closed');
    pc = null;

    safeCloseWs();

    try { ensureAudioEl(); audioEl.pause(); audioEl.removeAttribute('src'); audioEl.srcObject = null; } catch(e){ log('audio srcObject clear error:', e); }
    resetBuffers();

    state = 'idle';
    setBtn();
    setStatus(texts.t('ws_closed'));

    stopping = false;
  }

  function safeCloseWs() {
    try {
      if (ws && ws.readyState === 1) {
        log('WS closing by client');
        ws.close();
      }
    } catch(_){}
    ws = null;
  }

  // ---------- Signaling ----------
  function isLikelySdpString(s){
    return typeof s === 'string' && (s.startsWith('v=0') || s.indexOf('\nm=audio') >= 0 || s.indexOf('\na=') >= 0);
  }

  function handleSignal(raw){
    try {
      var msg = raw;
      if (typeof raw === 'string') {
        try { msg = JSON.parse(raw); } catch(_) { /* leave as string */ }
      }

      if (isLikelySdpString(msg)) {
        var desc = { type: (msg.indexOf('\na=fingerprint:') >= 0 && msg.indexOf('\nm=video') < 0 ? (pc && pc.signalingState === 'have-local-offer' ? 'answer' : 'offer') : 'answer'), sdp: msg };
        log('Signal: SDP string →', desc.type, 'len=', desc.sdp.length);
        onRemoteSdp(desc);
        return;
      }

      if (msg && typeof msg === 'object') {
        if (msg.sdp && (typeof msg.sdp === 'string' || (msg.sdp.type && msg.sdp.sdp))) {
          var d = (typeof msg.sdp === 'string')
            ? { type: (msg.type || 'answer'), sdp: msg.sdp }
            : { type: (msg.sdp.type || 'answer'), sdp: msg.sdp.sdp };
          log('Signal: SDP object →', d.type, 'len=', d.sdp.length);
          onRemoteSdp(d);
          return;
        }
        if (msg.type && msg.sdp) {
          log('Signal: flat {type,sdp} →', msg.type, 'len=', (msg.sdp||'').length);
          onRemoteSdp({ type: msg.type, sdp: msg.sdp });
          return;
        }
        if (msg.candidate || msg.candidates) {
          var arr = [];
          if (msg.candidates && Array.isArray(msg.candidates)) arr = msg.candidates;
          else if (msg.candidate) arr = [ msg ];
          log('Signal: ICE candidates count=', arr.length);
          arr.forEach(function(c){
            var cand = c.candidate || c;
            var init = (typeof cand === 'string')
              ? { candidate: cand, sdpMid: c.sdpMid || 'audio', sdpMLineIndex: c.sdpMLineIndex || 0 }
              : cand;
            try {
              pc && pc.addIceCandidate(new RTCIceCandidate(init)).then(function(){
                log('ICE add OK:', init.candidate && init.candidate.split(' ').slice(0,3).join(' '));
              }).catch(function(e){
                log('ICE add FAIL:', e && e.message);
              });
            } catch(e){
              log('ICE add EXC:', e && e.message);
            }
          });
          return;
        }
        if (msg.cmd === 'bye' || msg.bye) { log('Signal: bye'); stopAll(false); return; }
        if (msg.needOffer || msg.cmd === 'need-offer') { log('Signal: need-offer'); sendOffer(); return; }
      }
      // keep-alive ответ сервера
      if (msg && typeof msg === 'object' && msg.type === 'pong') {
        var rtt = (typeof msg.t === 'number') ? (Date.now() - msg.t) : null;
        if (rtt != null && rtt < 0) rtt = Math.abs(rtt);
        log('WS keep-alive ← pong', (rtt != null ? ('RTT≈' + rtt + 'ms') : ''), (msg.ts ? ('srvTs=' + msg.ts) : ''));
        return;
      }
      // Простой ACK от сервера за оффер — игнорируем
      if (msg && typeof msg === 'object' && msg.type === 'ack') {
        log('WS ack:', msg.stage || '');
        return;
      }

      // Применение микса без UI в браузере
      if (msg && typeof msg === 'object' && msg.type === 'mix') {
        if (typeof msg.alpha === 'number') mix.alpha = msg.alpha;
        if (typeof msg.micMuted === 'boolean') mix.micMuted = msg.micMuted;
        updateGains();
        log('Mix apply:', 'alpha=', mix.alpha, 'micMuted=', mix.micMuted);
        return;
      }

      log('Signal: unrecognized payload');
    } catch (e){
      log('Signal handling error:', e);
    }
  }

  function onRemoteSdp(desc){
    if (!pc) { log('onRemoteSdp: no pc'); return; }
    log('PC setRemoteDescription:', desc.type);
    pc.setRemoteDescription(new RTCSessionDescription(desc)).then(function(){
      log('PC remoteDescription set');
      if (desc.type === 'offer') {
        log('PC createAnswer');
        return pc.createAnswer().then(function(ans){
          log('PC setLocalDescription(answer)');
          return pc.setLocalDescription(ans);
        }).then(function(){
          log('Send answer to WS');
          sendAnswer();
        }).catch(function(e){
          log('Answer path error:', e && e.message);
        });
      }
    }).catch(function(e){
      log('setRemoteDescription error:', e && e.message);
    });
  }

  // ---------- WebRTC ----------
  function start(){
    if (state !== 'idle') { log('start: wrong state', state); return; }
    state = 'connecting';
    setBtn();
    setStatus();

    userStopped = false;
    ensureAudioEl(); // создадим <audio>, если его нет
    resetBuffers();

    // connect WS
    var proto = (location.protocol === 'https:' ? 'wss' : 'ws');
    var wsUrl = proto + '://' + location.host + '/ws';
    ws = new WebSocket(wsUrl);
    ws.onerror = function(ev){ log('WS error', ev); };
    ws.onclose = function(ev){ log('WS close', ev && ev.code, ev && ev.reason); stopAll(false); };
    ws.onmessage = function(ev){
      var data = ev && ev.data;
      log('WS message len=', (typeof data === 'string' ? data.length : (data && data.byteLength) || 0));
      handleSignal(data);
    };

    ws.onopen = function(){
      log('WS open');
      if (userStopped) { log('WS open but user already stopped — closing'); safeCloseWs(); return; }
      // ⬇️ keep-alive каждые 15s
      try { if (keepAliveTimer) clearInterval(keepAliveTimer); } catch(_) {}
      keepAliveTimer = setInterval(function(){
        try {
          if (ws && ws.readyState === 1) {
            ws.send(JSON.stringify({ type: 'ping', t: Date.now() }));
            log('WS keep-alive → ping');
          }
        } catch(e){ log('keep-alive send error:', e && e.message); }
      }, 15000);

      beginWebRtc();
    };
  }

  function stop(){
    stopAll(true);
  }

  function beginWebRtc(){
    if (!ws || ws.readyState !== 1) { log('beginWebRtc: WS not ready'); return; }

    var conf = { iceServers: [] };
    pc = new RTCPeerConnection(conf);
    log('PC created');

    // Ранний запуск микшера → <audio> и AC готовы до прихода треков
    ensureMixer();

    try {
      pc.addTransceiver('audio', { direction: 'recvonly' });
      log('PC addTransceiver recvonly(audio)-1');
      pc.addTransceiver('audio', { direction: 'recvonly' }); // второй аудио-трансивер для PLAYER
      log('PC addTransceiver recvonly(audio)-2');
    } catch(e){
      log('addTransceiver error:', e && e.message);
    }

    pc.onicecandidate = function(ev){
      if (ev.candidate && ws && ws.readyState === 1) {
        var c = ev.candidate;
        log('ICE local candidate → send', c.candidate && c.candidate.split(' ').slice(0,3).join(' '));
        ws.send(JSON.stringify({
          candidate: c.candidate,
          sdpMid: c.sdpMid,
          sdpMLineIndex: c.sdpMLineIndex
        }));
      }
    };

    pc.onicegatheringstatechange = function(){
      log('ICE gathering state:', pc.iceGatheringState);
    };
    pc.oniceconnectionstatechange = function(){
      log('ICE connection state:', pc.iceConnectionState);
      if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected') {
        setStatus(texts.t('status_error'));
        stopAll(false);
      }
    };
    pc.onsignalingstatechange = function(){
      log('PC signaling state:', pc.signalingState);
    };
    pc.onconnectionstatechange = function(){
      log('PC conn state:', pc.connectionState);
      if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        setStatus(texts.t('status_error'));
        stopAll(false);
      }
    };
    if (typeof pc.onselectedcandidatepairchange === 'function') {
      pc.onselectedcandidatepairchange = function(e){ log('Selected candidate pair changed', e); };
    }

    pc.ontrack = function(ev){
      log('PC ontrack kind=', ev.track && ev.track.kind, 'streams=', (ev.streams||[]).length);
      try {
        var stream = (ev.streams && ev.streams[0])
          ? ev.streams[0]
          : (ev.track ? new MediaStream([ev.track]) : null);

        if (!stream) return;

        if (!ensureMixer()) {
          // Fallback на <audio> если AudioContext не поднялся
          ensureAudioEl();
          audioEl.muted = false;
          audioEl.volume = 1.0;
          audioEl.srcObject = stream;

          let tries = 0;
          (function kick(){
            audioEl.play().then(function(){ log('audio.play OK (fallback)'); }).catch(function(err){
              tries++;
              log('audio.play FAIL (fallback) try', tries, err && err.message);
              if (tries < 3) setTimeout(kick, 300);
            });
          })();
        } else {
          // Подключаем в WebAudio-микшер (MIC/PLAYER по msid/id)
          var sid = (stream.id || '');
          var isPlayer = /player/i.test(sid) || /VC_PLAYER/i.test((ev.track && ev.track.id) || '');

          try {
            if (isPlayer) {
              if (srcPlayer) { try { srcPlayer.disconnect(); } catch(_){} }
              srcPlayer = ac.createMediaStreamSource(stream);
              srcPlayer.connect(gainPlayer);
              log('Mixer attach: PLAYER', 'sid=', sid);
            } else {
              if (srcMic) { try { srcMic.disconnect(); } catch(_){} }
              srcMic = ac.createMediaStreamSource(stream);
              srcMic.connect(gainMic);
              log('Mixer attach: MIC', 'sid=', sid);
            }
            updateGains();

            // ── Роутинг звука (дебаунс srcObject):
            // Предпочитаем DIRECT пока alpha==0 и MIC не mute (поведение V1).
            // MIX включаем только если (оба источника есть) И (alpha>0 ИЛИ micMuted==true).
            if (typeof ensureAudioEl === 'function') ensureAudioEl();
            var bothPresent = !!srcMic && !!srcPlayer;
            var a = Number(mix.alpha) || 0;
            var useMix = bothPresent && (mix.micMuted || a > 0);
            var target = (useMix && typeof mixOut !== 'undefined' && mixOut) ? mixOut.stream : stream;

            // завести currentOutStream глобально рядом с объявлением микшера:
            //   var currentOutStream = null;
            if (typeof currentOutStream === 'undefined') { /* если нет — пропустим дебаунс */ }

            if (!currentOutStream || currentOutStream !== target) {
              audioEl.muted = false;
              audioEl.volume = 1.0;
              audioEl.srcObject = target;
              currentOutStream = target;
              log('Audio route:', useMix ? ('MIX (alpha=' + a.toFixed(2) + ', micMuted=' + !!mix.micMuted + ')')
                                         : ('DIRECT (alpha=' + a.toFixed(2) + ', micMuted=' + !!mix.micMuted + ')'));
              var tries2 = 0;
              (function kick2(){
                audioEl.play().then(function(){ log('audio.play OK (route)'); }).catch(function(err){
                  tries2++;
                  log('audio.play FAIL (route) try', tries2, err && err.message);
                  if (tries2 < 5) setTimeout(kick2, 300);
                });
              })();
            } else {
              log('Audio route: unchanged');
            }


          } catch (e) {
            log('mixer attach error:', e && e.message);
          }
        }

      }
      catch (e) {
        log('ontrack error:', e && e.message);
      }

      state = 'connected';
      setBtn();
      setStatus();
      cancelReofferTimer();
    };

    sendOffer();

    reofferTimer = setTimeout(function(){
      if (state === 'connecting' && ws && ws.readyState === 1) {
        log('Re-send offer due to timeout');
        sendOffer();
      }
    }, 4000);
  }

  function sendOffer(){
    if (!pc) { log('sendOffer: no pc'); return; }
    log('PC createOffer');
    pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: false }).then(function(offer){
      log('PC setLocalDescription(offer)');
      return pc.setLocalDescription(offer);
    }).then(function(){
      if (!ws || ws.readyState !== 1) { log('sendOffer: WS not ready'); return; }
      var payload = { type: pc.localDescription.type, sdp: pc.localDescription.sdp };
      log('WS send offer type=', payload.type, 'len=', (payload.sdp||'').length);
      try {
        ws.send(JSON.stringify(payload));
      } catch(e){ log('WS send offer error:', e && e.message); }
    }).catch(function(e){
      log('Offer path error:', e && e.message);
    });
  }

  function cancelReofferTimer(){
    if (reofferTimer) { try { clearTimeout(reofferTimer); } catch(_){} reofferTimer = null; }
  }

  function sendAnswer(){
    if (!pc || !pc.localDescription) { log('sendAnswer: no localDescription'); return; }
    if (!ws || ws.readyState !== 1) { log('sendAnswer: WS not ready'); return; }
    log('WS send answer (both formats for compatibility)');
    ws.send(JSON.stringify({ sdp: pc.localDescription }));
    ws.send(JSON.stringify({ type: pc.localDescription.type, sdp: pc.localDescription.sdp }));
  }

  // ---------- init ----------
  texts.apply();
  setBtn();
  setStatus();
  ensureAudioEl(); // на всякий случай создать заранее

  log('Client boot. Log ON =', LOG.on);
})();

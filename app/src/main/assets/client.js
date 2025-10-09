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
      return d.toISOString().replace('T',' ').replace('Z','');
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
    ].join('\n');
    var st = document.createElement('style');
    st.type = 'text/css';
    st.appendChild(document.createTextNode(css));
    document.head.appendChild(st);
  })();

  // ---------- i18n ----------
  var texts = (function(){
    var isRu = (navigator.language || '').toLowerCase().startsWith('ru');
    var storeKey = 'vishnucast.lang';
    var saved = (function(){ try { return localStorage.getItem(storeKey); } catch(_) { return null; }})();
    var lang = saved || (isRu ? 'ru' : 'en');

    function setLang(l){
      lang = (l === 'ru') ? 'ru' : 'en';
      try { localStorage.setItem(storeKey, lang); } catch(_) {}
      applyTexts();
      log('Lang set to', lang);
    }

    var dict = {
      en: {
        connect: 'Connect',
        connecting: 'Connecting…',
        disconnect: 'Disconnect',
        status_idle: 'Idle. Press Connect to start.',
        status_connecting: 'Connecting…',
        status_connected: 'Connected',
        status_error: 'Error',
        hint_open: 'Open in your browser: ',
        ws_closed: 'Connection closed.',
      },
      ru: {
        connect: 'Подключиться',
        connecting: 'Подключение…',
        disconnect: 'Отключиться',
        status_idle: 'Ожидание. Нажмите «Подключиться».',
        status_connecting: 'Подключение…',
        status_connected: 'Подключено',
        status_error: 'Ошибка',
        hint_open: 'Откройте в браузере: ',
        ws_closed: 'Соединение закрыто.',
      }
    };

    function t(key){ return (dict[lang] && dict[lang][key]) || key; }

    function applyTexts(){
      var btn = document.getElementById('btn');
      var status = document.getElementById('status');
      var hint = document.getElementById('hint');

      if (btn) btn.textContent = (state === 'connected') ? t('disconnect') : (state === 'connecting' ? t('connecting') : t('connect'));
      if (status) {
        status.textContent = (state === 'connected') ? t('status_connected')
          : (state === 'connecting' ? t('status_connecting') : t('status_idle'));
        status.classList.toggle('connected', state === 'connected');
      }
      if (hint) {
        try {
          var origin = location.protocol + '//' + location.host;
          hint.innerHTML = '<span>' + t('hint_open') + '</span><a href="' + origin + '">' + origin + '</a>';
        } catch (_) {
          hint.textContent = t('hint_open');
        }
      }
    }

    return { t:t, setLang:setLang, apply:applyTexts, get lang(){ return lang; } };
  })();

  // ---------- elements ----------
  var btn = document.getElementById('btn');
  var statusEl = document.getElementById('status');
  var hintEl = document.getElementById('hint');
  var audioEl = document.getElementById('audio');
  var langRuBtn = document.getElementById('langRuBtn');
  var langEnBtn = document.getElementById('langEnBtn');

  // ---------- state ----------
  var pc = null;
  var ws = null;
  var userStopped = false;
  var state = 'idle'; // 'idle' | 'connecting' | 'connected'
  var stopping = false;
  var reofferTimer = null;
  var keepAliveTimer = null;

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
    statusEl.classList.toggle('connected', state === 'connected');
    log('Status:', txt, '| state=', state);
  }

  function setBtn() {
    if (!btn) return;
    btn.disabled = (state === 'connecting');
    while (btn.firstChild) btn.removeChild(btn.firstChild);
    if (state === 'connecting') {
      var sp = document.createElement('span'); sp.className = 'spin';
      btn.appendChild(sp);
      btn.appendChild(document.createTextNode(texts.t('connecting')));
    } else {
      btn.appendChild(document.createTextNode(state === 'connected' ? texts.t('disconnect') : texts.t('connect')));
    }
    if (state === 'connected') {
      btn.style.backgroundColor = 'var(--vc-btn-on)';
      btn.style.boxShadow = '0 1px 0 rgba(0,0,0,.05),0 6px 12px rgba(22,163,74,.25)';
    } else {
      btn.style.backgroundColor = 'var(--vc-btn)';
      btn.style.boxShadow = '0 1px 0 rgba(0,0,0,.05),0 6px 12px rgba(37,99,235,.25)';
    }
    log('Button updated | state=', state);
  }

  function wsPathFromQuery() {
    var q = location.search || '';
    if (!q) return '/ws';
    try {
      var params = new URLSearchParams(q);
      var p = params.get('wspath');
      if (p && p[0] !== '/') p = '/' + p;
      return p || '/ws';
    } catch (_){ return '/ws'; }
  }

  function makeWsUrl() {
    var proto = (location.protocol === 'https:') ? 'wss://' : 'ws://';
    var url = proto + location.host + wsPathFromQuery();
    log('WS URL:', url);
    return url;
  }

  function safeClosePc() {
    try { if (pc) pc.ontrack = null; } catch(_){}
    try { if (pc) pc.onicecandidate = null; } catch(_){}
    try { if (pc && pc.signalingState !== 'closed') pc.close(); } catch(_){}
    if (pc) log('PC closed');
    pc = null;
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

  function resetBuffers(){}
  function cancelReofferTimer(){ if (reofferTimer) { clearTimeout(reofferTimer); reofferTimer = null; log('Re-offer timer canceled'); } }

  // ---------- Start / Stop ----------
  function start() {
    if (state === 'connecting' || state === 'connected') return;

    // Разблокируем аудио-д движок в рамках жеста пользователя
    try {
      if (window.AudioContext || window.webkitAudioContext) {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!window.__vc_ac) window.__vc_ac = new AC();
        if (window.__vc_ac.state === 'suspended') { window.__vc_ac.resume().catch(()=>{}); }
        log('AudioContext state:', window.__vc_ac.state);
      }
    } catch(e) { log('AudioContext error:', e); }

    userStopped = false;
    stopping = false;
    state = 'connecting';
    setBtn();
    setStatus();

    var url = makeWsUrl();
    try {
      ws = new WebSocket(url);
      log('WS created');
    } catch (e) {
      log('WS create error:', e);
      setStatus(texts.t('status_error'));
      return;
    }

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
          } catch(e){ log('WS keep-alive error:', e && e.message); }
        }, 15000);
      beginWebRtc();
    };

    ws.onclose = function(ev){
      log('WS close code=', ev.code, 'reason=', ev.reason);
      try { if (keepAliveTimer) { clearInterval(keepAliveTimer); keepAliveTimer = null; } } catch(_) {}
      if (!userStopped) setStatus(texts.t('ws_closed'));
      stopAll(false);
    };

    ws.onerror = function(err){
      log('WS error:', err);
      setStatus(texts.t('status_error'));
    };

    ws.onmessage = function(ev){
      log('WS message len=', (''+ev.data).length);
      handleSignal(ev.data);
    };
  }

  function stopAll(manual){

    try { if (keepAliveTimer) { clearInterval(keepAliveTimer); keepAliveTimer = null; } } catch(_){}
    if (manual == null) manual = false;
    if (stopping) return;
    stopping = true;

    userStopped = manual;
    log('StopAll called. manual=', manual, 'state=', state);

    cancelReofferTimer();
    safeCloseWs();
    safeClosePc();
    try { audioEl.srcObject = null; } catch(e) { log('audio srcObject clear error:', e); }
    resetBuffers();

    state = 'idle';
    setBtn();
    setStatus(texts.t('ws_closed'));

    stopping = false;
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
        var desc = { type: (msg.indexOf('\na=fingerprint:') >= 0 ? (pc && pc.localDescription && pc.localDescription.type === 'offer' ? 'answer' : 'offer') : 'answer'), sdp: msg };
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
          else arr = [msg];

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
      if (rtt != null && rtt < 0) rtt = Math.abs(rtt); // на всякий случай
      log('WS keep-alive ← pong', (rtt != null ? ('RTT≈' + rtt + 'ms') : ''), (msg.ts ? ('srvTs=' + msg.ts) : ''));
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
      if (desc.type === 'offer') {
        log('PC createAnswer');
        pc.createAnswer().then(function(ans){
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
  function beginWebRtc(){
    if (!ws || ws.readyState !== 1) { log('beginWebRtc: WS not ready'); return; }

    var conf = { iceServers: [] };
    pc = new RTCPeerConnection(conf);
    log('PC created');

    try {
      pc.addTransceiver('audio', { direction: 'recvonly' });
      log('PC addTransceiver recvonly(audio)');
      pc.addTransceiver('audio', { direction: 'recvonly' }); // второй аудио-трансивер для PLAYER

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
      log('Signaling state:', pc.signalingState);
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
        if (stream) {
          audioEl.setAttribute('playsinline', '');
          audioEl.setAttribute('autoplay', '');
          audioEl.muted = false;
          audioEl.volume = 1.0;
          audioEl.srcObject = stream;

          let tries = 0;
          (function kick(){
            audioEl.play().then(function(){ log('audio.play OK'); }).catch(function(err){
              tries++;
              log('audio.play FAIL try', tries, err && err.message);
              if (tries < 3) setTimeout(kick, 300);
            });
          })();
        }
      } catch (e) {
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
      ws.send(JSON.stringify(payload));
    }).catch(function(e){
      log('Offer path error:', e && e.message);
      setStatus(texts.t('status_error'));
      stopAll(false);
    });
  }

  function sendAnswer(){
    if (!pc || !pc.localDescription) { log('sendAnswer: no localDescription'); return; }
    if (!ws || ws.readyState !== 1) { log('sendAnswer: WS not ready'); return; }
    log('WS send answer (both formats for compatibility)');
    ws.send(JSON.stringify({ sdp: pc.localDescription }));
    ws.send(JSON.stringify({ type: pc.localDescription.type, sdp: pc.localDescription.sdp }));
  }

  // ---------- init ----------
  setBtn();
  setStatus();

  log('Client boot. Log ON =', LOG.on);
})();

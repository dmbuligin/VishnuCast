/* VishnuCast client — robust signaling (flat/nested), recv-only audio, RU/EN, single-button UI */
(function () {
  'use strict';

  // ---------- Inject minimal UI CSS ----------
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
        ws_error: 'WebSocket error',
        pc_failed: 'PeerConnection failed',
        track_ended: 'Audio track ended',
        no_audio: 'No audio track received',
        lang_label: 'Language',
        lang_ru: 'Рус',
        lang_en: 'EN',
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
        ws_error: 'Ошибка WebSocket',
        pc_failed: 'Ошибка PeerConnection',
        track_ended: 'Аудио-трек завершён',
        no_audio: 'Аудио-трек не получен',
        lang_label: 'Язык',
        lang_ru: 'Рус',
        lang_en: 'EN',
      }
    };

    function t(key){ return (dict[lang] && dict[lang][key]) || key; }

    function applyTexts(){
      var btn = document.getElementById('btn');
      var status = document.getElementById('status');
      var hint = document.getElementById('hint');
      var langLabel = document.getElementById('langLabel');
      var langRu = document.getElementById('langRu');
      var langEn = document.getElementById('langEn');

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
      if (langLabel) langLabel.textContent = t('lang_label') + ':';
      if (langRu) langRu.textContent = t('lang_ru');
      if (langEn) langEn.textContent = t('lang_en');
    }

    return { t:t, setLang:setLang, apply:applyTexts, get lang(){ return lang; } };
  })();

  // ---------- UI elements ----------
  var btn = document.getElementById('btn');
  var statusEl = document.getElementById('status');
  var hintEl = document.getElementById('hint');
  var audioEl = document.getElementById('audio');
  var langRuBtn = document.getElementById('langRuBtn');
  var langEnBtn = document.getElementById('langEnBtn');

  // ---------- State ----------
  var pc = null;
  var ws = null;
  var userStopped = false;
  var state = 'idle'; // 'idle' | 'connecting' | 'connected'
  var stopping = false;
  var reofferTimer = null;

  // ---------- Language switches ----------
  (function initLang(){
    if (langRuBtn) langRuBtn.addEventListener('click', function(){ texts.setLang('ru'); setBtn(); setStatus(); });
    if (langEnBtn) langEnBtn.addEventListener('click', function(){ texts.setLang('en'); setBtn(); setStatus(); });
    texts.apply();
  })();

  // ---------- Button handler ----------
  if (btn) {
    btn.addEventListener('click', function(){
      if (state === 'connected' || state === 'connecting') stopAll(true);
      else start();
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
  }

  function log() {
    if (window && window.console && console.log) console.log.apply(console, arguments);
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
    return proto + location.host + wsPathFromQuery();
  }

  function safeClosePc() {
    try { if (pc) pc.ontrack = null; } catch(_){}
    try { if (pc) pc.onicecandidate = null; } catch(_){}
    try { if (pc && pc.signalingState !== 'closed') pc.close(); } catch(_){}
    pc = null;
  }

  function safeCloseWs() {
    try { if (ws && ws.readyState === 1) ws.close(); } catch(_){}
    ws = null;
  }

  function resetBuffers(){}

  function cancelReofferTimer(){
    if (reofferTimer) { clearTimeout(reofferTimer); reofferTimer = null; }
  }

  // ---------- Start / Stop ----------
  function start() {
    if (state === 'connecting' || state === 'connected') return;
    userStopped = false;
    stopping = false;
    state = 'connecting';
    setBtn();
    setStatus();

    var url = makeWsUrl();
    log('[VC] connecting to', url);
    ws = new WebSocket(url);

    ws.onopen = function(){
      if (userStopped) { safeCloseWs(); return; }
      log('[VC] ws open');
      beginWebRtc();
    };

    ws.onclose = function(ev){
      log('[VC] ws close', ev && ev.code, ev && ev.reason);
      if (!userStopped) setStatus(texts.t('ws_closed'));
      stopAll(false);
    };

    ws.onerror = function(err){
      log('[VC] ws error', err);
      setStatus(texts.t('ws_error'));
    };

    ws.onmessage = function(ev){
      handleSignal(ev.data);
    };
  }

  function stopAll(manual){
    if (manual == null) manual = false;
    if (stopping) return;
    stopping = true;

    userStopped = manual;
    cancelReofferTimer();
    safeCloseWs();
    safeClosePc();
    try { audioEl.srcObject = null; } catch(_) {}
    resetBuffers();

    state = 'idle';
    setBtn();
    setStatus(texts.t('ws_closed'));
    log('[VC] Disconnected');

    stopping = false;
  }

  // ---------- Signaling helpers ----------
  function isLikelySdpString(s){
    return typeof s === 'string' && (s.startsWith('v=0') || s.indexOf('\nm=audio') >= 0 || s.indexOf('\na=') >= 0);
  }

  function handleSignal(raw){
    try {
      try { log('[VC] sig in:', raw); } catch(_) {}
      var msg = raw;
      if (typeof raw === 'string') {
        // Try JSON first
        try { msg = JSON.parse(raw); } catch(_) { /* leave as string */ }
      }

      // 1) Plain SDP string (answer or offer)
      if (isLikelySdpString(msg)) {
        var desc = { type: (msg.indexOf('\na=fingerprint:') >= 0 ? (pc && pc.localDescription && pc.localDescription.type === 'offer' ? 'answer' : 'offer') : 'answer'), sdp: msg };
        onRemoteSdp(desc);
        return;
      }

      // 2) Object-based
      if (msg && typeof msg === 'object') {
        // Nested { sdp: {type, sdp} } or flat { type, sdp }
        if (msg.sdp && (typeof msg.sdp === 'string' || (msg.sdp.type && msg.sdp.sdp))) {
          var d = (typeof msg.sdp === 'string')
            ? { type: (msg.type || 'answer'), sdp: msg.sdp }
            : { type: (msg.sdp.type || 'answer'), sdp: msg.sdp.sdp };
          onRemoteSdp(d);
          return;
        }
        if (msg.type && msg.sdp) {
          onRemoteSdp({ type: msg.type, sdp: msg.sdp });
          return;
        }
        // ICE candidate(s): flat or nested, single or array
        if (msg.candidate || msg.candidates) {
          var arr = [];
          if (msg.candidates && Array.isArray(msg.candidates)) arr = msg.candidates;
          else arr = [msg];

          arr.forEach(function(c){
            var cand = c.candidate || c; // string or object
            var init = (typeof cand === 'string')
              ? { candidate: cand, sdpMid: c.sdpMid || 'audio', sdpMLineIndex: c.sdpMLineIndex || 0 }
              : cand;
            try { pc && pc.addIceCandidate(new RTCIceCandidate(init)); } catch(e){ log('[VC] addIceCandidate error', e); }
          });
          return;
        }
        if (msg.cmd === 'bye' || msg.bye) {
          stopAll(false); return;
        }
        if (msg.needOffer || msg.cmd === 'need-offer') {
          // server explicitly wants an offer
          sendOffer();
          return;
        }
      }
    } catch (e) {
      log('[VC] signaling parse error', e);
    }
  }

  function onRemoteSdp(desc){
    if (!pc) { log('[VC] no pc yet for remote SDP'); return; }
    pc.setRemoteDescription(new RTCSessionDescription(desc)).then(function(){
      log('[VC] setRemoteDescription OK', desc.type);
      if (desc.type === 'offer') {
        pc.createAnswer().then(function(ans){
          return pc.setLocalDescription(ans);
        }).then(function(){
          sendAnswer();
        }).catch(function(e){ log('[VC] answer error', e); });
      } else {
        // got answer → we are connected or about to connect
      }
    }).catch(function(e){
      log('[VC] setRemoteDescription error', e);
    });
  }

  // ---------- WebRTC ----------
  function beginWebRtc(){
    if (!ws || ws.readyState !== 1) { log('[VC] ws not open, abort start'); return; }

    var conf = { iceServers: [] };
    // sdpSemantics unified-plan by default in modern browsers
    pc = new RTCPeerConnection(conf);

    // >>> Добавлены диагностические логи ICE <<<
    pc.addEventListener('iceconnectionstatechange', ()=>console.log('[VC] ice:', pc.iceConnectionState));
    pc.addEventListener('icegatheringstatechange', ()=>console.log('[VC] iceGather:', pc.iceGatheringState));
    // <<< конец вставки >>>

    try { pc.addTransceiver('audio', { direction: 'recvonly' }); } catch(_){}

    pc.onicecandidate = function(ev){
      if (ev.candidate && ws && ws.readyState === 1) {
        log('[VC] local ICE cand:', ev.candidate.candidate);
        // send flat candidate; server may also accept nested — but flat is broadly compatible
        ws.send(JSON.stringify({
          candidate: ev.candidate.candidate,
          sdpMid: ev.candidate.sdpMid,
          sdpMLineIndex: ev.candidate.sdpMLineIndex
        }));
      }
    };

    pc.onconnectionstatechange = function(){
      log('[VC] pc state:', pc.connectionState);
      if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        setStatus(texts.t('pc_failed'));
        stopAll(false);
      }
    };

    pc.ontrack = function(ev){
      log('[VC] ontrack', ev && ev.streams && ev.streams[0]);
      try {
        if (ev.streams && ev.streams[0]) {
          audioEl.srcObject = ev.streams[0];
          audioEl.play().catch(function(e){ log('[VC] audio play failed', e); });
        } else if (ev.track) {
          var ms = new MediaStream([ev.track]);
          audioEl.srcObject = ms;
          audioEl.play().catch(function(e){ log('[VC] audio play failed', e); });
        } else {
          setStatus(texts.t('no_audio'));
        }
      } catch (e) {
        log('[VC] attach audio error', e);
        setStatus(texts.t('no_audio'));
      }
      state = 'connected';
      setBtn();
      setStatus();
      cancelReofferTimer();
    };

    // Immediately send an OFFER (browser as offerer) — most common flow
    sendOffer();

    // If сервер молчит долго, переотправим оффер (на случай потери первого сообщения)
    reofferTimer = setTimeout(function(){
      if (state === 'connecting' && ws && ws.readyState === 1) {
        log('[VC] re-sending offer after timeout');
        sendOffer();
      }
    }, 4000);
  }

  function sendOffer(){
    if (!pc) return;
    pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: false }).then(function(offer){
      return pc.setLocalDescription(offer);
    }).then(function(){
      if (!ws || ws.readyState !== 1) return;
      // Отправляем один оффер (flat)
      ws.send(JSON.stringify({ type: pc.localDescription.type, sdp: pc.localDescription.sdp }));
    }).catch(function(e){
      log('[VC] createOffer error', e);
      setStatus(texts.t('status_error'));
      stopAll(false);
    });
  }

  function sendAnswer(){
    if (!pc || !pc.localDescription) return;
    if (!ws || ws.readyState !== 1) return;
    // Сохраним совместимость: шлём и nested, и flat (не критично, сервер игнорирует)
    ws.send(JSON.stringify({ sdp: pc.localDescription }));
    ws.send(JSON.stringify({ type: pc.localDescription.type, sdp: pc.localDescription.sdp }));
  }

  // ---------- Initialize UI ----------
  setBtn();
  setStatus();

})();

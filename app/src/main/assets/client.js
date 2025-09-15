/* VishnuCast client — recv-only audio; single click handler; UI styles injected; no meter; RU/EN i18n */
(function () {
  'use strict';

  // ---------- Inject minimal UI CSS (hover/active/focus, spinner, connected color) ----------
  (function injectStyles(){
    var css = [
      ':root{--vc-btn:#2563eb;--vc-btn-hover:#1d4ed8;--vc-btn-on:#16a34a;--vc-fg:#fff}',
      '#btn{appearance:none;-webkit-appearance:none;display:inline-flex;align-items:center;gap:8px;',
      '  padding:16px 16px;border:none;border-radius:12px;background:var(--vc-btn);color:var(--vc-fg);',
      '  font-weight:600;font-size:16px;line-height:1;user-select:none;cursor:pointer;',
      '  box-shadow:0 1px 0 rgba(0,0,0,.05),0 6px 12px rgba(37,99,235,.25);',
      '  transition:transform .12s ease,box-shadow .2s ease,background-color .2s ease,opacity .2s ease;',
      '  -webkit-tap-highlight-color: rgba(0,0,0,.06)}',
      '#btn:hover:not(:disabled){transform:translateY(-1px);box-shadow:0 2px 0 rgba(0,0,0,.05),0 8px 16px rgba(37,99,235,.25)}',
      '#btn:active:not(:disabled){transform:translateY(0);box-shadow:0 1px 0 rgba(0,0,0,.05),0 4px 10px rgba(37,99,235,.25)}',
      '#btn:focus-visible{outline:none;box-shadow:0 0 0 3px rgba(37,99,235,.35),0 6px 12px rgba(37,99,235,.25)}',
      '#btn:disabled{opacity:.6;cursor:not-allowed}',
      /* visual states via data-state */
      '#btn[data-state="connected"]{background:var(--vc-btn-on);box-shadow:0 1px 0 rgba(0,0,0,.05),0 6px 12px rgba(22,163,74,.25)}',
      '#btn[data-state="connecting"]{background:var(--vc-btn-hover);position:relative}',
      '#btn[data-state="connecting"]::after{content:"";display:inline-block;width:14px;height:14px;margin-left:8px;',
      '  border:2px solid rgba(255,255,255,.6);border-top-color:#fff;border-radius:50%;animation:vc-spin .9s linear infinite}',
      '@keyframes vc-spin{to{transform:rotate(360deg)}}',
      '@media (prefers-reduced-motion:reduce){#btn{transition:none}#btn[data-state="connecting"]::after{animation:none}}'
    ].join('');
    var s = document.createElement('style');
    s.type = 'text/css'; s.appendChild(document.createTextNode(css));
    document.head ? document.head.appendChild(s) : document.documentElement.appendChild(s);
  })();

  // ---------- DOM ----------
  function $(sel) { return document.querySelector(sel); }
  var btn = $('#btn');
  var statusEl = $('#status');
  var audioEl = $('#audio');
  var langRuBtn = $('#lang-ru');
  var langEnBtn = $('#lang-en');

  if (!btn) {
    btn = document.createElement('button');
    btn.id = 'btn';
    btn.textContent = 'Подключить';
    document.body.insertBefore(btn, document.body.firstChild);
  }
  if (!statusEl) {
    statusEl = document.createElement('p');
    statusEl.id = 'status';
    statusEl.className = 'status-text';
    statusEl.textContent = 'Статус: не подключено';
    btn.parentNode ? btn.parentNode.insertBefore(statusEl, btn.nextSibling) : document.body.appendChild(statusEl);
  }
  if (!audioEl) {
    audioEl = document.createElement('audio');
    audioEl.id = 'audio';
    document.body.appendChild(audioEl);
  }
  audioEl.setAttribute('autoplay', '');
  audioEl.setAttribute('playsinline', '');
  audioEl.style.display = 'none';

  // ---------- i18n ----------
  var LS_KEY = 'vishnucast.lang';
  var TEXTS = {
    ru: {
      btn_connect: 'Подключить звук',
      btn_disconnect: 'Отключить',
      connecting: 'Подключение…',
      connected: 'Подключено',
      disconnected: 'Отключено',
      ws_closed: 'Сигналинг закрыт',
      err: 'Ошибка',
      status_prefix: 'Статус: '
    },
    en: {
      btn_connect: 'Connect audio',
      btn_disconnect: 'Disconnect',
      connecting: 'Connecting…',
      connected: 'Connected',
      disconnected: 'Disconnected',
      ws_closed: 'Signaling closed',
      err: 'Error',
      status_prefix: 'Status: '
    }
  };

  function detectInitialLang() {
    try {
      var saved = localStorage.getItem(LS_KEY);
      if (saved === 'ru' || saved === 'en') return saved;
    } catch(_) {}
    var nav = (navigator.language || navigator.userLanguage || 'en').toLowerCase();
    return nav.indexOf('ru') === 0 ? 'ru' : 'en';
  }

  var currentLang = detectInitialLang();
  var texts = TEXTS[currentLang];

  function applyLangToUi() {
    try {
      // Кнопка
      if (state === 'connected') btn.textContent = texts.btn_disconnect;
      else if (state === 'connecting') btn.textContent = texts.connecting;
      else btn.textContent = texts.btn_connect;

      // Статус (оставляем только префикс; конкретное значение обновляется через setStatus)
      if (statusEl && typeof statusEl.textContent === 'string') {
        var suffix = statusEl.textContent.split(':').slice(1).join(':').trim();
        if (!suffix) suffix = (state === 'connected' ? texts.connected :
                               state === 'connecting' ? texts.connecting :
                               texts.disconnected);
        statusEl.textContent = texts.status_prefix + suffix;
      }

      // Подсветка активной кнопки языка
      if (langRuBtn) langRuBtn.setAttribute('aria-current', String(currentLang === 'ru'));
      if (langEnBtn) langEnBtn.setAttribute('aria-current', String(currentLang === 'en'));
      // <html lang=...> (необязательно)
      try { document.documentElement.lang = currentLang; } catch(_) {}
    } catch(_) {}
  }

  function setLang(lang) {
    currentLang = (lang === 'ru') ? 'ru' : 'en';
    texts = TEXTS[currentLang];
    try { localStorage.setItem(LS_KEY, currentLang); } catch(_) {}
    applyLangToUi();
  }

  if (langRuBtn) langRuBtn.addEventListener('click', function(){ setLang('ru'); });
  if (langEnBtn) langEnBtn.addEventListener('click', function(){ setLang('en'); });

  // ---------- Status / Button helpers ----------
  function setStatusTextCore(s) {
    statusEl.textContent = texts.status_prefix + s;
  }
  function setStatus(s) { setStatusTextCore(s); }
  function enableBtn()  { btn.disabled = false; btn.style.pointerEvents = 'auto'; }
  function disableBtn() { btn.disabled = true; }
  function setBtnState(st){ btn.setAttribute('data-state', st); }
  function log(){ try{ console.log.apply(console, ['[VishnuCast]'].concat([].slice.call(arguments))); }catch(_){} }

  // ---------- State ----------
  var pc = null;
  var ws = null;
  var state = 'idle'; // idle | connecting | connected | error
  var userStopped = false;
  var pendingRemoteCandidates = [];

  // ---------- URL helpers ----------
  function buildWsPath() {
    try {
      var url = new URL(location.href);
      var qp = url.searchParams.get('wspath');
      if (qp) return qp.charAt(0) === '/' ? qp : '/' + qp;
    } catch (_) {}
    return '/ws';
  }
  function buildWsUrl() {
    var proto = (location.protocol === 'https:') ? 'wss' : 'ws';
    return proto + '://' + location.host + buildWsPath();
  }

  // ---------- Cleanup ----------
  function safeCloseWs() { try { if (ws) ws.close(); } catch (e) {} ws = null; }
  function safeClosePc() { try { if (pc) pc.close(); } catch (e) {} pc = null; }
  function resetBuffers() { pendingRemoteCandidates = []; }

  function stopAll(manual) {
    if (manual == null) manual = false;
    userStopped = manual;
    safeCloseWs();
    safeClosePc();
    resetBuffers();
    state = 'idle';
    setBtn();
    setStatus(texts.disconnected);
    log('Disconnected');
  }
  window.addEventListener('beforeunload', function () { stopAll(false); });

  // ---------- ICE helpers ----------
  function normalizeRemoteCandidate(msg) {
    var cStr = null, mid = null, mline = null;
    if (msg == null || typeof msg !== 'object') return null;

    if (typeof msg.candidate === 'string') {
      cStr = msg.candidate; mid = (typeof msg.sdpMid !== 'undefined') ? msg.sdpMid : null;
      mline = (typeof msg.sdpMLineIndex === 'number') ? msg.sdpMLineIndex : 0;
    } else if (typeof msg.sdp === 'string' && msg.sdp.indexOf('candidate:') === 0) {
      cStr = msg.sdp; mid = (typeof msg.sdpMid !== 'undefined') ? msg.sdpMid : null;
      mline = (typeof msg.sdpMLineIndex === 'number') ? msg.sdpMLineIndex : 0;
    } else if (msg.candidate && typeof msg.candidate === 'object' && typeof msg.candidate.candidate === 'string') {
      cStr = msg.candidate.candidate;
      mid = (typeof msg.candidate.sdpMid !== 'undefined') ? msg.candidate.sdpMid : null;
      mline = (typeof msg.candidate.sdpMLineIndex === 'number') ? msg.candidate.sdpMLineIndex : 0;
    } else if (msg.ice && typeof msg.ice === 'object') {
      if (typeof msg.ice.candidate === 'string') {
        cStr = msg.ice.candidate;
        mid = (typeof msg.ice.sdpMid !== 'undefined') ? msg.ice.sdpMid : null;
        mline = (typeof msg.ice.sdpMLineIndex === 'number') ? msg.ice.sdpMLineIndex : 0;
      } else if (msg.ice.candidate && typeof msg.ice.candidate === 'object' && typeof msg.ice.candidate.candidate === 'string') {
        cStr = msg.ice.candidate.candidate;
        mid = (typeof msg.ice.candidate.sdpMid !== 'undefined') ? msg.ice.candidate.sdpMid : null;
        mline = (typeof msg.ice.candidate.sdpMLineIndex === 'number') ? msg.ice.candidate.sdpMLineIndex : 0;
      }
    }
    if (!cStr || typeof cStr !== 'string' || cStr.trim() === '') return null;
    var init = { candidate: cStr };
    if (mid != null) init.sdpMid = mid;
    if (typeof mline === 'number') init.sdpMLineIndex = mline;
    return init;
  }
  function canAddRemoteCandidate() {
    try { return !!(pc && pc.remoteDescription && pc.remoteDescription.type); } catch (_) { return false; }
  }
  function drainPendingCandidates() {
    if (!canAddRemoteCandidate() || !pendingRemoteCandidates.length) return;
    var queue = pendingRemoteCandidates.slice(); pendingRemoteCandidates = [];
    for (var i = 0; i < queue.length; i++) {
      (function (cand) {
        pc.addIceCandidate(cand).catch(function (e) { log('addIceCandidate (drain) error:', e, cand); });
      })(queue[i]);
    }
  }

  // ---------- Core connect (НЕ МЕНЯЕМ ПРОТОКОЛ) ----------
  function setBtn() {
    if (state === 'connecting') {
      btn.textContent = texts.connecting;
      setBtnState('connecting');
      disableBtn();
    } else if (state === 'connected') {
      btn.textContent = texts.btn_disconnect;
      setBtnState('connected');
      enableBtn();
    } else { // idle/error
      btn.textContent = texts.btn_connect;
      setBtnState('idle');
      enableBtn();
    }
  }

  function connectOnce() {
    if (state === 'connecting' || state === 'connected') return;

    userStopped = false;
    state = 'connecting';
    setBtn();
    setStatus(texts.connecting);
    resetBuffers();

    try {
      pc = new RTCPeerConnection({ iceServers: [] });
    } catch (e) {
      log('RTCPeerConnection error:', e);
      state = 'error'; setBtn(); setStatus(texts.err + ': RTCPeerConnection');
      return;
    }

    try { if (pc.addTransceiver) pc.addTransceiver('audio', { direction: 'recvonly' }); } catch (e) { log('addTransceiver error:', e); }

    pc.ontrack = function (ev) {
      try {
        var stream = (ev.streams && ev.streams[0]) ? ev.streams[0] : null;
        if (stream) {
          audioEl.srcObject = stream;
          var p = audioEl.play(); if (p && p.catch) p.catch(function () {});
        }
      } catch (e) { log('ontrack error:', e); }
    };

    pc.onicecandidate = function (ev) {
      try {
        if (ev && ev.candidate && ws && ws.readyState === 1) { // 1=OPEN
          var c = ev.candidate;
          ws.send(JSON.stringify({
            type: 'ice',
            sdp: c.candidate,
            sdpMid: c.sdpMid || null,
            sdpMLineIndex: (typeof c.sdpMLineIndex === 'number') ? c.sdpMLineIndex : 0
          }));
        }
      } catch (e) { log('onicecandidate send error:', e); }
    };

    var url = buildWsUrl();
    log('WS →', url);
    try { ws = new WebSocket(url); }
    catch (e) { log('WebSocket ctor error:', e); state='error'; setBtn(); setStatus(texts.err + ': WS'); stopAll(false); return; }

    ws.onopen = function () {
      pc.createOffer({ offerToReceiveAudio: true }).then(function (offer) {
        return pc.setLocalDescription(offer).then(function () {
          ws.send(JSON.stringify({ type: 'offer', sdp: offer.sdp }));
        });
      }).catch(function (e) {
        log('Offer flow error:', e);
        state = 'error'; setBtn(); setStatus(texts.err + ': offer'); stopAll(false);
      });
    };

    ws.onmessage = function (ev) {
      try {
        var msg = JSON.parse(ev.data);

        if (msg && msg.type === 'answer' && typeof msg.sdp === 'string') {
          pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp }).then(function () {
            drainPendingCandidates();
            state = 'connected'; setBtn(); setStatus(texts.connected);
          }).catch(function (e) { log('setRemoteDescription error:', e); });
          return;
        }

        if (msg && (msg.type === 'ice' || msg.type === 'candidate' || msg.ice)) {
          var candInit = normalizeRemoteCandidate(msg);
          if (!candInit) return;
          if (canAddRemoteCandidate()) {
            pc.addIceCandidate(candInit).catch(function (e) { log('addIceCandidate error:', e, candInit); });
          } else {
            pendingRemoteCandidates.push(candInit);
          }
          return;
        }
      } catch (e) { log('WS parse error:', e, ev && ev.data); }
    };

    ws.onclose = function(){ log('WS close'); if (!userStopped) setStatus(texts.ws_closed); stopAll(false); };
    ws.onerror = function(e){ log('WS error:', e); /* onclose сделает stopAll */ };
  }

  // ---------- Toggle ----------
  btn.addEventListener('click', function (ev) {
    ev.preventDefault();
    var p = audioEl.play(); if (p && p.catch) p.catch(function () {});
    if (state === 'connected' || state === 'connecting') stopAll(true);
    else connectOnce();
  });

  // ---------- Init ----------
  // Установим язык (автодетект / сохранённый), применим к UI
  setLang(currentLang);
  // Для статуса — вывесим "disconnected"
  setStatus(TEXTS[currentLang].disconnected);
  setBtn();
  enableBtn();

  // export for debug
  window.VishnuCastClient = { connect: connectOnce, stop: function(){ stopAll(true); } };
})();

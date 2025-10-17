/* VishnuCast client — dual PC (MIC + PLAYER), recv-only audio, single-button UI */
(function () {
  'use strict';

  // ---------- logger (opt-in) ----------
  var LOG = (function(){
    var on = false;
    try { var q = new URLSearchParams(location.search||''); if (q.get('log')==='1') on = true; } catch(_){}
    try { if (localStorage.getItem('vishnucast.log') === '1') on = true; } catch(_){}
    try { if (typeof window.__vc_log === 'boolean') on = on || !!window.__vc_log; } catch(_){}
    function ts(){ var d=new Date(),z=n=>String(n).padStart(2,'0'); return d.getFullYear()+'-'+z(d.getMonth()+1)+'-'+z(d.getDate())+' '+z(d.getHours())+':'+z(d.getMinutes())+':'+z(d.getSeconds())+'.'+String(d.getMilliseconds()).padStart(3,'0'); }
    function log(){ if(!on) return; try{ console.log.apply(console,['[VishnuCast] '+ts()].concat([].slice.call(arguments))); }catch(_){} }
    return { on:on, log:log };
  })(); var log = LOG.log;

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
      '#btn .spin{width:16px;height:16px;border-radius:50%;border:2px solid rgba(255,255,255,.5);border-top-color:#fff;animation:vc-spin .9s linear infinite}',
      '@keyframes vc-spin{to{transform:rotate(360deg)}}',
      '#status.connected{color:#059669}',
      '#a{display:none}'
    ].join('\n');
    var st=document.createElement('style'); st.type='text/css'; st.appendChild(document.createTextNode(css)); document.head.appendChild(st);
  })();

  // ---------- i18n ----------
  var texts=(function(){
    var isRu=(navigator.language||'').toLowerCase().startsWith('ru');
    try{ var stored=localStorage.getItem('vishnucast.lang'); if(stored==='en') isRu=false; if(stored==='ru') isRu=true; }catch(_){}
    var dict={
      ru:{btn_connect:'Подключить',btn_disconnect:'Отключить',status_idle:'Готов',status_connecting:'Подключение…',status_connected:'Подключено',status_error:'Ошибка',lang_ru:'RU',lang_en:'EN'},
      en:{btn_connect:'Connect',btn_disconnect:'Disconnect',status_idle:'Ready',status_connecting:'Connecting…',status_connected:'Connected',status_error:'Error',lang_ru:'RU',lang_en:'EN'}
    };
    function t(k){return (isRu?dict.ru:dict.en)[k]||k;}
    function setLang(l){ if(l==='ru') isRu=true; else if(l==='en') isRu=false; try{localStorage.setItem('vishnucast.lang',isRu?'ru':'en');}catch(_){}; apply(); }
    function apply(){ var btn=document.getElementById('btn'),status=document.getElementById('status'),ru=document.getElementById('lang-ru'),en=document.getElementById('lang-en');
      if(btn) btn.textContent=(state==='connected'?t('btn_disconnect'):t('btn_connect'));
      if(status) status.textContent=t('status_idle');
      if(ru) ru.textContent=t('lang_ru'); if(en) en.textContent=t('lang_en'); }
    return {t:t,setLang:setLang,apply:apply};
  })();

  // ---------- DOM ----------
  var btn=document.getElementById('btn');
  var statusEl=document.getElementById('status');
  var audioEl=document.getElementById('a');
  var langRuBtn=document.getElementById('lang-ru');
  var langEnBtn=document.getElementById('lang-en');

  function ensureAudioEl(){
    if(audioEl&&audioEl.nodeName==='AUDIO') return audioEl;
    var el=document.getElementById('a');
    if(!el||el.nodeName!=='AUDIO'){ el=document.createElement('audio'); el.id='a'; el.setAttribute('playsinline',''); el.setAttribute('autoplay',''); el.style.display='none'; document.body.appendChild(el); log('Created <audio id="a">'); }
    audioEl=el; return audioEl;
  }

  // ---------- State ----------
  var state='idle'; // 'idle'|'connecting'|'connected'
  var userStopped=false, stopping=false;

  var pcMic=null, pcPlayer=null;
  var wsMic=null, wsPlayer=null;

  var mix={ alpha:0.0, micMuted:false };
  var ac=null, gainMic=null, gainPlayer=null, srcMic=null, srcPlayer=null, mixOut=null;
  var currentOutStream=null;
  var micStream=null, playerStream=null;
  var playerTrackMuted=true;

  function ensureMixer(){
    try{
      ensureAudioEl();
      ac=window.__vc_ac||ac;
      if(!ac&&(window.AudioContext||window.webkitAudioContext)){ var AC=window.AudioContext||window.webkitAudioContext; ac=new AC(); window.__vc_ac=ac; }
      if(!ac) return false;
      try{ if(ac.state==='suspended') ac.resume(); }catch(_){}
      if(!gainMic){ gainMic=ac.createGain(); gainMic.gain.value=1.0; }
      if(!gainPlayer){ gainPlayer=ac.createGain(); gainPlayer.gain.value=0.0; }
      if(!mixOut){ mixOut=ac.createMediaStreamDestination(); gainMic.connect(mixOut); gainPlayer.connect(mixOut); }
      updateGains();
      return true;
    }catch(e){ log('ensureMixer error:',e&&e.message); return false; }
  }

  function updateGains(){
    if(!gainMic||!gainPlayer) return;
    var a=Math.max(0,Math.min(1,Number(mix.alpha)||0));
    gainPlayer.gain.value=a;
    gainMic.gain.value=mix.micMuted?0:(1-a);

    try{
      ensureAudioEl();
      var bothPresent=!!micStream&&!!playerStream;
      var useMix=bothPresent && !playerTrackMuted && (mix.micMuted || a>0);
      var target = (useMix && mixOut) ? mixOut.stream
        : (mix.micMuted ? (playerStream||micStream||audioEl.srcObject) : (micStream||playerStream||audioEl.srcObject));
      if(target && currentOutStream!==target){
        audioEl.muted=false; audioEl.volume=1.0; audioEl.srcObject=target; currentOutStream=target;
        log('Audio route (by gains):', useMix?'MIX':'DIRECT', 'alpha=',a.toFixed(2),'micMuted=',!!mix.micMuted);
        var tries=0;(function kick(){ audioEl.play().catch(function(err){ tries++; if(tries<5) setTimeout(kick,300); }); })();
      }
    }catch(e){ log('updateGains route error:', e&&e.message); }
  }

  // ---------- Lang switches ----------
  (function initLang(){
    if(langRuBtn) langRuBtn.addEventListener('click', function(){ texts.setLang('ru'); setBtn(); setStatus(); });
    if(langEnBtn) langEnBtn.addEventListener('click', function(){ texts.setLang('en'); setBtn(); setStatus(); });
    texts.apply();
  })();

  // ---------- Button ----------
  if(btn){
    btn.addEventListener('click', function(){
      if(state==='connected' || state==='connecting'){ stopAll(true); }
      else { start(); }
    });
  }

  function setStatus(txt){
    if(!statusEl) return;
    if(txt==null){ txt=(state==='connected')?texts.t('status_connected'):(state==='connecting'?texts.t('status_connecting'):texts.t('status_idle')); }
    statusEl.textContent=txt; statusEl.className=(state==='connected')?'connected':'';
  }
  function setBtn(){
    if(!btn) return;
    btn.disabled=(state==='connecting');
    btn.textContent=(state==='connected'?texts.t('btn_disconnect'):texts.t('btn_connect'));
    btn.style.backgroundColor=(state==='connected'?'var(--vc-btn-on)':'var(--vc-btn)');
  }

  function resetBuffers(){
    try{ ensureAudioEl(); audioEl.pause(); audioEl.removeAttribute('src'); audioEl.srcObject=null; }catch(_){}
    try{ if(srcMic){ srcMic.disconnect(); srcMic=null; } }catch(_){}
    try{ if(srcPlayer){ srcPlayer.disconnect(); srcPlayer=null; } }catch(_){}
    micStream=null; playerStream=null; currentOutStream=null; playerTrackMuted=true;
  }

  function stopAll(byUser){
    if(stopping) return; stopping=true; userStopped=!!byUser;
    log('Stopping…');

    // Close PCs
    try{ if(pcMic){ pcMic.ontrack=null; pcMic.onicecandidate=null; if(pcMic.signalingState!=='closed') pcMic.close(); } }catch(_){}
    try{ if(pcPlayer){ pcPlayer.ontrack=null; pcPlayer.onicecandidate=null; if(pcPlayer.signalingState!=='closed') pcPlayer.close(); } }catch(_){}
    pcMic=null; pcPlayer=null;

    // Close WS
    try{ if(wsMic && wsMic.readyState===1) wsMic.close(); }catch(_){}
    try{ if(wsPlayer && wsPlayer.readyState===1) wsPlayer.close(); }catch(_){}
    wsMic=null; wsPlayer=null;

    // Reset audio
    resetBuffers();

    state='idle'; setBtn(); setStatus(texts.t('ws_closed'));
    stopping=false;
  }

  function start(){
    if(state!=='idle'){ log('start: wrong state', state); return; }
    state='connecting'; setBtn(); setStatus();
    ensureAudioEl(); resetBuffers(); ensureMixer();

    connectMic();     // PC #1
    connectPlayer();  // PC #2
  }

  // ---------- MIC PC ----------
  function connectMic(){
    var proto=(location.protocol==='https:'?'wss':'ws'); var url=proto+'://'+location.host+'/ws';
    wsMic=new WebSocket(url);
    wsMic.onopen=function(){ log('WS MIC open'); beginPcMic(); };
    wsMic.onmessage=function(ev){ handleSignalMic(ev.data); };
    wsMic.onerror=function(e){ log('WS MIC error', e); };
    wsMic.onclose=function(){ log('WS MIC close'); };
  }

  function beginPcMic(){
    pcMic=new RTCPeerConnection({iceServers:[]});
    pcMic.addTransceiver('audio',{direction:'recvonly'});
    pcMic.onicecandidate=function(ev){
      if(ev.candidate && wsMic && wsMic.readyState===1){
        var c=ev.candidate; wsMic.send(JSON.stringify({candidate:c.candidate,sdpMid:c.sdpMid,sdpMLineIndex:c.sdpMLineIndex}));
      }
    };
    pcMic.ontrack=function(ev){
      var stream=(ev.streams&&ev.streams[0])?ev.streams[0]:(ev.track?new MediaStream([ev.track]):null);
      if(!stream) return;
      try{
        ensureMixer();
        if(srcMic){ try{srcMic.disconnect();}catch(_){}} srcMic=ac.createMediaStreamSource(stream); srcMic.connect(gainMic);
        micStream=stream;
        updateGains(); routeOutput();
      }catch(e){ log('MIC attach error:', e&&e.message); }
      maybeMarkConnected();
    };
    // Offer
    pcMic.createOffer({offerToReceiveAudio:true,offerToReceiveVideo:false}).then(function(of){
      return pcMic.setLocalDescription(of);
    }).then(function(){
      wsMic && wsMic.readyState===1 && wsMic.send(JSON.stringify({type:pcMic.localDescription.type,sdp:pcMic.localDescription.sdp}));
    }).catch(function(e){ log('MIC offer error:', e&&e.message); });
  }

  function handleSignalMic(raw){
    var msg=tryParse(raw);
    if(isSdpString(msg)){ onRemoteSdpMic({type:'answer',sdp:msg}); return; }
    if(msg && msg.type && msg.sdp){ onRemoteSdpMic({type:msg.type,sdp:msg.sdp}); return; }
    if(msg && (msg.candidate||msg.candidates)){
      var arr=(msg.candidates&&Array.isArray(msg.candidates))?msg.candidates:(msg.candidate?[msg]:[]);
      arr.forEach(function(c){
        var init=(typeof c.candidate==='string')?{candidate:c.candidate,sdpMid:c.sdpMid||'audio',sdpMLineIndex:c.sdpMLineIndex||0}:c;
        pcMic && pcMic.addIceCandidate(new RTCIceCandidate(init)).catch(function(e){ log('MIC addIce error:',e&&e.message); });
      });
      return;
    }
    if(msg && msg.type==='pong'){ return; }
  }

  function onRemoteSdpMic(desc){
    if(!pcMic) return;
    pcMic.setRemoteDescription(new RTCSessionDescription(desc)).catch(function(e){ log('MIC setRemote error:', e&&e.message); });
  }

  // ---------- PLAYER PC ----------
  function connectPlayer(){
    var proto=(location.protocol==='https:'?'wss':'ws'); var url=proto+'://'+location.host+'/ws_player';
    wsPlayer=new WebSocket(url);
    wsPlayer.onopen=function(){ log('WS PLAYER open'); beginPcPlayer(); };
    wsPlayer.onmessage=function(ev){ handleSignalPlayer(ev.data); };
    wsPlayer.onerror=function(e){ log('WS PLAYER error', e); };
    wsPlayer.onclose=function(){ log('WS PLAYER close'); };
  }

  function beginPcPlayer(){
    pcPlayer=new RTCPeerConnection({iceServers:[]});
    pcPlayer.addTransceiver('audio',{direction:'recvonly'});
    pcPlayer.onicecandidate=function(ev){
      if(ev.candidate && wsPlayer && wsPlayer.readyState===1){
        var c=ev.candidate; wsPlayer.send(JSON.stringify({candidate:c.candidate,sdpMid:c.sdpMid,sdpMLineIndex:c.sdpMLineIndex}));
      }
    };
    pcPlayer.ontrack=function(ev){
      var stream=(ev.streams&&ev.streams[0])?ev.streams[0]:(ev.track?new MediaStream([ev.track]):null);
      if(!stream) return;
      try{
        ensureMixer();
        if(srcPlayer){ try{srcPlayer.disconnect();}catch(_){}} srcPlayer=ac.createMediaStreamSource(stream); srcPlayer.connect(gainPlayer);
        playerStream=stream;

        // детектировать реальный mute трека
        try{
          if(ev.track){
            playerTrackMuted=!!ev.track.muted;
            ev.track.onmute=function(){ playerTrackMuted=true; updateGains(); routeOutput(); };
            ev.track.onunmute=function(){ playerTrackMuted=false; updateGains(); routeOutput(); };
          }else{
            var t=(stream.getAudioTracks&&stream.getAudioTracks()[0])||null;
            if(t){
              playerTrackMuted=!!t.muted;
              t.onmute=function(){ playerTrackMuted=true; updateGains(); routeOutput(); };
              t.onunmute=function(){ playerTrackMuted=false; updateGains(); routeOutput(); };
            }
          }
        }catch(_){}

        updateGains(); routeOutput();
      }catch(e){ log('PLAYER attach error:', e&&e.message); }
      maybeMarkConnected();
    };
    // Offer
    pcPlayer.createOffer({offerToReceiveAudio:true,offerToReceiveVideo:false}).then(function(of){
      return pcPlayer.setLocalDescription(of);
    }).then(function(){
      wsPlayer && wsPlayer.readyState===1 && wsPlayer.send(JSON.stringify({type:pcPlayer.localDescription.type,sdp:pcPlayer.localDescription.sdp}));
    }).catch(function(e){ log('PLAYER offer error:', e&&e.message); });
  }

  function handleSignalPlayer(raw){
    var msg=tryParse(raw);
    if(isSdpString(msg)){ onRemoteSdpPlayer({type:'answer',sdp:msg}); return; }
    if(msg && msg.type && msg.sdp){ onRemoteSdpPlayer({type:msg.type,sdp:msg.sdp}); return; }
    if(msg && (msg.candidate||msg.candidates)){
      var arr=(msg.candidates&&Array.isArray(msg.candidates))?msg.candidates:(msg.candidate?[msg]:[]);
      arr.forEach(function(c){
        var init=(typeof c.candidate==='string')?{candidate:c.candidate,sdpMid:c.sdpMid||'audio',sdpMLineIndex:c.sdpMLineIndex||0}:c;
        pcPlayer && pcPlayer.addIceCandidate(new RTCIceCandidate(init)).catch(function(e){ log('PLAYER addIce error:',e&&e.message); });
      });
      return;
    }
    if(msg && msg.type==='pong'){ return; }
  }

  function onRemoteSdpPlayer(desc){
    if(!pcPlayer) return;
    pcPlayer.setRemoteDescription(new RTCSessionDescription(desc)).catch(function(e){ log('PLAYER setRemote error:', e&&e.message); });
  }

  // ---------- common helpers ----------
  function tryParse(raw){ if(typeof raw==='string'){ try{ return JSON.parse(raw); }catch(_){ return raw; } } return raw; }
  function isSdpString(s){ return typeof s==='string' && (s.startsWith('v=0') || s.indexOf('\nm=audio')>=0 || s.indexOf('\na=')>=0); }

  function routeOutput(){
    try{
      ensureAudioEl();
      var both=!!micStream && !!playerStream;
      var a=Number(mix.alpha)||0;
      var useMix = both && !playerTrackMuted && (mix.micMuted || a>0);
      var target = (useMix && mixOut) ? mixOut.stream
        : (mix.micMuted ? (playerStream||micStream||audioEl.srcObject) : (micStream||playerStream||audioEl.srcObject));
      if(target && currentOutStream!==target){
        audioEl.muted=false; audioEl.volume=1.0; audioEl.srcObject=target; currentOutStream=target;
        var tries=0;(function kick(){ audioEl.play().catch(function(){ if(++tries<5) setTimeout(kick,300); }); })();
        log('Audio route:', useMix?'MIX':'DIRECT', 'alpha=', a.toFixed(2), 'micMuted=', !!mix.micMuted, 'both=', both);
      }
    }catch(e){ log('routeOutput error:', e&&e.message); }
  }

  function maybeMarkConnected(){
    // считаем «подключено», когда пришёл хотя бы один трек (любой)
    if(state!=='connected'){
      state='connected'; setBtn(); setStatus();
    }
  }

  // ---------- external MIX apply via WS broadcast ----------
  // (остаётся совместимость с существующим протоколом)
  function applyMixFromServer(msg){
    if(typeof msg.alpha==='number') mix.alpha=msg.alpha;
    if(typeof msg.micMuted==='boolean') mix.micMuted=msg.micMuted;
    updateGains(); routeOutput();
    log('Mix apply:', 'alpha=', mix.alpha, 'micMuted=', mix.micMuted);
  }

  // перехват широковещательного сообщения mix
  (function hijackGlobalMix(){
    var _orig = window.__vc_on_mix_broadcast;
    window.__vc_on_mix_broadcast = function(payload){
      try { if (payload && payload.type==='mix') applyMixFromServer(payload); } catch(_){}
      if (typeof _orig === 'function') { try { _orig(payload); } catch(_){ } }
    };
  })();

  // ---------- init ----------
  texts.apply(); setBtn(); setStatus(); ensureAudioEl();

})();

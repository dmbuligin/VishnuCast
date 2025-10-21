/* VishnuCast client — dual PC (MIC + PLAYER), recv-only audio, single-button UI */
(function () {
  'use strict';

  // -------- logger --------
  var LOG_ON = false;
  try { var q = new URLSearchParams(location.search||''); if (q.get('log')==='1') LOG_ON = true; } catch(_){}
  try { if (localStorage.getItem('vishnucast.log')==='1') LOG_ON = true; } catch(_){}
  function ts(){ var d=new Date(); function z(n){return String(n).padStart(2,'0');} return d.getFullYear()+'-'+z(d.getMonth()+1)+'-'+z(d.getDate())+' '+z(d.getHours())+':'+z(d.getMinutes())+':'+z(d.getSeconds())+'.'+String(d.getMilliseconds()).padStart(3,'0'); }
  function log(){ if(!LOG_ON) return; try{ var args=[ '[VishnuCast]', ts() ]; for(var i=0;i<arguments.length;i++) args.push(arguments[i]); console.log.apply(console,args); }catch(_){ } }

  // -------- state --------
  var wsMic=null, wsPlayer=null;
  var pcMic=null, pcPlayer=null;
  var ac=null, micGain=null, playerGain=null;
  var state='idle';
  var micMuted=false;
  var alpha01=0.0;

  // -------- UI --------
  function btn(){ return document.getElementById('btn'); }
  function statusEl(){ return document.getElementById('status'); }

  function setBtn(){
    var b=btn(); if(!b) return;
    b.disabled = (state==='connecting');
    b.textContent = (state==='connected') ? (isRu()?'Отключить':'Disconnect') : (isRu()?'Подключить':'Connect');
  }
  function setStatus(text){
    var s=statusEl(); if(!s) return;
    if(!text){ text=(state==='connected') ? (isRu()?'Подключено':'Connected') : (isRu()?'Готово':'Ready'); }
    s.textContent = text;
    s.className = (state==='connected') ? 'connected' : '';
  }

  function isRu(){ try{ var st=localStorage.getItem('vishnucast.lang'); if(st==='ru') return true; if(st==='en') return false; }catch(_){}
    return (navigator.language||'').toLowerCase().startsWith('ru');
  }

  // -------- audio/mixer --------
  function ensureAudio(){
    if(ac) return;
    ac = new (window.AudioContext||window.webkitAudioContext)();
    var dst = ac.destination;
    micGain = ac.createGain(); micGain.gain.value = 1.0;
    playerGain = ac.createGain(); playerGain.gain.value = 0.0;
    micGain.connect(dst); playerGain.connect(dst);
    log('AudioContext created');
  }

  function updateGains(){
    if(!micGain || !playerGain) return;
    var micLevel = micMuted ? 0.0 : (1.0 - alpha01);
    var playerLevel = alpha01;
    micGain.gain.value = micLevel;
    playerGain.gain.value = playerLevel;
    log('Audio route:', (alpha01<=0.02?'MIC':alpha01>=0.98?'PLAYER':'MIX'), 'alpha=', alpha01.toFixed(2), 'micMuted=', micMuted);
  }

  function setAlpha(a){ alpha01 = Math.max(0, Math.min(1, Number(a)||0)); updateGains(); }
  function setMicMuted(m){ micMuted = !!m; updateGains(); }

  function applyMixFromServer(msg){
    if(typeof msg.alpha === 'number') setAlpha(msg.alpha);
    if(typeof msg.micMuted === 'boolean') setMicMuted(msg.micMuted);
  }

  // -------- PC helpers --------
  function addRemoteTrackTo(nodeGain, ev){
    try{
      var stream = ev.streams && ev.streams[0];
      if(!stream || !ac) return;
      var src = ac.createMediaStreamSource(stream);
      src.connect(nodeGain);
      log('ontrack: connected stream to gain');
    }catch(e){ log('ontrack error:', e && e.message); }
  }

  function sendIce(ws, c){
    if(ws && ws.readyState===1 && c){
      try { ws.send(JSON.stringify({ type:'ice', candidate:{ candidate:c.candidate, sdpMid:c.sdpMid, sdpMLineIndex:c.sdpMLineIndex } })); }
      catch(e){ log('send ice error:', e && e.message); }
    }
  }

  function handleSignalCommon(pc, raw){
    var msg; try { msg = JSON.parse(raw); } catch(_){ msg = raw; }
    if(typeof msg === 'string'){ // some servers send plain SDP
      pc.setRemoteDescription({type:'answer', sdp: msg}).catch(function(e){ log('setRemote error:', e && e.message); });
      return;
    }
    if(msg && msg.type==='answer' && msg.sdp){
      pc.setRemoteDescription({type:'answer', sdp: msg.sdp}).catch(function(e){ log('setRemote error:', e && e.message); });
      return;
    }
    if(msg && msg.type==='ice'){
      var c = msg.candidate || msg;
      try { pc.addIceCandidate(new RTCIceCandidate(c)); } catch(e){ log('addIce error:', e && e.message); }
      return;
    }
    if(msg && msg.candidate){
      try { pc.addIceCandidate(new RTCIceCandidate(msg)); } catch(e){ log('addIce(flat) error:', e && e.message); }
      return;
    }
    if(msg && msg.type==='mix'){
      applyMixFromServer(msg);
      return;
    }
    if(msg && msg.type==='pong') return;
  }

  // -------- MIC --------
  function connectMic(){
    var proto=(location.protocol==='https:'?'wss':'ws'), url=proto+'://'+location.host+'/ws';
    wsMic = new WebSocket(url);
    wsMic.onopen = function(){
      log('WS MIC open');
      try{ wsMic.send(JSON.stringify({type:'ping', t:Date.now()})); }catch(_){}
      beginPcMic();
    };
    wsMic.onmessage = function(ev){ handleSignalCommon(pcMic, ev.data); };
    wsMic.onerror = function(e){ log('WS MIC error', e); };
    wsMic.onclose = function(){ log('WS MIC close'); };
  }

  function beginPcMic(){
    pcMic = new RTCPeerConnection({ iceServers: [] });
    pcMic.addTransceiver('audio', { direction:'recvonly' });
    pcMic.onconnectionstatechange = function(){ log('[MIC] state:', pcMic.connectionState); if(pcMic.connectionState==='connected') maybeMarkConnected(); };
    pcMic.onicecandidate = function(ev){ if(ev.candidate) sendIce(wsMic, ev.candidate); };
    pcMic.ontrack = function(ev){ ensureAudio(); addRemoteTrackTo(micGain, ev); };

    pcMic.createOffer({ offerToReceiveAudio:true }).then(function(of){
      return pcMic.setLocalDescription(of);
    }).then(function(){
      if(wsMic && wsMic.readyState===1){
        wsMic.send(JSON.stringify({ type:'offer', sdp: pcMic.localDescription.sdp }));
        log('[MIC] offer sent:', (pcMic.localDescription.sdp||'').length, 'bytes');
      }
    }).catch(function(e){ log('MIC offer error:', e && e.message); });
  }

  // -------- PLAYER --------
  function connectPlayer(){
    var proto=(location.protocol==='https:'?'wss':'ws'), url=proto+'://'+location.host+'/ws_player';
    wsPlayer = new WebSocket(url);
    wsPlayer.onopen = function(){
      log('WS PLAYER open');
      try{ wsPlayer.send(JSON.stringify({type:'ping', t:Date.now()})); }catch(_){}
      beginPcPlayer();
    };
    wsPlayer.onmessage = function(ev){ handleSignalCommon(pcPlayer, ev.data); };
    wsPlayer.onerror = function(e){ log('WS PLAYER error', e); };
    wsPlayer.onclose = function(){ log('WS PLAYER close'); };
  }

  function beginPcPlayer(){
    pcPlayer = new RTCPeerConnection({ iceServers: [] });
    pcPlayer.addTransceiver('audio', { direction:'recvonly' });
    pcPlayer.onconnectionstatechange = function(){ log('[PLAYER] state:', pcPlayer.connectionState); if(pcPlayer.connectionState==='connected') maybeMarkConnected(); };
    pcPlayer.onicecandidate = function(ev){ if(ev.candidate) sendIce(wsPlayer, ev.candidate); };
    pcPlayer.ontrack = function(ev){ ensureAudio(); addRemoteTrackTo(playerGain, ev); };

    pcPlayer.createOffer({ offerToReceiveAudio:true }).then(function(of){
      return pcPlayer.setLocalDescription(of);
    }).then(function(){
      if(wsPlayer && wsPlayer.readyState===1){
        wsPlayer.send(JSON.stringify({ type:'offer', sdp: pcPlayer.localDescription.sdp }));
        log('[PLAYER] offer sent:', (pcPlayer.localDescription.sdp||'').length, 'bytes');
      }
    }).catch(function(e){ log('PLAYER offer error:', e && e.message); });
  }

  function maybeMarkConnected(){
    if(state==='connecting'){ state='connected'; setBtn(); setStatus(); }
  }

  // -------- Connect / Disconnect --------
  function start(){
    if(state!=='idle'){ log('start: wrong state', state); return; }
    state='connecting'; setBtn(); setStatus();
    ensureAudio();
    if(ac.state==='suspended'){ ac.resume().catch(function(){}); }
    connectMic();
    connectPlayer();
  }

  function stop(){
    state='idle'; setBtn(); setStatus();
    try{ if(wsMic) wsMic.close(); }catch(_){}
    try{ if(wsPlayer) wsPlayer.close(); }catch(_){}
    try{ if(pcMic) pcMic.close(); }catch(_){}
    try{ if(pcPlayer) pcPlayer.close(); }catch(_){}
    wsMic=wsPlayer=pcMic=pcPlayer=null;
  }

  // bind button
  document.addEventListener('click', function(e){
    if(e.target && e.target.id==='btn'){
      if(state==='connected' || state==='connecting'){ stop(); }
      else { start(); }
    }
  }, false);

  // hijack global broadcast helper (server may call this)
  (function(){
    var orig = window.__vc_on_mix_broadcast;
    window.__vc_on_mix_broadcast = function(payload){
      try { if(payload && payload.type==='mix') applyMixFromServer(payload); } catch(_){}
      if(typeof orig === 'function'){ try{ orig(payload); }catch(_){ } }
    };
  })();

  // initial UI
  setBtn(); setStatus();
})();

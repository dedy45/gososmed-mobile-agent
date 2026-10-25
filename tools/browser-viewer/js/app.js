/**
 * GoSosmed Remote Browser Viewer — App Lifecycle & Networking
 * Handles WebSocket lifecycle, auto-reconnect, JSON/binary packet dispatch, and telemetry logs.
 */
(() => {
  const statusDot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const wsUrlInput = document.getElementById('wsUrlInput');
  const selectResolution = document.getElementById('selectResolution');
  const btnConnect = document.getElementById('btnConnect');
  const btnDisconnect = document.getElementById('btnDisconnect');
  const terminalLog = document.getElementById('terminalLog');
  const valRes = document.getElementById('valRes');
  const phoneFrame = document.getElementById('phoneFrame');

  let ws = null;
  let manualDisconnect = false;
  let reconnectTimer = null;

  // Auto-detect dynamic WS host based on current page address (zero hardcode)
  const currentHost = window.location.host;
  const wsProto = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
  if (wsUrlInput) {
    if (currentHost) {
      wsUrlInput.value = `${wsProto}//${currentHost}/ws`;
    } else {
      const saved = localStorage.getItem('goso_remote_ws');
      const defaultHost = window.location.hostname || '127.0.0.1';
      const defaultPort = window.location.port || '8765';
      wsUrlInput.value = saved || `${wsProto}//${defaultHost}:${defaultPort}/ws`;
    }
  }

  // ---------------------------------------------------------------------
  // Log box: buffered before DOM-ready, capped, with severity counters
  // ---------------------------------------------------------------------
  const LOG_MAX = 500;
  const logBuffer = [];
  const logCounts = { info: 0, success: 0, warn: 0, error: 0 };

  function logBadge() {
    const el = document.getElementById('logErrorCount');
    if (!el) return;
    el.textContent = `${logCounts.error} err / ${logCounts.warn} warn`;
    el.className = logCounts.error > 0 ? 'log-badge has-error'
      : (logCounts.warn > 0 ? 'log-badge has-warn' : 'log-badge');
  }

  function appendLogLine(msg, type) {
    if (!terminalLog) return false;
    const line = document.createElement('div');
    line.className = `log-line ${type}`;
    const d = new Date();
    const time = d.toTimeString().substring(0, 8) + '.' +
      String(d.getMilliseconds()).padStart(3, '0');
    line.textContent = `[${time}] ${msg}`;
    terminalLog.appendChild(line);
    while (terminalLog.childElementCount > LOG_MAX) {
      terminalLog.removeChild(terminalLog.firstElementChild);
    }
    terminalLog.scrollTop = terminalLog.scrollHeight;
    return true;
  }

  function log(msg, type = 'info') {
    if (!(type in logCounts)) type = 'info';
    logCounts[type]++;
    logBadge();
    if (!appendLogLine(msg, type)) {
      logBuffer.push([msg, type]);
      if (logBuffer.length > LOG_MAX) logBuffer.shift();
    }
  }

  function flushLogBuffer() {
    if (!terminalLog) return;
    while (logBuffer.length) {
      const [msg, type] = logBuffer.shift();
      appendLogLine(msg, type);
    }
  }

  function clearLog() {
    logBuffer.length = 0;
    if (terminalLog) terminalLog.textContent = '';
    logCounts.info = logCounts.success = logCounts.warn = logCounts.error = 0;
    logBadge();
  }

  // ---------------------------------------------------------------------
  // Global error capture — nothing may fail silently in the browser again
  // ---------------------------------------------------------------------
  window.addEventListener('error', (ev) => {
    if (ev.target && ev.target !== window && ev.target.tagName) {
      // Resource load failure (img/script/css)
      log(`Gagal memuat resource: <${ev.target.tagName.toLowerCase()}> ${ev.target.src || ev.target.href || ''}`, 'error');
      return;
    }
    const where = ev.filename ? ` @ ${ev.filename.split('/').pop()}:${ev.lineno}:${ev.colno}` : '';
    log(`JS Error: ${ev.message}${where}`, 'error');
  }, true);

  window.addEventListener('unhandledrejection', (ev) => {
    const r = ev.reason;
    const msg = (r && (r.stack || r.message)) ? (r.message || r.stack) : String(r);
    log(`Unhandled Promise: ${msg}`, 'error');
  });

  // Console.error passthrough so library-level failures surface in the UI
  const nativeConsoleError = console.error.bind(console);
  console.error = (...args) => {
    nativeConsoleError(...args);
    try {
      log('console.error: ' + args.map(a => (a && a.message) ? a.message : String(a)).join(' '), 'error');
    } catch (_) {}
  };

  function setConnected(connected) {
    if (connected) {
      if (statusDot) statusDot.className = 'status-dot active';
      if (statusText) statusText.textContent = 'Live Connected';
      if (btnConnect) btnConnect.style.display = 'none';
      if (btnDisconnect) btnDisconnect.style.display = 'inline-flex';
    } else {
      if (statusDot) statusDot.className = 'status-dot';
      if (statusText) statusText.textContent = 'Disconnected';
      if (btnConnect) btnConnect.style.display = 'inline-flex';
      if (btnDisconnect) btnDisconnect.style.display = 'none';
      if (window.GoStream && typeof window.GoStream.reset === 'function') {
        window.GoStream.reset();
      }
    }
  }

  function sendControl(cmd) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(cmd);
    }
  }

  function connectWebSocket() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    if (!wsUrlInput) return;
    const url = wsUrlInput.value.trim();
    if (!url) return;
    try { localStorage.setItem('goso_remote_ws', url); } catch (_) {}
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }

    log(`Menghubungkan ke ${url}...`, 'info');
    try {
      ws = new WebSocket(url);
      ws.binaryType = 'arraybuffer';

      ws.onopen = () => {
        log('Koneksi WebSocket lokal berhasil tersambung.', 'success');
        setConnected(true);
        flushLogBuffer();
        // Request instant keyframe & initial resolution sync
        sendControl(JSON.stringify({ cmd: 'requestKeyFrame' }));
        const w = (selectResolution ? parseInt(selectResolution.value) : 720) || 720;
        const kbps = w >= 1080 ? 5000 : (w >= 720 ? 2500 : 1200);
        sendControl(JSON.stringify({ cmd: 'setResolution', width: w, kbps: kbps, fps: 30 }));
        // Unmute by default: audio starts as soon as a gesture unlocks AudioContext
        if (window.GoStream) {
          window.GoStream.initAudioContext();
          window.GoStream.isAudioMuted = false;
          sendControl(JSON.stringify({ cmd: 'startAudio' }));
          const btn = document.getElementById('btnAudioToggle');
          if (btn) { btn.textContent = '🔊'; btn.classList.add('active'); }
          log('Audio HP diaktifkan otomatis (44.1 kHz PCM stereo).', 'success');
        }
      };

      ws.onclose = () => {
        log('Koneksi WebSocket terputus.', 'warn');
        setConnected(false);
        ws = null;
        if (!manualDisconnect) {
          log('Mencoba menyambung ulang otomatis dalam 1.5 detik...', 'info');
          reconnectTimer = setTimeout(connectWebSocket, 1500);
        }
      };

      ws.onerror = () => {
        log('Kesalahan koneksi WebSocket.', 'error');
      };

      ws.onmessage = async (event) => {
        // JSON text protocol
        if (typeof event.data === 'string') {
          try {
            const msg = JSON.parse(event.data);
            if (msg.type === 'status') {
              if (!msg.captureActive) {
                log('CaptureService HP standby. Nyalakan switch Remote Controller di HP.', 'warn');
                const tip = document.getElementById('streamWaitingTip');
                if (tip) {
                  const s = tip.querySelector('strong');
                  if (s) s.textContent = 'Menunggu Switch di HP...';
                }
              } else {
                log(`HP terhubung: ${msg.model || 'Android'} (Capture Aktif)`, 'success');
              }
            } else if (msg.type === 'capture_error') {
              log(`Capture error: ${msg.message || msg.reason}`, 'error');
              if (msg.reason === 'projection_invalid') {
                const tip = document.getElementById('streamWaitingTip');
                if (tip) {
                  tip.style.display = 'flex';
                  const s = tip.querySelector('strong');
                  if (s) s.textContent = 'Izin tangkapan layar kedaluwarsa';
                }
                if (window.GoStream) window.GoStream.reset();
              } else {
                log('Menunggu frame berikutnya dari HP...', 'warn');
              }
            } else if (msg.type === 'frame_meta') {
              if (valRes) valRes.textContent = `${msg.width}x${msg.height}`;
              if (msg.width && msg.height) {
                const aspect = (msg.width / msg.height).toFixed(4);
                document.documentElement.style.setProperty('--stream-aspect', aspect);
                if (phoneFrame) phoneFrame.classList.toggle('landscape', msg.width > msg.height);
              }
            }
          } catch (_) {}
          return;
        }

        // Binary video/image/audio stream
        if (event.data instanceof ArrayBuffer) {
          const data = new Uint8Array(event.data);
          if (!data.length) return;

          const t = data[0];
          if (t === 3) { // MSG_CONFIG (SPS+PPS)
            if (window.GoStream) window.GoStream.onConfig(data);
          } else if (t === 4) { // MSG_FRAME (NALU)
            if (window.GoStream) window.GoStream.onFrame(data);
          } else if (t === 5) { // MSG_AUDIO (PCM 16-bit 44100Hz Stereo)
            if (window.GoStream) window.GoStream.playPcmAudio(data.subarray(1));
          } else if (t === 1 || (data[0] === 0xFF && data[1] === 0xD8)) { // JPEG
            if (window.GoStream) window.GoStream.onJpeg(data);
          }
        }
      };
    } catch (err) {
      log(`Gagal memulai koneksi: ${err.message}`, 'error');
    }
  }

  if (btnConnect) {
    btnConnect.addEventListener('click', () => {
      manualDisconnect = false;
      connectWebSocket();
    });
  }

  if (btnDisconnect) {
    btnDisconnect.addEventListener('click', () => {
      manualDisconnect = true;
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
      if (ws) ws.close();
    });
  }

  if (selectResolution) {
    selectResolution.addEventListener('change', () => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        const w = parseInt(selectResolution.value) || 720;
        const kbps = w >= 1080 ? 5000 : (w >= 720 ? 2500 : 1200);
        log(`Mengubah resolusi streaming ke ${w}p (${kbps} Kbps)...`, 'info');
        sendControl(JSON.stringify({ cmd: 'setResolution', width: w, kbps: kbps, fps: 30 }));
        sendControl(JSON.stringify({ cmd: 'requestKeyFrame' }));
      }
    });
  }

  function checkHttpsUpgrade() {
    const hasWebCodecs = ('VideoDecoder' in window);
    if (!hasWebCodecs && location.protocol === 'http:' && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') {
      const banner = document.getElementById('httpsUpgradeBanner');
      if (banner) {
        banner.style.display = 'flex';
        const port = parseInt(location.port || '8765') + 1;
        const httpsUrl = `https://${location.hostname}:${port}/`;
        const btn = document.getElementById('btnUpgradeHttps');
        if (btn) {
          btn.onclick = () => { window.location.href = httpsUrl; };
        }
        banner.onclick = () => { window.location.href = httpsUrl; };
      }
    }
  }

  // ---------------------------------------------------------------------
  // First-gesture unlock: browsers suspend AudioContext until a user
  // gesture. Unlock on ANY first interaction so audio starts immediately.
  // ---------------------------------------------------------------------
  let gestureUnlocked = false;
  function unlockAudioOnce() {
    if (gestureUnlocked) return;
    gestureUnlocked = true;
    if (window.GoStream) {
      window.GoStream.initAudioContext();
      const ctx = window.GoStream.audioCtx;
      if (ctx && ctx.state === 'suspended') {
        ctx.resume().then(() => log('AudioContext ter-unlock oleh gesture.', 'success'))
          .catch((e) => log(`AudioContext unlock gagal: ${e.message}`, 'warn'));
      }
    }
    window.removeEventListener('pointerdown', unlockAudioOnce, true);
    window.removeEventListener('keydown', unlockAudioOnce, true);
    window.removeEventListener('touchstart', unlockAudioOnce, true);
  }
  window.addEventListener('pointerdown', unlockAudioOnce, true);
  window.addEventListener('keydown', unlockAudioOnce, true);
  window.addEventListener('touchstart', unlockAudioOnce, true);

  // Auto-connect on page load
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      flushLogBuffer();
      checkHttpsUpgrade();
      setTimeout(connectWebSocket, 150);
    });
  } else {
    flushLogBuffer();
    checkHttpsUpgrade();
    setTimeout(connectWebSocket, 150);
  }

  // Handle bfcache (Back/Forward cache) and page visibility transitions
  window.addEventListener('pageshow', (event) => {
    if (event.persisted || !window.GoApp || !window.GoApp.isConnected()) {
      checkHttpsUpgrade();
      setTimeout(connectWebSocket, 150);
    }
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && (!window.GoApp || !window.GoApp.isConnected())) {
      setTimeout(connectWebSocket, 150);
    }
  });

  // Global Export
  window.GoApp = {
    sendControl,
    log,
    flushLogBuffer,
    clearLog,
    setConnected,
    connectWebSocket,
    checkHttpsUpgrade,
    get logCounts() { return logCounts; },
    get ws() { return ws; },
    isConnected() { return ws && ws.readyState === WebSocket.OPEN; }
  };
})();

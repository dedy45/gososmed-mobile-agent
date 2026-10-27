/**
 * GoSosmed Remote Browser Viewer — Interactive Touch & Controls
 * Handles touch gestures, mouse wheel, softkeys, keyboard injection, recording, and toolbar actions.
 */
(() => {
  function sendControl(cmd) {
    if (window.GoApp && typeof window.GoApp.sendControl === 'function') {
      window.GoApp.sendControl(cmd);
    }
  }

  function log(msg, type = 'info') {
    if (window.GoApp && typeof window.GoApp.log === 'function') {
      window.GoApp.log(msg, type);
    } else {
      console.log(`[${type}] ${msg}`);
    }
  }

  // =========================================================================
  // Interactive Touch & Mouse Control (vitRemote 1:1 Proven - Discrete Mode)
  // Sends ONLY discrete t:/l:/s: commands on pointerup.
  // NEVER sends d:/m:/u: from browser to avoid dual-mechanism conflicts.
  // =========================================================================
  const screenViewport = document.getElementById('screenViewport');
  const phoneFrame = document.getElementById('phoneFrame');

  function curEl() {
    if (window.GoStream && typeof window.GoStream.curEl === 'function') {
      return window.GoStream.curEl();
    }
    const cv = document.getElementById('screenCanvas');
    const img = document.getElementById('screenImg');
    const vid = document.getElementById('screenVideo');
    if (cv && cv.style.display !== 'none') return cv;
    if (img && img.style.display !== 'none') return img;
    return vid || screenViewport;
  }

  function pos(e) {
    const el = curEl();
    const nw = el.videoWidth || el.naturalWidth || el.width || 1;
    const nh = el.videoHeight || el.naturalHeight || el.height || 1;
    const r = screenViewport ? screenViewport.getBoundingClientRect() : { left: 0, top: 0, width: 1, height: 1 };
    const s = Math.min(r.width / nw, r.height / nh);
    const w = nw * s;
    const h = nh * s;
    const l = r.left + (r.width - w) / 2;
    const t = r.top + (r.height - h) / 2;
    return [
      Math.round(Math.min(1, Math.max(0, (e.clientX - l) / w)) * 10000),
      Math.round(Math.min(1, Math.max(0, (e.clientY - t) / h)) * 10000),
      e.clientX, e.clientY
    ];
  }

  let down = null;
  let wheelT = 0;
  let mouseEnabled = true;

  // Initialize cursor state
  if (screenViewport) screenViewport.classList.add('mouse-active');

  // =========================================================================
  // Mouse Toggle Button (🖱️ in sidebar)
  // =========================================================================
  const btnToggleMouse = document.getElementById('btnToggleMouse');
  if (btnToggleMouse) {
    btnToggleMouse.addEventListener('click', () => {
      mouseEnabled = !mouseEnabled;
      btnToggleMouse.classList.toggle('active', mouseEnabled);
      if (screenViewport) {
        screenViewport.classList.toggle('mouse-active', mouseEnabled);
        screenViewport.classList.toggle('mouse-disabled', !mouseEnabled);
      }
      log(`Kontrol Mouse: ${mouseEnabled ? 'Aktif (Tap/Swipe/Scroll)' : 'Nonaktif'}`, 'info');
    });
  }

  // Touch ripple visual feedback (iMyFone production pattern)
  function showRipple(clientX, clientY) {
    if (!screenViewport) return;
    const rect = screenViewport.getBoundingClientRect();
    const ripple = document.createElement('div');
    ripple.className = 'touch-ripple';
    ripple.style.left = `${clientX - rect.left}px`;
    ripple.style.top = `${clientY - rect.top}px`;
    screenViewport.appendChild(ripple);
    setTimeout(() => ripple.remove(), 350);
  }

  if (screenViewport) {
    screenViewport.addEventListener('pointerdown', (e) => {
      if (e.button || !mouseEnabled) return;
      e.preventDefault();
      try { screenViewport.setPointerCapture(e.pointerId); } catch (_) {}
      const p = pos(e);
      down = [...p, performance.now()];
      showRipple(e.clientX, e.clientY);
    });

    screenViewport.addEventListener('pointermove', (_e) => {
      // No-op: discrete mode buffers until pointerup
    });

    screenViewport.addEventListener('pointerup', (e) => {
      if (!down || !mouseEnabled) return;
      const p = pos(e);
      const dt = Math.round(performance.now() - down[4]);
      const moved = Math.hypot(p[2] - down[2], p[3] - down[3]) > 8;

      if (!moved) {
        if (dt > 450) {
          sendControl(`l:${down[0]},${down[1]}`);
          log(`Long Press: (${down[0]}, ${down[1]})`, 'info');
        } else {
          sendControl(`t:${down[0]},${down[1]}`);
          log(`Tap: (${down[0]}, ${down[1]})`, 'info');
        }
      } else {
        const dur = Math.min(2000, Math.max(80, dt));
        sendControl(`s:${down[0]},${down[1]},${p[0]},${p[1]},${dur}`);
        log(`Swipe: (${down[0]},${down[1]}) -> (${p[0]},${p[1]}) [${dur}ms]`, 'info');
      }
      down = null;
    });

    screenViewport.addEventListener('pointercancel', () => { down = null; });

    // Right-Click -> Android Back
    screenViewport.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      sendControl('a:back');
      log('Right Click -> Android Back', 'info');
    });

    // Mouse Wheel Scroll
    screenViewport.addEventListener('wheel', (e) => {
      if (!mouseEnabled) return;
      e.preventDefault();
      const n = performance.now();
      if (n - wheelT < 250) return;
      wheelT = n;
      const p = pos(e);
      const d = e.deltaY > 0 ? -2000 : 2000;
      sendControl(`s:${p[0]},${p[1]},${p[0]},${Math.min(9500, Math.max(500, p[1] + d))},200`);
      log(`Wheel Scroll: ${d > 0 ? 'Down' : 'Up'}`, 'info');
    }, { passive: false });
  }

  // =========================================================================
  // Android Softkeys & Sidebar Actions
  // =========================================================================
  const btnNavBack = document.getElementById('btnNavBack');
  if (btnNavBack) {
    btnNavBack.addEventListener('click', () => {
      sendControl('back');
      log('Sent: Back', 'info');
    });
  }

  const btnNavHome = document.getElementById('btnNavHome');
  if (btnNavHome) {
    btnNavHome.addEventListener('click', () => {
      sendControl('home');
      log('Sent: Home', 'info');
    });
  }

  const btnNavRecent = document.getElementById('btnNavRecent');
  if (btnNavRecent) {
    btnNavRecent.addEventListener('click', () => {
      sendControl('recents');
      log('Sent: Recents', 'info');
    });
  }

  const btnNavLock = document.getElementById('btnNavLock');
  if (btnNavLock) {
    btnNavLock.addEventListener('click', () => {
      sendControl('power');
      log('Sent: Lock Screen', 'info');
    });
  }

  const btnNavPower = document.getElementById('btnNavPower');
  if (btnNavPower) {
    btnNavPower.addEventListener('click', () => {
      sendControl('wake');
      log('Sent: Power / Wake Screen', 'info');
    });
  }

  let isLandscape = false;
  const btnRotateScreen = document.getElementById('btnRotateScreen');
  if (btnRotateScreen) {
    btnRotateScreen.addEventListener('click', () => {
      isLandscape = !isLandscape;
      if (phoneFrame) phoneFrame.classList.toggle('landscape', isLandscape);
      document.body.classList.toggle('landscape-mode', isLandscape);
      if (isLandscape) {
        if (fileManagerPanel) fileManagerPanel.style.display = 'none';
        const tel = document.getElementById('telemetryPanel');
        if (tel) tel.style.display = 'none';
        if (btnFileManager) btnFileManager.classList.remove('active');
      }
      sendControl('rotate');
      log(`Sent: Rotate (${isLandscape ? 'Landscape (Panel Otomatis Sembunyi)' : 'Portrait'})`, 'info');
    });
  }

  const btnScreenshot = document.getElementById('btnScreenshot');
  if (btnScreenshot) {
    btnScreenshot.addEventListener('click', () => {
      try {
        const cv = (window.GoStream && window.GoStream.cv) || document.getElementById('screenCanvas');
        const vid = (window.GoStream && window.GoStream.vid) || document.getElementById('screenVideo');
        const link = document.createElement('a');
        link.download = `gososmed-screen-${Date.now()}.png`;
        if (cv && cv.style.display !== 'none') {
          link.href = cv.toDataURL('image/png');
        } else if (vid && vid.style.display !== 'none') {
          const tempCv = document.createElement('canvas');
          tempCv.width = vid.videoWidth || 720;
          tempCv.height = vid.videoHeight || 1600;
          tempCv.getContext('2d').drawImage(vid, 0, 0);
          link.href = tempCv.toDataURL('image/png');
        }
        link.click();
        log('Screenshot berhasil diunduh.', 'success');
      } catch (err) {
        log(`Screenshot gagal: ${err.message}`, 'error');
      }
    });
  }

  const btnRestScreen = document.getElementById('btnRestScreen');
  if (btnRestScreen) {
    btnRestScreen.addEventListener('click', () => {
      sendControl('power');
      log('Sent: Rest Screen (Layar HP Padam)', 'info');
    });
  }

  const btnVolUp = document.getElementById('btnVolUp');
  if (btnVolUp) {
    btnVolUp.addEventListener('click', () => {
      sendControl('volume_up');
      log('Sent: Volume Up (keyevent 24)', 'info');
    });
  }

  const btnVolDown = document.getElementById('btnVolDown');
  if (btnVolDown) {
    btnVolDown.addEventListener('click', () => {
      sendControl('volume_down');
      log('Sent: Volume Down (keyevent 25)', 'info');
    });
  }

  const btnFullscreen = document.getElementById('btnFullscreen');
  if (btnFullscreen) {
    btnFullscreen.addEventListener('click', () => {
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
      } else {
        document.exitFullscreen().catch(() => {});
      }
    });
  }

  const btnTelemetryToggle = document.getElementById('btnTelemetryToggle');
  if (btnTelemetryToggle) {
    btnTelemetryToggle.addEventListener('click', () => {
      const panel = document.getElementById('telemetryPanel');
      if (panel) {
        panel.style.display = (panel.style.display === 'none') ? 'flex' : 'none';
      }
    });
  }

  // =========================================================================
  // Internal Phone Audio Streaming Toggle
  // =========================================================================
  const btnAudioToggle = document.getElementById('btnAudioToggle');
  const valAudio = document.getElementById('valAudio');
  const audioMeter = document.getElementById('audioMeter');

  function paintAudioState() {
    const muted = window.GoStream ? window.GoStream.isAudioMuted : true;
    if (btnAudioToggle) {
      btnAudioToggle.textContent = muted ? '🔈' : '🔊';
      btnAudioToggle.classList.toggle('active', !muted);
    }
    if (valAudio) {
      const st = window.GoStream && window.GoStream.audioStats;
      if (muted) valAudio.textContent = 'Muted';
      else if (st && st.frames > 0) valAudio.textContent = `${(st.frames / 44100).toFixed(1)}s PCM`;
      else valAudio.textContent = 'Menunggu data';
    }
  }

  // Drive the level meter from the audio engine
  if (window.GoStream) {
    window.GoStream.onAudioLevel = (peak) => {
      if (!audioMeter) return;
      const pct = Math.min(100, Math.round(Math.sqrt(peak) * 100));
      audioMeter.style.width = `${pct}%`;
      if (valAudio) {
        const st = window.GoStream.audioStats;
        valAudio.textContent = `${(st.frames / 44100).toFixed(1)}s • ${(peak * 100).toFixed(0)}%`;
      }
    };
  }

  if (btnAudioToggle) {
    btnAudioToggle.addEventListener('click', () => {
      if (window.GoStream) window.GoStream.initAudioContext();
      const currentMuted = window.GoStream ? window.GoStream.isAudioMuted : true;
      const newMuted = !currentMuted;
      if (window.GoStream) window.GoStream.isAudioMuted = newMuted;
      paintAudioState();
      sendControl(JSON.stringify({ cmd: newMuted ? 'stopAudio' : 'startAudio' }));
      const st = window.GoStream && window.GoStream.audioStats;
      const detail = st && st.lastError ? ` — ${st.lastError}` : '';
      log(`Audio HP: ${newMuted ? 'Muted' : 'Aktif (44.1 kHz PCM stereo)'}${detail}`,
        st && st.errors > 0 ? 'warn' : 'info');
    });
  }

  const btnClearLog = document.getElementById('btnClearLog');
  if (btnClearLog) {
    btnClearLog.addEventListener('click', () => {
      if (window.GoApp && window.GoApp.clearLog) window.GoApp.clearLog();
      log('Log dibersihkan.', 'info');
    });
  }

  paintAudioState();

  // Periodic audio diagnostics so a dead pipeline is never invisible
  setInterval(() => {
    if (!window.GoStream) return;
    const st = window.GoStream.audioStats;
    if (!st || st.packets === 0) return;
    const muted = window.GoStream.isAudioMuted;
    if (muted) return;
    if (st.frames === 0 && st.errors === 0 && st.dropped > 30) {
      log(`Audio menunggu gesture browser (dropped=${st.dropped}). Klik sekali di area layar.`, 'warn');
      st.dropped = 0;
    }
    if (st.errors > 0 && st.lastError) {
      log(`Audio error berulang: ${st.lastError}`, 'error');
      st.errors = 0;
    }
  }, 4000);

  // =========================================================================
  // Client-Side Screen Recording (HTML5 MediaRecorder 60 FPS)
  // =========================================================================
  let mediaRecorder = null;
  let recordedChunks = [];
  let isRecording = false;

  const btnRecordScreen = document.getElementById('btnRecordScreen');
  if (btnRecordScreen) {
    btnRecordScreen.addEventListener('click', () => {
      if (!isRecording) {
        if (window.GoStream && typeof window.GoStream.initAudioContext === 'function') {
          window.GoStream.initAudioContext();
        }
        const cv = (window.GoStream && window.GoStream.cv) || document.getElementById('screenCanvas');
        const vid = (window.GoStream && window.GoStream.vid) || document.getElementById('screenVideo');
        const activeSurface = (cv && cv.style.display !== 'none') ? cv : vid;
        if (!activeSurface) {
          log('Tidak ada surface streaming aktif untuk merekam', 'error');
          return;
        }
        let stream = null;
        try {
          stream = activeSurface.captureStream ? activeSurface.captureStream(60) : activeSurface.mozCaptureStream(60);
        } catch (_) {}

        if (!stream) {
          log('captureStream tidak didukung browser ini', 'error');
          return;
        }

        const audioStreamDest = window.GoStream ? window.GoStream.audioStreamDest : null;
        if (audioStreamDest && audioStreamDest.stream && audioStreamDest.stream.getAudioTracks().length > 0) {
          audioStreamDest.stream.getAudioTracks().forEach(t => stream.addTrack(t));
        }

        recordedChunks = [];
        try {
          mediaRecorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8,opus' });
        } catch (_) {
          try {
            mediaRecorder = new MediaRecorder(stream, { mimeType: 'video/webm' });
          } catch (_) {
            mediaRecorder = new MediaRecorder(stream);
          }
        }

        mediaRecorder.ondataavailable = (e) => {
          if (e.data.size > 0) recordedChunks.push(e.data);
        };

        mediaRecorder.onstop = () => {
          const blob = new Blob(recordedChunks, { type: 'video/webm' });
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `GoSosmed_Record_${Date.now()}.webm`;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          setTimeout(() => URL.revokeObjectURL(url), 5000);
          log('Rekaman layar selesai dan diunduh ke PC!', 'success');
        };

        mediaRecorder.start(1000);
        isRecording = true;
        btnRecordScreen.classList.add('recording');
        log('Merekam layar dimulai (60 FPS)...', 'info');
      } else {
        if (mediaRecorder && mediaRecorder.state !== 'inactive') {
          mediaRecorder.stop();
        }
        isRecording = false;
        btnRecordScreen.classList.remove('recording');
      }
    });
  }

  // =========================================================================
  // Game Keymapping Overlay (WASD Joystick + Actions)
  // =========================================================================
  // =========================================================================
  // Game Keymapping Overlay — dynamic, draggable, resizable, persisted
  // Strategy mirrored from DeskIn `presetkeyboard_deskin_*.json` +
  // `GameKeyBoardActivity` (per-key x/y/size/label persisted) and iMyFone's
  // virtual-keyboard overlay. Keys are stored as normalized 0..1 fractions so
  // a saved layout survives phone rotation and browser window resizing.
  // =========================================================================
  const KM_STORE = 'goso_keymap_v2';
  const KM_DEFAULT = [
    { id: 'k1', label: 'W', type: 'key', code: 51, x: 0.18, y: 0.72, size: 48 },
    { id: 'k2', label: 'A', type: 'key', code: 29, x: 0.07, y: 0.82, size: 48 },
    { id: 'k3', label: 'S', type: 'key', code: 47, x: 0.18, y: 0.88, size: 48 },
    { id: 'k4', label: 'D', type: 'key', code: 32, x: 0.29, y: 0.82, size: 48 },
    { id: 'k5', label: 'SPACE', type: 'key', code: 62, x: 0.72, y: 0.88, size: 64 },
    { id: 'k6', label: 'R', type: 'key', code: 46, x: 0.86, y: 0.72, size: 48 },
    { id: 'k7', label: 'F', type: 'key', code: 34, x: 0.86, y: 0.82, size: 48 },
    { id: 'k8', label: 'TAP', type: 'tap', code: 0, x: 0.72, y: 0.72, size: 48 }
  ];

  let keymapActive = false;
  let keymapEditing = false;
  let keymapLayout = loadKeymap();
  let selectedKeyId = null;

  const keymapOverlay = document.getElementById('keymapOverlay');
  const keymapEditor = document.getElementById('keymapEditor');
  const btnToggleKeymap = document.getElementById('btnToggleKeymap');

  function loadKeymap() {
    try {
      const raw = localStorage.getItem(KM_STORE);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length) return parsed;
      }
    } catch (err) {
      log(`Keymap tersimpan rusak, memakai default: ${err.message}`, 'warn');
    }
    return JSON.parse(JSON.stringify(KM_DEFAULT));
  }

  function saveKeymap() {
    try {
      localStorage.setItem(KM_STORE, JSON.stringify(keymapLayout));
    } catch (err) {
      log(`Gagal menyimpan keymap: ${err.message}`, 'error');
    }
  }

  function renderKeymap() {
    if (!keymapOverlay) return;
    keymapOverlay.textContent = '';
    for (const k of keymapLayout) {
      const el = document.createElement('div');
      el.className = 'keymap-key' + (k.size >= 56 ? ' square' : '');
      el.dataset.id = k.id;
      el.style.left = `${k.x * 100}%`;
      el.style.top = `${k.y * 100}%`;
      el.style.width = `${k.size}px`;
      el.style.height = `${k.size}px`;
      el.style.marginLeft = `${-k.size / 2}px`;
      el.style.marginTop = `${-k.size / 2}px`;
      el.style.opacity = keymapEditing ? '1' : String(k.alpha || 0.85);
      el.textContent = k.label;

      if (keymapEditing) {
        const handle = document.createElement('div');
        handle.className = 'km-handle';
        handle.addEventListener('pointerdown', (ev) => beginResize(ev, k, el));
        el.appendChild(handle);
      }
      bindKeyPointer(el, k);
      if (k.id === selectedKeyId) el.classList.add('selected');
      keymapOverlay.appendChild(el);
    }
  }

  // Drag (or fire the key when not editing)
  function bindKeyPointer(el, k) {
    let drag = null;

    el.addEventListener('pointerdown', (ev) => {
      ev.stopPropagation();
      if (!keymapEditing) {
        ev.preventDefault();
        fireKey(k, el);
        return;
      }
      ev.preventDefault();
      try { el.setPointerCapture(ev.pointerId); } catch (_) {}
      const box = keymapOverlay.getBoundingClientRect();
      drag = {
        dx: (ev.clientX - box.left) - k.x * box.width,
        dy: (ev.clientY - box.top) - k.y * box.height
      };
      selectKey(k.id);
      el.classList.add('selected');
    });

    el.addEventListener('pointermove', (ev) => {
      if (!drag) return;
      const box = keymapOverlay.getBoundingClientRect();
      if (!box.width || !box.height) return;
      const nx = (ev.clientX - box.left - drag.dx) / box.width;
      const ny = (ev.clientY - box.top - drag.dy) / box.height;
      k.x = Math.min(1, Math.max(0, nx));
      k.y = Math.min(1, Math.max(0, ny));
      el.style.left = `${k.x * 100}%`;
      el.style.top = `${k.y * 100}%`;
    });

    const endDrag = () => {
      if (!drag) return;
      drag = null;
      saveKeymap();
    };
    el.addEventListener('pointerup', endDrag);
    el.addEventListener('pointercancel', endDrag);
  }

  function beginResize(ev, k, el) {
    ev.stopPropagation();
    ev.preventDefault();
    try { el.setPointerCapture(ev.pointerId); } catch (_) {}
    const startX = ev.clientX, startY = ev.clientY, startSize = k.size;

    const move = (e) => {
      const d = Math.max(e.clientX - startX, e.clientY - startY);
      k.size = Math.min(96, Math.max(28, Math.round(startSize + d)));
      el.style.width = `${k.size}px`;
      el.style.height = `${k.size}px`;
      el.style.marginLeft = `${-k.size / 2}px`;
      el.style.marginTop = `${-k.size / 2}px`;
      const sizeInput = document.getElementById('kmSize');
      if (sizeInput) sizeInput.value = String(k.size);
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      saveKeymap();
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  function fireKey(k, el) {
    if (k.type === 'tap') {
      const cx = Math.round(k.x * 10000);
      const cy = Math.round(k.y * 10000);
      sendControl(`t:${cx},${cy}`);
      log(`Keymap TAP: (${cx}, ${cy})`, 'info');
    } else {
      sendControl(`key:${k.code}`);
      log(`Keymap KEY: ${k.label} (keycode ${k.code})`, 'info');
    }
    el.classList.add('active');
    setTimeout(() => el.classList.remove('active'), 140);
  }

  function selectKey(id) {
    selectedKeyId = id;
    const k = keymapLayout.find(x => x.id === id);
    const insp = document.getElementById('kmInspector');
    if (!k || !insp) { if (insp) insp.style.display = 'none'; return; }
    insp.style.display = 'flex';
    document.getElementById('kmLabel').value = k.label;
    document.getElementById('kmValue').value = String(k.code || 0);
    document.getElementById('kmSize').value = String(k.size);
    document.getElementById('kmAlpha').value = String(Math.round((k.alpha || 0.85) * 100));
    const wrap = document.getElementById('kmValueWrap');
    if (wrap) wrap.style.display = k.type === 'tap' ? 'none' : 'flex';
    renderKeymap();
  }

  function setEditing(on) {
    keymapEditing = on;
    if (keymapOverlay) keymapOverlay.classList.toggle('editing', on);
    // The floating config panel supersedes the inline editor bar; only show the
    // bar when the panel is closed (double-click flow).
    const panelOpen = (() => {
      const bd = document.getElementById('gpcBackdrop');
      return bd && bd.style.display !== 'none';
    })();
    if (keymapEditor) keymapEditor.style.display = (on && !panelOpen) ? 'flex' : 'none';
    if (!on) {
      selectedKeyId = null;
      const insp = document.getElementById('kmInspector');
      if (insp) insp.style.display = 'none';
    }
    renderKeymap();
  }

  if (btnToggleKeymap) {
    btnToggleKeymap.addEventListener('click', () => {
      keymapActive = !keymapActive;
      if (keymapOverlay) keymapOverlay.style.display = keymapActive ? 'block' : 'none';
      btnToggleKeymap.classList.toggle('active', keymapActive);
      if (!keymapActive) setEditing(false);
      else renderKeymap();
      log(`Keymap Game: ${keymapActive ? 'Aktif' : 'Nonaktif'}`, 'info');
    });
  }

  // Double-click / long-press on the overlay enters edit mode
  if (keymapOverlay) {
    keymapOverlay.addEventListener('dblclick', (e) => {
      if (keymapActive) {
        e.preventDefault();
        setEditing(true);
        log('Mode edit keymap aktif. Geser tombol, tarik sudut untuk ukuran.', 'info');
      }
    });
  }

  const kmAddKey = document.getElementById('kmAddKey');
  if (kmAddKey) kmAddKey.addEventListener('click', () => {
    const id = 'k' + Date.now().toString(36);
    keymapLayout.push({ id, label: 'X', type: 'key', code: 52, x: 0.5, y: 0.5, size: 48 });
    saveKeymap(); renderKeymap(); selectKey(id);
  });

  const kmAddTap = document.getElementById('kmAddTap');
  if (kmAddTap) kmAddTap.addEventListener('click', () => {
    const id = 'k' + Date.now().toString(36);
    keymapLayout.push({ id, label: 'TAP', type: 'tap', code: 0, x: 0.5, y: 0.5, size: 48 });
    saveKeymap(); renderKeymap(); selectKey(id);
  });

  const kmReset = document.getElementById('kmReset');
  if (kmReset) kmReset.addEventListener('click', () => {
    keymapLayout = JSON.parse(JSON.stringify(KM_DEFAULT));
    saveKeymap(); renderKeymap(); selectKey(null);
    log('Keymap direset ke preset default.', 'info');
  });

  const kmExport = document.getElementById('kmExport');
  if (kmExport) kmExport.addEventListener('click', async () => {
    const json = JSON.stringify(keymapLayout, null, 2);
    try {
      await navigator.clipboard.writeText(json);
      log(`Keymap JSON disalin (${keymapLayout.length} tombol).`, 'success');
    } catch (_) {
      log(`Keymap JSON: ${json}`, 'info');
    }
  });

  const kmDone = document.getElementById('kmDone');
  if (kmDone) kmDone.addEventListener('click', () => setEditing(false));

  const kmDelete = document.getElementById('kmDelete');
  if (kmDelete) kmDelete.addEventListener('click', () => {
    if (!selectedKeyId) return;
    keymapLayout = keymapLayout.filter(k => k.id !== selectedKeyId);
    selectedKeyId = null;
    saveKeymap(); renderKeymap();
    const insp = document.getElementById('kmInspector');
    if (insp) insp.style.display = 'none';
    log('Tombol keymap dihapus.', 'info');
  });

  // Live inspector bindings
  const kmLabel = document.getElementById('kmLabel');
  if (kmLabel) kmLabel.addEventListener('input', () => {
    const k = keymapLayout.find(x => x.id === selectedKeyId);
    if (!k) return;
    k.label = kmLabel.value || '?';
    saveKeymap(); renderKeymap();
  });

  const kmValue = document.getElementById('kmValue');
  if (kmValue) kmValue.addEventListener('input', () => {
    const k = keymapLayout.find(x => x.id === selectedKeyId);
    if (!k) return;
    const n = parseInt(kmValue.value, 10);
    if (!isNaN(n)) { k.code = n; saveKeymap(); }
  });

  const kmSize = document.getElementById('kmSize');
  if (kmSize) kmSize.addEventListener('input', () => {
    const k = keymapLayout.find(x => x.id === selectedKeyId);
    if (!k) return;
    k.size = parseInt(kmSize.value, 10) || 48;
    saveKeymap(); renderKeymap();
  });

  const kmAlpha = document.getElementById('kmAlpha');
  if (kmAlpha) kmAlpha.addEventListener('input', () => {
    const k = keymapLayout.find(x => x.id === selectedKeyId);
    if (!k) return;
    k.alpha = (parseInt(kmAlpha.value, 10) || 85) / 100;
    saveKeymap(); renderKeymap();
  });

  // =========================================================================
  // Gamepad Configuration Modal
  // Presets mirror DeskIn `presetkeyboard_deskin_*.json` (GameKeyBoardData +
  // GameKeyDataBean with x/y/width/height/scale/keyValue/pressType). Coordinates
  // here are stored as normalized 0..1 so a layout survives rotation/resize.
  // =========================================================================
  const GPC_STORE = 'goso_gamepad_style';
  const gpcState = {
    selectedId: null,
    style: { globalAlpha: 0.85, scale: 1, haptics: true, showLabels: true }
  };

  const GP_PRESETS = [
    {
      id: 'fps', name: 'FPS / Shooter',
      desc: 'WASD + jump + reload',
      keys: [
        { label: 'W', code: 51, x: 0.16, y: 0.70, size: 48 },
        { label: 'A', code: 29, x: 0.07, y: 0.82, size: 48 },
        { label: 'S', code: 47, x: 0.16, y: 0.90, size: 48 },
        { label: 'D', code: 32, x: 0.26, y: 0.82, size: 48 },
        { label: 'SPACE', code: 62, x: 0.78, y: 0.88, size: 64 },
        { label: 'R', code: 46, x: 0.88, y: 0.76, size: 48 },
        { label: 'F', code: 34, x: 0.88, y: 0.62, size: 48 }
      ]
    },
    {
      id: 'moba', name: 'MOBA',
      desc: 'Skill Q W E R',
      keys: [
        { label: 'Q', code: 45, x: 0.74, y: 0.62, size: 50 },
        { label: 'W', code: 51, x: 0.85, y: 0.70, size: 50 },
        { label: 'E', code: 33, x: 0.74, y: 0.78, size: 50 },
        { label: 'R', code: 46, x: 0.85, y: 0.86, size: 56 },
        { label: 'BACK', code: 4, x: 0.14, y: 0.88, size: 52 }
      ]
    },
    {
      id: 'racing', name: 'Racing',
      desc: 'Gas / rem / klakson',
      keys: [
        { label: 'GAS', code: 62, x: 0.86, y: 0.78, size: 64 },
        { label: 'REM', code: 47, x: 0.74, y: 0.86, size: 64 },
        { label: 'HORN', code: 34, x: 0.86, y: 0.60, size: 50 },
        { label: 'LEFT', code: 21, x: 0.12, y: 0.80, size: 52 },
        { label: 'RIGHT', code: 22, x: 0.26, y: 0.80, size: 52 }
      ]
    },
    {
      id: 'wasd', name: 'WASD Basic',
      desc: 'Hanya arah + tap',
      keys: [
        { label: 'W', code: 51, x: 0.18, y: 0.72, size: 48 },
        { label: 'A', code: 29, x: 0.07, y: 0.82, size: 48 },
        { label: 'S', code: 47, x: 0.18, y: 0.90, size: 48 },
        { label: 'D', code: 32, x: 0.29, y: 0.82, size: 48 },
        { label: 'TAP', code: 0, type: 'tap', x: 0.80, y: 0.80, size: 52 }
      ]
    },
    {
      id: 'media', name: 'Media / Nav',
      desc: 'Play, volume, back',
      keys: [
        { label: 'PLAY', code: 126, x: 0.50, y: 0.84, size: 58 },
        { label: 'VOL+', code: 24, x: 0.70, y: 0.84, size: 50 },
        { label: 'VOL-', code: 25, x: 0.86, y: 0.84, size: 50 },
        { label: 'BACK', code: 4, x: 0.14, y: 0.84, size: 50 },
        { label: 'HOME', code: 3, x: 0.30, y: 0.84, size: 50 }
      ]
    },
    {
      id: 'empty', name: 'Kosong',
      desc: 'Mulai dari nol',
      keys: []
    }
  ];

  function loadGamepadStyle() {
    try {
      const raw = localStorage.getItem(GPC_STORE);
      if (raw) Object.assign(gpcState.style, JSON.parse(raw));
    } catch (err) {
      log(`Gaya gamepad tersimpan rusak: ${err.message}`, 'warn');
    }
  }

  function saveGamepadStyle() {
    try { localStorage.setItem(GPC_STORE, JSON.stringify(gpcState.style)); } catch (_) {}
    applyGamepadStyle();
  }

  function applyGamepadStyle() {
    const s = gpcState.style;
    if (keymapOverlay) {
      keymapOverlay.style.opacity = String(s.globalAlpha);
      keymapOverlay.style.transform = `scale(${s.scale})`;
      keymapOverlay.style.transformOrigin = 'center center';
    }
    for (const k of keymapLayout) {
      if (k.alpha == null) k.alpha = s.globalAlpha;
    }
    renderKeymap();
  }

  function applyPreset(preset) {
    keymapLayout = preset.keys.map((k, i) => ({
      id: 'k' + Date.now().toString(36) + i,
      label: k.label,
      type: k.type || 'key',
      code: k.code,
      x: k.x, y: k.y,
      size: k.size || 48,
      alpha: gpcState.style.globalAlpha
    }));
    selectedKeyId = null;
    saveKeymap();
    renderKeymap();
    refreshGpcUi();
    log(`Preset gamepad dimuat: ${preset.name} (${keymapLayout.length} tombol)`, 'success');
  }

  function refreshGpcUi() {
    const grid = document.getElementById('gpcPresetGrid');
    if (grid) {
      grid.textContent = '';
      for (const p of GP_PRESETS) {
        const b = document.createElement('button');
        b.className = 'gpc-preset';
        b.dataset.preset = p.id;
        b.innerHTML = `<span class="gpc-preset-name">${p.name}</span>
                       <span class="gpc-preset-desc">${p.desc} • ${p.keys.length} tombol</span>`;
        b.addEventListener('click', () => {
          applyPreset(p);
          grid.querySelectorAll('.gpc-preset').forEach(x => x.classList.remove('active'));
          b.classList.add('active');
        });
        grid.appendChild(b);
      }
    }

    const list = document.getElementById('gpcKeyList');
    if (list) {
      list.textContent = '';
      for (const k of keymapLayout) {
        const c = document.createElement('button');
        c.className = 'gpc-keychip' + (k.id === gpcState.selectedId ? ' active' : '');
        c.textContent = `${k.label}${k.type === 'tap' ? ' ⟨tap⟩' : ' #' + k.code}`;
        c.addEventListener('click', () => {
          gpcState.selectedId = k.id;
          selectedKeyId = k.id;
          refreshGpcUi();
          selectKey(k.id);
        });
        list.appendChild(c);
      }
    }

    const cnt = document.getElementById('gpcCount');
    if (cnt) cnt.textContent = `${keymapLayout.length} tombol`;

    // Inspector values
    const k = keymapLayout.find(x => x.id === gpcState.selectedId);
    const insp = document.getElementById('gpcInspector');
    if (insp) insp.style.display = k ? 'flex' : 'none';
    if (k) {
      const set = (id, v) => { const e = document.getElementById(id); if (e) e.value = v; };
      set('gpcLabel', k.label);
      set('gpcCode', k.code || 0);
      set('gpcX', Math.round(k.x * 100));
      set('gpcY', Math.round(k.y * 100));
      set('gpcSize', k.size);
      set('gpcAlpha', Math.round((k.alpha || gpcState.style.globalAlpha) * 100));
      const txt = (id, v) => { const e = document.getElementById(id); if (e) e.textContent = v; };
      txt('gpcXVal', Math.round(k.x * 100) + '%');
      txt('gpcYVal', Math.round(k.y * 100) + '%');
      txt('gpcSizeVal', k.size + 'px');
      txt('gpcAlphaVal', Math.round((k.alpha || gpcState.style.globalAlpha) * 100) + '%');
      const cw = document.getElementById('gpcCodeWrap');
      if (cw) cw.style.display = k.type === 'tap' ? 'none' : 'flex';
    }
  }

  function openGpc() {
    const bd = document.getElementById('gpcBackdrop');
    if (!bd) return;
    loadGamepadStyle();
    // Sync style controls
    const s = gpcState.style;
    const ga = document.getElementById('gpcGlobalAlpha');
    if (ga) ga.value = Math.round(s.globalAlpha * 100);
    const sc = document.getElementById('gpcScale');
    if (sc) sc.value = Math.round(s.scale * 100);
    const ha = document.getElementById('gpcHaptics');
    if (ha) ha.checked = !!s.haptics;
    const sl = document.getElementById('gpcShowLabels');
    if (sl) sl.checked = !!s.showLabels;
    const gv = (id, v) => { const e = document.getElementById(id); if (e) e.textContent = v; };
    gv('gpcGlobalAlphaVal', Math.round(s.globalAlpha * 100) + '%');
    gv('gpcScaleVal', Math.round(s.scale * 100) + '%');

    bd.style.display = 'block';
    // Show the overlay so the user can see where the keys actually land while
    // tuning them. The panel floats above the preview without dimming it.
    if (!keymapActive) {
      keymapActive = true;
      if (keymapOverlay) keymapOverlay.style.display = 'block';
      if (btnToggleKeymap) btnToggleKeymap.classList.add('active');
    }
    setEditing(true);
    restorePanelPosition();
    refreshGpcUi();
    log('Panel konfigurasi gamepad dibuka. Geser header untuk memindahkan.', 'info');
  }

  function closeGpc() {
    const bd = document.getElementById('gpcBackdrop');
    if (bd) bd.style.display = 'none';
    // Leaving edit mode is essential: while editing, a key press selects the
    // button instead of dispatching it. After closing the panel the overlay
    // must go back to "press to fire" so the mapping actually works.
    setEditing(false);
    selectedKeyId = null;
    gpcState.selectedId = null;
    renderKeymap();
  }

  // ---------------------------------------------------------------------
  // Draggable floating panel — the phone preview stays fully visible so the
  // user can see the real button positions while configuring them.
  // ---------------------------------------------------------------------
  const GPC_POS_KEY = 'goso_gpc_panel_pos';

  function savePanelPosition() {
    const m = document.querySelector('.gpc-modal');
    if (!m) return;
    try {
      localStorage.setItem(GPC_POS_KEY, JSON.stringify({
        left: m.style.left || '', top: m.style.top || '',
        width: m.style.width || '', height: m.style.height || ''
      }));
    } catch (_) {}
  }

  function restorePanelPosition() {
    const m = document.querySelector('.gpc-modal');
    if (!m) return;
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(GPC_POS_KEY) || 'null'); } catch (_) {}
    if (saved && saved.left && saved.top) {
      m.style.left = saved.left;
      m.style.top = saved.top;
      if (saved.width) m.style.width = saved.width;
      if (saved.height) m.style.height = saved.height;
    }
    clampPanelIntoView();
  }

  /** Keeps the panel reachable if the window was resized since last use. */
  function clampPanelIntoView() {
    const m = document.querySelector('.gpc-modal');
    if (!m) return;
    const r = m.getBoundingClientRect();
    const vw = window.innerWidth, vh = window.innerHeight;
    let left = r.left, top = r.top;
    if (left < 0) left = 0;
    if (top < 0) top = 0;
    if (left + r.width > vw) left = Math.max(0, vw - r.width);
    if (top + r.height > vh) top = Math.max(0, vh - r.height);
    m.style.left = `${left}px`;
    m.style.top = `${top}px`;
  }

  (function setupPanelDrag() {
    const head = document.getElementById('gpcHead');
    const modal = document.querySelector('.gpc-modal');
    if (!head || !modal) return;

    let drag = null;

    head.addEventListener('pointerdown', (e) => {
      // Do not start a drag from the header buttons.
      if (e.target.closest('button')) return;
      e.preventDefault();
      const r = modal.getBoundingClientRect();
      drag = { dx: e.clientX - r.left, dy: e.clientY - r.top };
      modal.classList.add('dragging');
      try { head.setPointerCapture(e.pointerId); } catch (_) {}
    });

    head.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const vw = window.innerWidth, vh = window.innerHeight;
      const w = modal.offsetWidth, h = modal.offsetHeight;
      let left = e.clientX - drag.dx;
      let top = e.clientY - drag.dy;
      left = Math.min(Math.max(0, left), Math.max(0, vw - w));
      top = Math.min(Math.max(0, top), Math.max(0, vh - h));
      modal.style.left = `${left}px`;
      modal.style.top = `${top}px`;
    });

    const endDrag = () => {
      if (!drag) return;
      drag = null;
      modal.classList.remove('dragging');
      savePanelPosition();
    };
    head.addEventListener('pointerup', endDrag);
    head.addEventListener('pointercancel', endDrag);
  })();

  const gpcCollapse = document.getElementById('gpcCollapse');
  if (gpcCollapse) {
    gpcCollapse.addEventListener('click', () => {
      const m = document.querySelector('.gpc-modal');
      if (!m) return;
      const collapsed = m.classList.toggle('collapsed');
      gpcCollapse.textContent = collapsed ? '▸' : '▾';
      gpcCollapse.title = collapsed ? 'Besarkan panel' : 'Kecilkan panel';
    });
  }

  const btnKeymapConfig = document.getElementById('btnKeymapConfig');
  if (btnKeymapConfig) btnKeymapConfig.addEventListener('click', openGpc);
  const gpcClose = document.getElementById('gpcClose');
  if (gpcClose) gpcClose.addEventListener('click', closeGpc);
  const gpcSave = document.getElementById('gpcSave');
  if (gpcSave) gpcSave.addEventListener('click', () => { saveKeymap(); saveGamepadStyle(); closeGpc(); });

  // Tabs
  document.querySelectorAll('.gpc-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.gpc-tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.gpc-pane').forEach(p => p.classList.remove('active'));
      tab.classList.add('active');
      const pane = document.querySelector(`.gpc-pane[data-pane="${tab.dataset.tab}"]`);
      if (pane) pane.classList.add('active');
    });
  });

  // Add / delete
  const gpcAddKey = document.getElementById('gpcAddKey');
  if (gpcAddKey) gpcAddKey.addEventListener('click', () => {
    const id = 'k' + Date.now().toString(36);
    keymapLayout.push({ id, label: 'X', type: 'key', code: 52, x: 0.5, y: 0.5, size: 48, alpha: gpcState.style.globalAlpha });
    gpcState.selectedId = id; selectedKeyId = id;
    saveKeymap(); renderKeymap(); refreshGpcUi();
  });

  const gpcAddTap = document.getElementById('gpcAddTap');
  if (gpcAddTap) gpcAddTap.addEventListener('click', () => {
    const id = 'k' + Date.now().toString(36);
    keymapLayout.push({ id, label: 'TAP', type: 'tap', code: 0, x: 0.5, y: 0.5, size: 48, alpha: gpcState.style.globalAlpha });
    gpcState.selectedId = id; selectedKeyId = id;
    saveKeymap(); renderKeymap(); refreshGpcUi();
  });

  const gpcDelete = document.getElementById('gpcDelete');
  if (gpcDelete) gpcDelete.addEventListener('click', () => {
    if (!gpcState.selectedId) return;
    keymapLayout = keymapLayout.filter(k => k.id !== gpcState.selectedId);
    gpcState.selectedId = null; selectedKeyId = null;
    saveKeymap(); renderKeymap(); refreshGpcUi();
  });

  const gpcResetAll = document.getElementById('gpcResetAll');
  if (gpcResetAll) gpcResetAll.addEventListener('click', () => {
    keymapLayout = JSON.parse(JSON.stringify(KM_DEFAULT));
    gpcState.selectedId = null; selectedKeyId = null;
    gpcState.style = { globalAlpha: 0.85, scale: 1, haptics: true, showLabels: true };
    saveKeymap(); saveGamepadStyle(); refreshGpcUi();
    log('Gamepad direset ke default.', 'info');
  });

  // Export / Import
  const gpcExport = document.getElementById('gpcExport');
  if (gpcExport) gpcExport.addEventListener('click', async () => {
    const payload = JSON.stringify({ version: 1, keys: keymapLayout, style: gpcState.style }, null, 2);
    const ta = document.getElementById('gpcJson');
    if (ta) ta.value = payload;
    try { await navigator.clipboard.writeText(payload); log('Layout gamepad disalin ke clipboard.', 'success'); }
    catch (_) { log('Layout gamepad siap di kotak JSON.', 'info'); }
  });

  const gpcImport = document.getElementById('gpcImport');
  if (gpcImport) gpcImport.addEventListener('click', () => {
    const ta = document.getElementById('gpcJson');
    if (!ta || !ta.value.trim()) { log('Kotak JSON kosong.', 'warn'); return; }
    try {
      const obj = JSON.parse(ta.value);
      const keys = Array.isArray(obj) ? obj : obj.keys;
      if (!Array.isArray(keys)) throw new Error('Field "keys" tidak ditemukan');
      keymapLayout = keys.map((k, i) => ({
        id: k.id || ('k' + Date.now().toString(36) + i),
        label: String(k.label || '?'),
        type: k.type === 'tap' ? 'tap' : 'key',
        code: Number(k.code) || 0,
        x: Math.min(1, Math.max(0, Number(k.x) || 0)),
        y: Math.min(1, Math.max(0, Number(k.y) || 0)),
        size: Math.min(96, Math.max(28, Number(k.size) || 48)),
        alpha: Number(k.alpha) || gpcState.style.globalAlpha
      }));
      if (obj.style) Object.assign(gpcState.style, obj.style);
      gpcState.selectedId = null; selectedKeyId = null;
      saveKeymap(); saveGamepadStyle(); refreshGpcUi();
      log(`Import berhasil: ${keymapLayout.length} tombol.`, 'success');
    } catch (err) {
      log(`Import gagal: ${err.message}`, 'error');
    }
  });

  // Inspector bindings
  const bindInspector = (id, apply, labelId, fmt) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener('input', () => {
      const k = keymapLayout.find(x => x.id === gpcState.selectedId);
      if (!k) return;
      apply(k, el.value);
      if (labelId) {
        const l = document.getElementById(labelId);
        if (l) l.textContent = fmt ? fmt(el.value) : el.value;
      }
      saveKeymap(); renderKeymap();
      if (labelId === 'gpcXVal' || labelId === 'gpcYVal') refreshGpcUi();
    });
  };
  bindInspector('gpcLabel', (k, v) => { k.label = v || '?'; });
  bindInspector('gpcCode', (k, v) => { const n = parseInt(v, 10); if (!isNaN(n)) k.code = n; });
  bindInspector('gpcX', (k, v) => { k.x = Math.min(1, Math.max(0, Number(v) / 100)); }, 'gpcXVal', v => v + '%');
  bindInspector('gpcY', (k, v) => { k.y = Math.min(1, Math.max(0, Number(v) / 100)); }, 'gpcYVal', v => v + '%');
  bindInspector('gpcSize', (k, v) => { k.size = parseInt(v, 10) || 48; }, 'gpcSizeVal', v => v + 'px');
  bindInspector('gpcAlpha', (k, v) => { k.alpha = (parseInt(v, 10) || 85) / 100; }, 'gpcAlphaVal', v => v + '%');

  // Style bindings
  const gpcGlobalAlpha = document.getElementById('gpcGlobalAlpha');
  if (gpcGlobalAlpha) gpcGlobalAlpha.addEventListener('input', () => {
    gpcState.style.globalAlpha = (parseInt(gpcGlobalAlpha.value, 10) || 85) / 100;
    const l = document.getElementById('gpcGlobalAlphaVal');
    if (l) l.textContent = gpcGlobalAlpha.value + '%';
    for (const k of keymapLayout) k.alpha = gpcState.style.globalAlpha;
    saveGamepadStyle();
  });

  const gpcScale = document.getElementById('gpcScale');
  if (gpcScale) gpcScale.addEventListener('input', () => {
    gpcState.style.scale = (parseInt(gpcScale.value, 10) || 100) / 100;
    const l = document.getElementById('gpcScaleVal');
    if (l) l.textContent = gpcScale.value + '%';
    saveGamepadStyle();
  });

  const gpcHaptics = document.getElementById('gpcHaptics');
  if (gpcHaptics) gpcHaptics.addEventListener('change', () => {
    gpcState.style.haptics = gpcHaptics.checked;
    saveGamepadStyle();
  });

  const gpcShowLabels = document.getElementById('gpcShowLabels');
  if (gpcShowLabels) gpcShowLabels.addEventListener('change', () => {
    gpcState.style.showLabels = gpcShowLabels.checked;
    saveGamepadStyle();
  });

  // Physical keyboard -> keycodes (1:1 DeskIn keyboard_code.json semantics)
  const KEYCODE_MAP = {
    'Backspace': 67, 'Enter': 66, 'Tab': 61, ' ': 62, 'Spacebar': 62,
    'Escape': 111, 'Delete': 112,
    'ArrowUp': 19, 'ArrowDown': 20, 'ArrowLeft': 21, 'ArrowRight': 22
  };

  window.addEventListener('keydown', (e) => {
    if (document.activeElement === typeInputEl()) return;
    if (keymapEditing) return;
    if (!window.GoApp || !window.GoApp.isConnected()) return;

    // Game keymap bindings win when active
    if (keymapActive) {
      const k = keymapLayout.find(
        x => x.type === 'key' && x.label.toUpperCase() === e.key.toUpperCase()
      );
      if (k) {
        e.preventDefault();
        const el = keymapOverlay && keymapOverlay.querySelector(`[data-id="${k.id}"]`);
        fireKey(k, el || document.createElement('div'));
        return;
      }
    }

    if (e.key in KEYCODE_MAP) {
      e.preventDefault();
      sendControl(`key:${KEYCODE_MAP[e.key]}`);
      return;
    }
    if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
      e.preventDefault();
      sendControl(`text:${e.key}`);
    }
  });

  function typeInputEl() { return document.getElementById('typeInput'); }
  // =========================================================================
  // Clipboard Sync (PC -> HP)
  // =========================================================================
  const btnClipboardSync = document.getElementById('btnClipboardSync');
  if (btnClipboardSync) {
    btnClipboardSync.addEventListener('click', async () => {
      try {
        const text = await navigator.clipboard.readText();
        if (text) {
          sendControl(`clipboard:${text}`);
          log(`Clipboard dikirim ke HP (${text.length} karakter)`, 'success');
        } else {
          log('Clipboard PC kosong', 'warn');
        }
      } catch (err) {
        log(`Gagal membaca clipboard: ${err.message}`, 'error');
      }
    });
  }

  // =========================================================================
  // Direct Typing & Physical Keyboard
  // =========================================================================
  const typeInput = document.getElementById('typeInput');
  const btnSendText = document.getElementById('btnSendText');

  function sendTextDirect() {
    if (!typeInput) return;
    const txt = typeInput.value.trim();
    if (txt) {
      sendControl(`text:${txt}`);
      log(`Sent text: "${txt}"`, 'info');
      typeInput.value = '';
    }
  }

  if (btnSendText) btnSendText.addEventListener('click', sendTextDirect);
  if (typeInput) {
    typeInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') sendTextDirect();
    });
  }

  // Maestro Unicode & Emoji typing
  const btnMaestroType = document.getElementById('btnMaestroType');
  function sendTextMaestro() {
    if (!typeInput) return;
    const txt = typeInput.value;
    if (txt) {
      sendControl(JSON.stringify({ cmd: 'maestroType', text: txt }));
      log(`[Maestro Unicode] Sent: "${txt}"`, 'success');
      typeInput.value = '';
    }
  }
  if (btnMaestroType) btnMaestroType.addEventListener('click', sendTextMaestro);

  // =========================================================================
  // WebADB 1:1 Left-Docked File Manager Controller
  // =========================================================================
  const btnFileManager = document.getElementById('btnFileManager');
  const fileManagerPanel = document.getElementById('fileManagerPanel');
  const btnFmClose = document.getElementById('btnFmClose');
  const fmPathInput = document.getElementById('fmPathInput');
  const fmBtnUp = document.getElementById('fmBtnUp');
  const fmBtnRefresh = document.getElementById('fmBtnRefresh');
  const fmBtnUpload = document.getElementById('fmBtnUpload');
  const fmFileInput = document.getElementById('fmFileInput');
  const fmFileTableBody = document.getElementById('fmFileTableBody');
  const fmItemCount = document.getElementById('fmItemCount');
  const fmUploadProgress = document.getElementById('fmUploadProgress');
  const fmUploadStatus = document.getElementById('fmUploadStatus');
  const fmUploadBar = document.getElementById('fmUploadBar');
  const fmUploadPct = document.getElementById('fmUploadPct');
  const fmSearchInput = document.getElementById('fmSearchInput');
  const fmSortSelect = document.getElementById('fmSortSelect');
  const fmSelectAll = document.getElementById('fmSelectAll');
  const fmBatchBar = document.getElementById('fmBatchBar');
  const fmBatchCount = document.getElementById('fmBatchCount');
  const fmBatchDownload = document.getElementById('fmBatchDownload');
  const fmBatchDelete = document.getElementById('fmBatchDelete');
  const fmBatchClear = document.getElementById('fmBatchClear');

  // Preview Lightbox elements
  const previewModal = document.getElementById('previewModal');
  const previewTitle = document.getElementById('previewTitle');
  const previewContent = document.getElementById('previewContent');
  const previewMeta = document.getElementById('previewMeta');
  const previewBtnDownload = document.getElementById('previewBtnDownload');
  const previewBtnClose = document.getElementById('previewBtnClose');

  let currentFmDir = '/sdcard/DCIM/Camera';
  let rawFiles = [];
  let selectedFiles = new Set();
  let currentSort = 'date_desc';
  let currentSearch = '';
  let currentPreviewPath = null;

  function normalizePath(p) {
    if (!p) return '/sdcard';
    let s = p.replace(/\/+/g, '/');
    if (s.length > 1 && s.endsWith('/')) s = s.slice(0, -1);
    return s;
  }

  function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  function formatTime(ms) {
    if (!ms) return '-';
    const d = new Date(ms);
    return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
  }

  function isTextFile(name) {
    return /\.(txt|log|json|xml|sh|properties|yaml|yml|csv|md|conf|cfg|ini)$/i.test(name);
  }
  function isMediaFile(name) {
    return /\.(mp4|mov|mkv|webm|jpg|jpeg|png|webp|gif|txt|log|json|xml|sh|properties|yaml|yml|csv|md|conf|cfg|ini)$/i.test(name);
  }
  function isVideoFile(name) {
    return /\.(mp4|mov|mkv|webm)$/i.test(name);
  }

  function openPreview(file) {
    if (!previewModal || !previewContent) return;
    currentPreviewPath = file.path;
    previewTitle.textContent = file.name;
    previewMeta.textContent = `${formatBytes(file.size)} • ${formatTime(file.mtime)}`;
    previewContent.innerHTML = '';

    const mediaUrl = `/api/preview?path=${encodeURIComponent(file.path)}`;
    if (isVideoFile(file.name)) {
      const vid = document.createElement('video');
      vid.src = mediaUrl;
      vid.controls = true;
      vid.autoplay = true;
      vid.playsInline = true;
      vid.style.maxWidth = '85vw';
      vid.style.maxHeight = '72vh';
      vid.style.borderRadius = '8px';
      vid.style.boxShadow = '0 10px 30px rgba(0,0,0,0.8)';
      previewContent.appendChild(vid);
    } else if (isTextFile(file.name)) {
      previewContent.innerHTML = '<div style="color: var(--text-muted); padding: 24px;">Memuat teks...</div>';
      fetch(`/api/download?path=${encodeURIComponent(file.path)}`)
        .then(r => r.text())
        .then(text => {
          previewContent.innerHTML = '';
          const pre = document.createElement('pre');
          pre.style.width = '100%';
          pre.style.maxWidth = '85vw';
          pre.style.maxHeight = '68vh';
          pre.style.overflow = 'auto';
          pre.style.fontFamily = 'ui-monospace, SFMono-Regular, Menlo, monospace';
          pre.style.fontSize = '0.78rem';
          pre.style.lineHeight = '1.5';
          pre.style.color = '#e2e8f0';
          pre.style.background = '#020104';
          pre.style.padding = '14px';
          pre.style.borderRadius = '8px';
          pre.style.border = '1px solid rgba(255,255,255,0.08)';
          pre.style.whiteSpace = 'pre-wrap';
          pre.style.wordBreak = 'break-all';
          pre.textContent = text;
          previewContent.appendChild(pre);
        })
        .catch(err => {
          previewContent.innerHTML = `<div style="color: #ef4444; padding: 24px;">Gagal memuat teks: ${err.message}</div>`;
        });
    } else {
      const img = document.createElement('img');
      img.src = mediaUrl;
      img.alt = file.name;
      img.style.maxWidth = '85vw';
      img.style.maxHeight = '72vh';
      img.style.borderRadius = '8px';
      img.style.objectFit = 'contain';
      previewContent.appendChild(img);
    }
    previewModal.style.display = 'flex';
  }

  function closePreview() {
    if (!previewModal) return;
    const vid = previewContent?.querySelector('video');
    if (vid) { vid.pause(); vid.src = ''; }
    previewModal.style.display = 'none';
    currentPreviewPath = null;
  }

  if (previewBtnClose) previewBtnClose.addEventListener('click', closePreview);
  if (previewBtnDownload) {
    previewBtnDownload.addEventListener('click', () => {
      if (currentPreviewPath) {
        window.open(`/api/download?path=${encodeURIComponent(currentPreviewPath)}`, '_blank');
      }
    });
  }
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && previewModal && previewModal.style.display !== 'none') {
      closePreview();
    }
  });

  async function requestFileList(dir) {
    if (dir) currentFmDir = normalizePath(dir);
    if (fmPathInput) fmPathInput.value = currentFmDir;
    selectedFiles.clear();
    updateBatchBar();

    // Update active chip state
    document.querySelectorAll('.fm-chip').forEach(chip => {
      chip.classList.toggle('active', chip.dataset.path === currentFmDir);
    });

    if (fmFileTableBody) {
      fmFileTableBody.innerHTML = '<tr><td colspan="5" style="padding: 24px; text-align: center; color: var(--text-muted);">Memuat berkas dari HP...</td></tr>';
    }

    try {
      const resp = await fetch(`/api/files?dir=${encodeURIComponent(currentFmDir)}`);
      if (resp.ok) {
        const data = await resp.json();
        rawFiles = data.files || [];
        applySortAndRender();
        return;
      }
    } catch (_) {}

    // WebSocket fallback
    sendControl(JSON.stringify({ cmd: 'listFiles', dir: currentFmDir }));
  }

  function applySortAndRender() {
    let filtered = rawFiles.filter(f => {
      if (!currentSearch) return true;
      return f.name.toLowerCase().includes(currentSearch.toLowerCase());
    });

    // Sorting
    filtered.sort((a, b) => {
      if (a.is_dir && !b.is_dir) return -1;
      if (!a.is_dir && b.is_dir) return 1;
      switch (currentSort) {
        case 'date_asc': return (a.mtime || 0) - (b.mtime || 0);
        case 'date_desc': return (b.mtime || 0) - (a.mtime || 0);
        case 'name_asc': return a.name.localeCompare(b.name);
        case 'name_desc': return b.name.localeCompare(a.name);
        case 'size_asc': return (a.size || 0) - (b.size || 0);
        case 'size_desc': return (b.size || 0) - (a.size || 0);
        default: return (b.mtime || 0) - (a.mtime || 0);
      }
    });

    renderTable(filtered);
  }

  function updateBatchBar() {
    const count = selectedFiles.size;
    if (fmBatchBar) {
      fmBatchBar.style.display = count > 0 ? 'flex' : 'none';
    }
    if (fmBatchCount) {
      fmBatchCount.textContent = `${count} dipilih`;
    }
    if (fmSelectAll) {
      const totalFiles = rawFiles.filter(f => !f.is_dir).length;
      fmSelectAll.checked = count > 0 && count === totalFiles;
    }
  }

  function renderTable(files) {
    if (!fmFileTableBody) return;
    if (!files || !files.length) {
      fmFileTableBody.innerHTML = '<tr><td colspan="5" style="padding: 24px; text-align: center; color: var(--text-muted);">Folder kosong</td></tr>';
      if (fmItemCount) fmItemCount.textContent = '0 item';
      return;
    }

    if (fmItemCount) fmItemCount.textContent = `${files.length} item`;

    let html = '';
    for (const f of files) {
      const icon = f.is_dir ? '📁' : (isVideoFile(f.name) ? '🎬' : (isMediaFile(f.name) ? '🖼️' : '📄'));
      const fullPath = `${currentFmDir}/${f.name}`;
      const checked = selectedFiles.has(fullPath) ? 'checked' : '';

      if (f.is_dir) {
        html += `<tr style="border-bottom: 1px solid rgba(255,255,255,0.04);">
          <td class="fm-col-cb" style="padding: 6px; text-align: center;">-</td>
          <td class="fm-col-name" style="padding: 6px 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
            <span class="fm-row-folder" data-folder="${encodeURIComponent(f.name)}" title="${f.name}">${icon} ${f.name}</span>
          </td>
          <td class="fm-col-size" style="padding: 6px 8px; color: var(--text-muted); font-size: 0.72rem; text-align: right; white-space: nowrap;">Folder</td>
          <td class="fm-col-date" style="padding: 6px 8px; color: var(--text-muted); font-size: 0.7rem; text-align: right; white-space: nowrap;">${formatTime(f.mtime)}</td>
          <td class="fm-col-action" style="padding: 6px 8px; text-align: center; white-space: nowrap;">
            <button class="fm-action-btn" data-delete="${encodeURIComponent(fullPath)}" title="Hapus folder">🗑️</button>
          </td>
        </tr>`;
      } else {
        const previewBtn = isMediaFile(f.name) ? `<button class="fm-action-btn" data-preview="${encodeURIComponent(JSON.stringify({ name: f.name, path: fullPath, size: f.size, mtime: f.mtime }))}" title="Preview (Video/Foto/Teks)">👁️</button>` : '';
        html += `<tr style="border-bottom: 1px solid rgba(255,255,255,0.04);">
          <td class="fm-col-cb" style="padding: 6px; text-align: center;">
            <input type="checkbox" class="fm-cb" data-path="${encodeURIComponent(fullPath)}" ${checked}>
          </td>
          <td class="fm-col-name" style="padding: 6px 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
            <span class="fm-filename ${isMediaFile(f.name) ? 'fm-row-media' : ''}" data-path="${encodeURIComponent(fullPath)}" title="${f.name}" style="${isMediaFile(f.name) ? 'cursor: pointer; color: #38bdf8;' : ''}">${icon} ${f.name}</span>
          </td>
          <td class="fm-col-size" style="padding: 6px 8px; color: var(--text-muted); font-size: 0.72rem; text-align: right; white-space: nowrap;">${formatBytes(f.size)}</td>
          <td class="fm-col-date" style="padding: 6px 8px; color: var(--text-muted); font-size: 0.7rem; text-align: right; white-space: nowrap;">${formatTime(f.mtime)}</td>
          <td class="fm-col-action" style="padding: 6px 8px; text-align: center; white-space: nowrap;">
            ${previewBtn}
            <button class="fm-action-btn" data-download="${encodeURIComponent(fullPath)}" title="Unduh ke PC">⬇️</button>
            <button class="fm-action-btn" data-delete="${encodeURIComponent(fullPath)}" title="Hapus dari HP">🗑️</button>
          </td>
        </tr>`;
      }
    }
    fmFileTableBody.innerHTML = html;

    // Bind row handlers
    fmFileTableBody.querySelectorAll('.fm-row-folder').forEach(el => {
      el.addEventListener('click', () => {
        const folderName = decodeURIComponent(el.dataset.folder);
        requestFileList(`${currentFmDir}/${folderName}`);
      });
    });

    fmFileTableBody.querySelectorAll('[data-preview]').forEach(btn => {
      btn.addEventListener('click', () => {
        const fileInfo = JSON.parse(decodeURIComponent(btn.dataset.preview));
        openPreview(fileInfo);
      });
    });

    fmFileTableBody.querySelectorAll('.fm-row-media').forEach(el => {
      el.addEventListener('click', () => {
        const p = decodeURIComponent(el.dataset.path);
        const f = files.find(x => `${currentFmDir}/${x.name}` === p);
        if (f) openPreview({ name: f.name, path: p, size: f.size, mtime: f.mtime });
      });
    });

    fmFileTableBody.querySelectorAll('[data-download]').forEach(btn => {
      btn.addEventListener('click', () => {
        const path = decodeURIComponent(btn.dataset.download);
        window.open(`/api/download?path=${encodeURIComponent(path)}`, '_blank');
      });
    });

    fmFileTableBody.querySelectorAll('[data-delete]').forEach(btn => {
      btn.addEventListener('click', async () => {
        const path = decodeURIComponent(btn.dataset.delete);
        const fname = path.split('/').pop();
        if (!confirm(`Hapus "${fname}" dari HP?`)) return;
        try {
          const res = await fetch(`/api/delete?path=${encodeURIComponent(path)}`, { method: 'POST' });
          if (res.ok) {
            log(`Berkas "${fname}" berhasil dihapus.`, 'success');
            requestFileList();
          } else {
            log(`Gagal menghapus berkas.`, 'error');
          }
        } catch (e) {
          log(`Error delete: ${e.message}`, 'error');
        }
      });
    });

    // Checkbox toggles
    fmFileTableBody.querySelectorAll('.fm-cb').forEach(cb => {
      cb.addEventListener('change', () => {
        const path = decodeURIComponent(cb.dataset.path);
        if (cb.checked) selectedFiles.add(path);
        else selectedFiles.delete(path);
        updateBatchBar();
      });
    });
  }

  // Select All Checkbox
  if (fmSelectAll) {
    fmSelectAll.addEventListener('change', () => {
      const isChecked = fmSelectAll.checked;
      selectedFiles.clear();
      if (isChecked) {
        rawFiles.filter(f => !f.is_dir).forEach(f => {
          selectedFiles.add(`${currentFmDir}/${f.name}`);
        });
      }
      fmFileTableBody?.querySelectorAll('.fm-cb').forEach(cb => {
        cb.checked = isChecked;
      });
      updateBatchBar();
    });
  }

  // Batch Download
  if (fmBatchDownload) {
    fmBatchDownload.addEventListener('click', () => {
      if (!selectedFiles.size) return;
      selectedFiles.forEach(path => {
        window.open(`/api/download?path=${encodeURIComponent(path)}`, '_blank');
      });
    });
  }

  // Batch Delete
  if (fmBatchDelete) {
    fmBatchDelete.addEventListener('click', async () => {
      const count = selectedFiles.size;
      if (!count || !confirm(`Hapus ${count} berkas yang dipilih secara permanen dari HP?`)) return;
      let deleted = 0;
      for (const path of selectedFiles) {
        try {
          const res = await fetch(`/api/delete?path=${encodeURIComponent(path)}`, { method: 'POST' });
          if (res.ok) deleted++;
        } catch (_) {}
      }
      log(`${deleted} dari ${count} berkas berhasil dihapus.`, 'success');
      selectedFiles.clear();
      requestFileList();
    });
  }

  if (fmBatchClear) {
    fmBatchClear.addEventListener('click', () => {
      selectedFiles.clear();
      updateBatchBar();
      fmFileTableBody?.querySelectorAll('.fm-cb').forEach(cb => { cb.checked = false; });
    });
  }

  // Search Filter
  if (fmSearchInput) {
    fmSearchInput.addEventListener('input', (e) => {
      currentSearch = e.target.value.trim();
      applySortAndRender();
    });
  }

  // Sort Dropdown
  if (fmSortSelect) {
    fmSortSelect.addEventListener('change', (e) => {
      currentSort = e.target.value;
      applySortAndRender();
    });
  }

  // Column header sort click
  const fmThName = document.getElementById('fmThName');
  const fmThSize = document.getElementById('fmThSize');
  const fmThDate = document.getElementById('fmThDate');
  if (fmThName) fmThName.addEventListener('click', () => {
    currentSort = currentSort === 'name_asc' ? 'name_desc' : 'name_asc';
    if (fmSortSelect) fmSortSelect.value = currentSort;
    applySortAndRender();
  });
  if (fmThSize) fmThSize.addEventListener('click', () => {
    currentSort = currentSort === 'size_desc' ? 'size_asc' : 'size_desc';
    if (fmSortSelect) fmSortSelect.value = currentSort;
    applySortAndRender();
  });
  if (fmThDate) fmThDate.addEventListener('click', () => {
    currentSort = currentSort === 'date_desc' ? 'date_asc' : 'date_desc';
    if (fmSortSelect) fmSortSelect.value = currentSort;
    applySortAndRender();
  });

  window.GoControl = window.GoControl || {};
  window.GoControl.onFileList = (msg) => {
    if (msg.dir) currentFmDir = normalizePath(msg.dir);
    if (fmPathInput) fmPathInput.value = currentFmDir;
    rawFiles = msg.files || [];
    applySortAndRender();
  };

  // REAL XHR PROGRESS STREAMING UPLOAD
  function uploadSingleFile(file, dir) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const uploadUrl = `/api/upload?dir=${encodeURIComponent(dir)}&filename=${encodeURIComponent(file.name)}`;
      xhr.open('POST', uploadUrl, true);
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');

      xhr.upload.onprogress = (ev) => {
        if (ev.lengthComputable) {
          const pct = Math.min(99, Math.round((ev.loaded / ev.total) * 100));
          if (fmUploadBar) fmUploadBar.style.width = `${pct}%`;
          if (fmUploadPct) fmUploadPct.textContent = `${pct}%`;
          if (fmUploadStatus) {
            fmUploadStatus.textContent = `Mengunggah: ${file.name} (${formatBytes(ev.loaded)} / ${formatBytes(ev.total)})`;
          }
        }
      };

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          if (fmUploadBar) fmUploadBar.style.width = '100%';
          if (fmUploadPct) fmUploadPct.textContent = '100%';
          log(`Transfer sukses: ${file.name} (${formatBytes(file.size)}) tersimpan dan diindeks ke galeri HP!`, 'success');
          resolve();
        } else {
          log(`Gagal mengunggah ${file.name} (HTTP ${xhr.status})`, 'error');
          reject(new Error(`HTTP ${xhr.status}`));
        }
      };

      xhr.onerror = () => {
        log(`Koneksi transfer terputus untuk ${file.name}`, 'error');
        reject(new Error('Network error'));
      };

      xhr.send(file);
    });
  }

  async function handleFilesUpload(files, targetDir) {
    if (!files || !files.length) return;
    const dir = targetDir || currentFmDir;

    if (fmUploadProgress) {
      fmUploadProgress.style.display = 'block';
      if (fmUploadBar) fmUploadBar.style.width = '0%';
      if (fmUploadPct) fmUploadPct.textContent = '0%';
    }

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      log(`Mengunggah ke HP (${i + 1}/${files.length}): ${file.name} (${formatBytes(file.size)}) -> ${dir}`, 'info');
      try {
        await uploadSingleFile(file, dir);
      } catch (err) {
        log(`Upload gagal: ${err.message}`, 'error');
      }
    }

    if (fmUploadProgress) {
      setTimeout(() => { fmUploadProgress.style.display = 'none'; }, 800);
    }
    requestFileList();
  }

  window.GoControl.onUploadResult = (msg) => {
    if (msg.ok) {
      log(`Transfer sukses: ${msg.filename} (${formatBytes(msg.size)}) telah diindeks ke galeri HP!`, 'success');
      requestFileList();
    } else {
      log(`Transfer gagal: ${msg.error || 'Unknown error'}`, 'error');
    }
  };

  // Bookmark Chips
  document.querySelectorAll('.fm-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      requestFileList(chip.dataset.path);
    });
  });

  // Up button (Parent folder)
  if (fmBtnUp) {
    fmBtnUp.addEventListener('click', () => {
      if (currentFmDir === '/' || currentFmDir === '/sdcard') return;
      const idx = currentFmDir.lastIndexOf('/');
      const parent = idx <= 0 ? '/' : currentFmDir.substring(0, idx);
      requestFileList(parent);
    });
  }

  // Path input Enter navigation
  if (fmPathInput) {
    fmPathInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        requestFileList(fmPathInput.value.trim());
      }
    });
  }

  if (fmBtnRefresh) fmBtnRefresh.addEventListener('click', () => requestFileList());

  // Upload button
  if (fmBtnUpload && fmFileInput) {
    fmBtnUpload.addEventListener('click', () => fmFileInput.click());
    fmFileInput.addEventListener('change', (e) => {
      if (e.target.files && e.target.files.length) {
        handleFilesUpload(e.target.files, currentFmDir);
        e.target.value = '';
      }
    });
  }

  // Sidebar File Manager toggle button
  if (btnFileManager) {
    btnFileManager.addEventListener('click', () => {
      if (!fileManagerPanel) return;
      const isVisible = fileManagerPanel.style.display !== 'none';
      fileManagerPanel.style.display = isVisible ? 'none' : 'flex';
      btnFileManager.classList.toggle('active', !isVisible);
      if (!isVisible) requestFileList();
    });
  }

  // Close panel button
  if (btnFmClose && fileManagerPanel) {
    btnFmClose.addEventListener('click', () => {
      fileManagerPanel.style.display = 'none';
      if (btnFileManager) btnFileManager.classList.remove('active');
    });
  }

  // Drag and drop onto screen viewport OR file manager panel
  const dropTargets = [screenViewport, fileManagerPanel].filter(Boolean);
  dropTargets.forEach(target => {
    target.addEventListener('dragenter', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (dropOverlay) dropOverlay.style.display = 'flex';
    });

    target.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (dropOverlay) dropOverlay.style.display = 'flex';
    });
  });

  if (screenViewport) {
    screenViewport.addEventListener('dragleave', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (dropOverlay) dropOverlay.style.display = 'none';
    });

    screenViewport.addEventListener('drop', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (dropOverlay) dropOverlay.style.display = 'none';
      if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
        handleFilesUpload(e.dataTransfer.files, currentFmDir);
      }
    });
  }

  // Initial load: fetch file list and IME on start
  requestFileList('/sdcard/DCIM/Camera');
  setTimeout(() => {
    sendControl(JSON.stringify({ cmd: 'getIme' }));
    fetch('/api/ime').then(r => r.json()).then(data => {
      if (data && data.default_ime_name) {
        const badge = document.getElementById('valImeName');
        if (badge) badge.textContent = `IME: ${data.default_ime_name}`;
      }
    }).catch(() => {});
  }, 1000);
})();

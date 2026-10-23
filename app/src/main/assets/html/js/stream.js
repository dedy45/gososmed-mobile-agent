/**
 * GoSosmed Remote Browser Viewer — Stream Engine (vitRemote 1:1)
 * Handles fMP4 muxing (MSE), WebCodecs hardware decoding, JPEG fallback, and PCM audio.
 * Zero dependency on DOM event binding.
 */
(() => {
  // =========================================================================
  // Minimal H.264 -> Fragmented MP4 (fMP4) Muxer for MSE
  // Runs on ALL browsers over plain HTTP LAN without WebCodecs secure context!
  // =========================================================================
  const U = a => new Uint8Array(a),
        u32 = n => [n >>> 24 & 255, n >>> 16 & 255, n >>> 8 & 255, n & 255],
        str = s => [...s].map(c => c.charCodeAt(0)),
        Z = n => new Array(n).fill(0);

  function cat(parts) {
    let n = 0;
    for (const p of parts) n += p.length;
    const o = new Uint8Array(n);
    let i = 0;
    for (const p of parts) { o.set(p, i); i += p.length; }
    return o;
  }

  function box(t, ...p) {
    const b = cat(p), o = new Uint8Array(b.length + 8);
    o.set(u32(b.length + 8));
    o.set(str(t), 4);
    o.set(b, 8);
    return o;
  }

  const MATRIX = [0, 1, 0, 0, ...Z(12), 0, 1, 0, 0, ...Z(12), 64, 0, 0, 0];

  function nalus(d) {
    const out = [];
    let s = -1;
    for (let i = 0; i + 2 < d.length; i++) {
      if (d[i] === 0 && d[i + 1] === 0 && d[i + 2] === 1) {
        if (s >= 0) {
          let e = i;
          while (e > s && d[e - 1] === 0) e--;
          out.push(d.subarray(s, e));
        }
        s = i + 3;
        i += 2;
      }
    }
    if (s >= 0) out.push(d.subarray(s));
    return out;
  }

  function initSeg(sps, pps, w, h) {
    const avcC = box('avcC', U([1, sps[1], sps[2], sps[3], 0xff, 0xe1, sps.length >> 8, sps.length & 255, ...sps, 1, pps.length >> 8, pps.length & 255, ...pps]));
    const avc1 = box('avc1', U([...Z(6), 0, 1, ...Z(16), w >> 8, w & 255, h >> 8, h & 255, 0, 0x48, 0, 0, 0, 0x48, 0, 0, 0, 0, 0, 0, 0, 1, ...Z(32), 0, 0x18, 0xff, 0xff]), avcC);
    const stbl = box('stbl', box('stsd', U([0, 0, 0, 0, 0, 0, 0, 1]), avc1), box('stts', U(Z(8))), box('stsc', U(Z(8))), box('stsz', U(Z(12))), box('stco', U(Z(8))));
    const minf = box('minf', box('vmhd', U([0, 0, 0, 1, ...Z(8)])), box('dinf', box('dref', U([0, 0, 0, 0, 0, 0, 0, 1]), box('url ', U([0, 0, 0, 1])))), stbl);
    const mdia = box('mdia', box('mdhd', U([...Z(12), ...u32(1000), 0, 0, 0, 0, 0x55, 0xc4, 0, 0])), box('hdlr', U([...Z(8), ...str('vide'), ...Z(12), ...str('GoSosmed'), 0])), minf);
    const trak = box('trak', box('tkhd', U([0, 0, 0, 7, ...Z(8), 0, 0, 0, 1, ...Z(20), ...Z(4), ...MATRIX, ...u32(w * 65536), ...u32(h * 65536)])), mdia);
    const moov = box('moov', box('mvhd', U([...Z(12), ...u32(1000), ...Z(4), 0, 1, 0, 0, 1, 0, ...Z(10), ...MATRIX, ...Z(24), 255, 255, 255, 255])), trak, box('mvex', box('trex', U([0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1]))));
    return cat([box('ftyp', U([...str('isom'), 0, 0, 2, 0, ...str('isom'), ...str('iso2'), ...str('avc1'), ...str('mp41')])), moov]);
  }

  function fragment(seq, dts, dur, key, data) {
    const traf = box('traf', box('tfhd', U([0, 2, 0, 0, 0, 0, 0, 1])), box('tfdt', U([1, 0, 0, 0, ...u32(Math.floor(dts / 4294967296)), ...u32(dts >>> 0)])), box('trun', U([0, 0, 7, 1, 0, 0, 0, 1, ...u32(108), ...u32(dur), ...u32(data.length), ...(key ? [2, 0, 0, 0] : [1, 1, 0, 0])])));
    return cat([box('moof', box('mfhd', U([0, 0, 0, 0, ...u32(seq)])), traf), box('mdat', data)]);
  }

  function hex(n) { return n.toString(16).padStart(2, '0'); }

  // =========================================================================
  // Dual-Engine State & DOM Elements
  // =========================================================================
  const vid = document.getElementById('screenVideo');
  const cv = document.getElementById('screenCanvas');
  const cx = cv ? cv.getContext('2d') : null;
  const img = document.getElementById('screenImg');
  const tip = document.getElementById('streamWaitingTip');
  const phoneFrame = document.getElementById('phoneFrame');

  const valFps = document.getElementById('valFps');
  const valRes = document.getElementById('valRes');
  const valEngine = document.getElementById('valEngine');
  const engineBadge = document.getElementById('engineBadge');

  let framesRendered = 0;
  let lastFpsTime = performance.now();
  let fpsCount = 0;
  let lastJpg = null;

  // MSE State (Timescale 1000 / 30 FPS = ~33.3ms)
  const P = { ms: null, sb: null, queue: [], ready: false, key: false, seq: 1, dts: 0, dur: 33, w: 0, h: 0 };
  // WebCodecs State
  const WC = { dec: null, key: false, ts: 0 };
  // Codec aktif: 1 AVC, 2 HEVC, 3 AV1. Dipakai untuk memilih jalur frame.
  let curCodecId = 1;
  const hasWebCodecs = ('VideoDecoder' in window);

  const log = (msg, type = 'info') => {
    if (window.GoApp && typeof window.GoApp.log === 'function') {
      window.GoApp.log(msg, type);
    } else {
      console.log(`[${type}] ${msg}`);
    }
  };

  function showMode(m) {
    showMode.current = m;
    if (vid) vid.style.display = (m === 'video') ? 'block' : 'none';
    if (cv) cv.style.display = (m === 'canvas') ? 'block' : 'none';
    if (img) img.style.display = (m === 'image') ? 'block' : 'none';
    if (tip) tip.style.display = m ? 'none' : 'flex';
  }

  function curEl() {
    if (cv && cv.style.display !== 'none') return cv;
    if (img && img.style.display !== 'none') return img;
    return vid;
  }

  function onFrameRendered(mode, engineName) {
    showMode(mode);
    if (valEngine) valEngine.textContent = engineName;
    framesRendered++;
    fpsCount++;

    const now = performance.now();
    const elapsed = now - lastFpsTime;
    if (elapsed >= 1000) {
      if (valFps) valFps.textContent = (fpsCount * 1000 / elapsed).toFixed(1);
      fpsCount = 0;
      lastFpsTime = now;
    }
  }

  // =========================================================================
  // Engine Pipeline: onConfig & onFrame (H.264 Annex-B)
  // =========================================================================
  function onConfig(d, serverCodec) {
    const w = (d[1] << 8) | d[2];
    const h = (d[3] << 8) | d[4];
    // Byte 5 = codec id (1 AVC, 2 HEVC, 3 AV1). Paket lama tanpa id
    // diperlakukan sebagai AVC supaya viewer lama tetap jalan.
    const codecId = d.length > 6 ? d[5] : 1;
    const body = d.subarray(6);
    curCodecId = codecId;
    window.__gosoCodec = serverCodec || null;

    if (valRes) valRes.textContent = `${w}x${h}`;
    if (w > 0 && h > 0) {
      const aspect = (w / h).toFixed(4);
      document.documentElement.style.setProperty('--stream-aspect', aspect);
      if (phoneFrame) phoneFrame.classList.toggle('landscape', w > h);
    }

    if (codecId === 2) return onConfigHevc(body, w, h);
    return onConfigAvc(body, w, h);
  }

  // ---------------------------------------------------------------------
  // HEVC: WebCodecs saja. Annex-B dikirim mentah, parameter set ikut di
  // dalam keyframe, jadi `description` tidak perlu (dan tidak boleh) diisi.
  // Muxer MSE tidak dipakai karena belum membangun box hvcC.
  // ---------------------------------------------------------------------
  function onConfigHevc(body, w, h) {
    let vps = null, sps = null, pps = null;
    for (const n of nalus(body)) {
      const t = (n[0] >> 1) & 0x3F;
      if (t === 32) vps = n;
      else if (t === 33) sps = n;
      else if (t === 34) pps = n;
    }
    if (!sps) { log('HEVC: SPS tidak ditemukan di paket config', 'error'); return; }

    if (!hasWebCodecs) {
      // HTTP LAN: tidak ada VideoDecoder. Minta AVC, satu-satunya codec
      // yang bisa dimux ke fMP4.
      log('HEVC butuh WebCodecs (HTTPS :8766) — meminta AVC', 'warn');
      requestCodec('video/avc');
      return;
    }

    const codecStr = window.__gosoCodec || hevcCodecString(vps, sps);
    if (!codecStr) { log('HEVC: gagal membangun codec string — meminta AVC', 'error'); requestCodec('video/avc'); return; }

    WC.key = false;
    WC.cfgW = w;
    WC.cfgH = h;
    WC.codecStr = codecStr;

    try { if (WC.dec) WC.dec.close(); } catch (_) {}
    WC.dec = new VideoDecoder({
      output: (f) => {
        try {
          if (cv.width !== f.displayWidth || cv.height !== f.displayHeight) {
            cv.width = f.displayWidth;
            cv.height = f.displayHeight;
          }
          if (cx) cx.drawImage(f, 0, 0, cv.width, cv.height);
          onFrameRendered('canvas', 'WebCodecs HEVC');
        } finally {
          f.close();
        }
      },
      error: (e) => {
        log(`HEVC decoder error: ${e && e.message ? e.message : e} — meminta AVC`, 'error');
        try { WC.dec.close(); } catch (_) {}
        WC.dec = null;
        WC.key = false;
        requestCodec('video/avc');
      }
    });

    try {
      WC.dec.configure({ codec: codecStr, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware' });
      if (valEngine) valEngine.textContent = 'WebCodecs HEVC';
      if (engineBadge) engineBadge.textContent = 'HEVC Hardware';
      log(`VideoDecoder HEVC dikonfigurasi: ${codecStr} (${w}x${h})`, 'success');
    } catch (err) {
      try {
        WC.dec.configure({ codec: codecStr, optimizeForLatency: true, hardwareAcceleration: 'prefer-software' });
        if (valEngine) valEngine.textContent = 'WebCodecs HEVC SW';
        if (engineBadge) engineBadge.textContent = 'HEVC Software';
        log(`HEVC HW gagal, memakai software: ${codecStr}`, 'warn');
      } catch (_) {
        log(`HEVC configure gagal: ${err.message} — meminta AVC`, 'error');
        WC.dec = null;
        WC.key = false;
        requestCodec('video/avc');
      }
    }
  }

  /** Minta server berganti codec, sekali saja (hindari loop reconfigure). */
  let lastRequestedCodec = null;
  function requestCodec(mime) {
    if (lastRequestedCodec === mime) return;
    lastRequestedCodec = mime;
    try {
      if (window.GoApp && typeof window.GoApp.sendControl === 'function') {
        window.GoApp.sendControl(JSON.stringify({ cmd: 'setCodec', mime }));
      }
    } catch (_) {}
  }

  /**
   * Fallback: bangun `hev1.P.T.L` dari VPS/SPS bila server tidak mengirim
   * string codec (mis. klien lama). Bukan pengganti parser penuh — cukup
   * untuk profile Main 8-bit yang dipakai encoder Android.
   */
  function hevcCodecString(vps, sps) {
    try {
      const rbsp = unescapeRbsp(sps.subarray(2));
      let bitPos = 0;
      const bit = () => {
        const v = (rbsp[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
        bitPos++;
        return v;
      };
      const bits = (n) => { let v = 0; for (let i = 0; i < n; i++) v = (v << 1) | bit(); return v; };
      bits(4); bits(1); bits(1); bits(6);          // sps_video_parameter_set_id..max_sub_layers
      const maxSub = bits(3);
      bits(1); bits(16);                            // temporal_id_nesting + reserved 0xffff
      const space = bits(2), tier = bit(), profile = bits(5);
      const compat = [bits(8), bits(8), bits(8), bits(8)];
      const cons = [];
      for (let i = 0; i < 6; i++) cons.push(bits(8));
      const level = bits(8);
      const hex = (v) => v.toString(16).toUpperCase().padStart(2, '0');
      const compatVal = (compat[0] | (compat[1] << 8) | (compat[2] << 16) | (compat[3] << 24)) >>> 0;
      let out = 'hev1.' + ['', 'A', 'B', 'C'][space] + profile + '.' +
        compatVal.toString(16).toUpperCase().padStart(8, '0') + '.' +
        (tier ? 'H' : 'L') + level;
      while (cons.length && cons[cons.length - 1] === 0) cons.pop();
      for (const c of cons) out += '.' + hex(c);
      return out;
    } catch (_) { return null; }
  }

  function unescapeRbsp(d) {
    const out = [];
    let zeros = 0;
    for (let i = 0; i < d.length; i++) {
      const b = d[i];
      if (zeros >= 2 && b === 3) { zeros = 0; continue; }
      out.push(b);
      zeros = b === 0 ? zeros + 1 : 0;
    }
    return Uint8Array.from(out);
  }

  function onConfigAvc(body, w, h) {
    let sps, pps;
    for (const n of nalus(body)) {
      const t = n[0] & 31;
      if (t === 7) sps = n;
      else if (t === 8) pps = n;
    }
    if (!sps || !pps) return;

    // Mode A: WebCodecs VideoDecoder (Secure Context: HTTPS / localhost)
    if (hasWebCodecs) {
      try { if (WC.dec) WC.dec.close(); } catch (_) {}
      WC.key = false;
      WC.cfgW = w;
      WC.cfgH = h;

      const codecStr = `avc1.${hex(sps[1])}${hex(sps[2])}${hex(sps[3])}`;
      WC.codecStr = codecStr;

      // Guard: a decoder must never leave the viewer stuck. On any fatal
      // error we tear WebCodecs down and hand over to MSE in the same call.
      let demoted = false;
      const demote = (reason) => {
        if (demoted) return;
        demoted = true;
        WC.dec = null;
        WC.key = false;
        log(`WebCodecs dinonaktifkan (${reason}) → beralih ke MSE fMP4`, 'warn');
        // Re-run configuration through the MSE branch with the SAME csd.
        try { configureMse(sps, pps, w, h); } catch (err) {
          log(`MSE juga gagal: ${err.message}`, 'error');
        }
      };

      WC.dec = new VideoDecoder({
        output: (f) => {
          try {
            // The decoder may emit a different size than configured (rotation,
            // encoder re-init). Always trust the frame, never the old canvas size.
            if (cv.width !== f.displayWidth || cv.height !== f.displayHeight) {
              cv.width = f.displayWidth;
              cv.height = f.displayHeight;
            }
            if (cx) cx.drawImage(f, 0, 0, cv.width, cv.height);
            onFrameRendered('canvas', 'WebCodecs');
          } finally {
            f.close(); // Zero-leak cleanup
          }
        },
        error: (e) => {
          demote(e && e.message ? e.message : 'decoder error');
        }
      });

      try {
        WC.dec.configure({
          codec: codecStr,
          optimizeForLatency: true,
          hardwareAcceleration: 'prefer-hardware'
        });
        if (valEngine) valEngine.textContent = 'WebCodecs';
        if (engineBadge) engineBadge.textContent = 'WebCodecs Hardware';
        log(`VideoDecoder dikonfigurasi: ${codecStr} (${w}x${h})`, 'success');
        return;
      } catch (err) {
        // configure() throws synchronously for unsupported codec/profile.
        // Try the software decoder before giving up on WebCodecs entirely.
        try {
          WC.dec.configure({
            codec: codecStr,
            optimizeForLatency: true,
            hardwareAcceleration: 'prefer-software'
          });
          if (valEngine) valEngine.textContent = 'WebCodecs SW';
          if (engineBadge) engineBadge.textContent = 'WebCodecs Software';
          log(`VideoDecoder HW gagal, memakai software: ${codecStr}`, 'warn');
          return;
        } catch (_) {
          demote(`configure gagal: ${err.message}`);
          return;
        }
      }
    }

    configureMse(sps, pps, w, h);
  }

  // Mode B: MSE fMP4 muxer (plain HTTP LAN, or WebCodecs fallback)
  function configureMse(sps, pps, w, h) {
    if (!window.MediaSource) {
      log('Browser tidak mendukung MediaSource (MSE)', 'error');
      return;
    }

    const mime = `video/mp4; codecs="avc1.${hex(sps[1])}${hex(sps[2])}${hex(sps[3])}"`;
    if (!MediaSource.isTypeSupported(mime)) {
      log(`MIME tidak didukung browser: ${mime}`, 'error');
      return;
    }

    // Tear down any previous MSE graph. Keeping the old SourceBuffer while the
    // encoder changes resolution causes appendBuffer() to throw QuotaExceeded
    // and the picture freezes — which is exactly what happened on rotation.
    if (P.ms) {
      try { P.sb = null; } catch (_) {}
      try { if (vid && vid.src) { vid.pause(); vid.removeAttribute('src'); vid.load(); } } catch (_) {}
      try { URL.revokeObjectURL(vid.src); } catch (_) {}
      P.ms = null;
      P.ready = false;
      P.queue = [];
      P.key = false;
    }

    Object.assign(P, { queue: [initSeg(sps, pps, w, h)], ready: false, key: false, seq: 1, dts: 0, dur: 33, w, h });
    const ms = new MediaSource();
    P.ms = ms;
    if (vid) vid.src = URL.createObjectURL(ms);

    ms.addEventListener('sourceopen', () => {
      if (P.ms !== ms) return;
      try {
        P.sb = ms.addSourceBuffer(mime);
        P.sb.mode = 'segments';
      } catch (err) {
        log(`addSourceBuffer gagal: ${err.message}`, 'error');
        return;
      }
      P.sb.addEventListener('updateend', () => {
        pumpSB();
        if (vid && vid.paused) vid.play().catch(() => {});
      });
      P.sb.addEventListener('error', () => {
        log('SourceBuffer error — membangun ulang pipeline MSE', 'warn');
        P.ready = false;
        P.queue = [];
      });
      P.ready = true;
      pumpSB();
    }, { once: true });

    if (valEngine) valEngine.textContent = 'MSE fMP4';
    if (engineBadge) engineBadge.textContent = 'MSE MediaSource';
    log(`MSE SourceBuffer aktif: ${mime} (${w}x${h})`, 'success');
  }

  function onFrame(d) {
    const key = (d[1] === 1);

    // Auto-configure from keyframe if decoder is not yet configured (vitRemote benchmark).
    // Hanya untuk AVC: header NALU HEVC memakai 6 bit, jadi `n[0] & 31` akan
    // salah mengenali VPS(32)/SPS(33) sebagai tipe H.264.
    if (curCodecId === 1 && !WC.dec && !P.ms && key) {
      const frameNalus = nalus(d.subarray(2));
      let sps = null, pps = null;
      for (const n of frameNalus) {
        const t = n[0] & 31;
        if (t === 7) sps = n;
        else if (t === 8) pps = n;
      }
      if (sps && pps) {
        const w = (P.w > 0 ? P.w : 720);
        const h = (P.h > 0 ? P.h : 1600);
        const cfg = new Uint8Array(5 + 4 + sps.length + 4 + pps.length);
        cfg[0] = 3;
        cfg[1] = (w >> 8) & 0xFF;
        cfg[2] = w & 0xFF;
        cfg[3] = (h >> 8) & 0xFF;
        cfg[4] = h & 0xFF;
        let off = 5;
        cfg.set([0, 0, 0, 1], off); off += 4;
        cfg.set(sps, off); off += sps.length;
        cfg.set([0, 0, 0, 1], off); off += 4;
        cfg.set(pps, off);
        onConfig(cfg);
      }
    }

    // WebCodecs path
    if (WC.dec) {
      // A closed/failed decoder must be torn down, never fed.
      if (WC.dec.state === 'closed') {
        WC.dec = null;
        WC.key = false;
        log('VideoDecoder tertutup — menunggu keyframe berikutnya', 'warn');
      } else {
        if (!WC.key && !key) return;
        if (WC.dec.decodeQueueSize > 8 && !key) return; // skip lag
        WC.key = true;
        const data = d.subarray(2);
        const ts = (WC.ts += 33333);
        try {
          WC.dec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ts, data }));
        } catch (err) {
          // decode() throws on wrong dimensions / malformed chunk.
          // Tear down instead of silently dropping every later frame.
          log(`decode() gagal: ${err.message} — beralih ke MSE`, 'warn');
          try { WC.dec.close(); } catch (_) {}
          WC.dec = null;
          WC.key = false;
        }
        return;
      }
    }

    // MSE fMP4 path — hanya AVC. Muxer membangun `avc1`+`avcC`; HEVC butuh
    // `hvc1`+`hvcC` yang belum ada, jadi HEVC tidak boleh masuk sini.
    if (curCodecId !== 1) return;
    if (!P.ms || (!P.key && !key)) return;
    P.key = true;
    const parts = [];
    for (const n of nalus(d.subarray(2))) {
      const t = n[0] & 31;
      if (t === 7 || t === 8 || t === 9) continue;
      parts.push(U(u32(n.length)), n);
    }
    if (!parts.length) return;

    P.queue.push(fragment(P.seq++, P.dts, P.dur, key, cat(parts)));
    P.dts += P.dur;
    pumpSB();
    onFrameRendered('video', 'MSE fMP4');
  }

  function pumpSB() {
    const sb = P.sb;
    if (!P.ready || !sb || sb.updating || !P.queue.length) return;
    try {
      // Keep the buffer bounded: drop already-played media before appending.
      if (vid && vid.buffered.length) {
        const start = vid.buffered.start(0);
        const behind = vid.currentTime - start;
        if (behind > 20) {
          try { sb.remove(0, vid.currentTime - 5); } catch (_) {}
          return;
        }
      }
      sb.appendBuffer(P.queue.shift());
    } catch (err) {
      if (err && err.name === 'QuotaExceededError') {
        // Buffer full: evict old data and retry on the next updateend.
        // The previous code cleared the whole queue here, which froze the
        // picture permanently after a rotation.
        log('SourceBuffer penuh — membuang data lama', 'warn');
        try {
          const t = vid && vid.buffered.length ? vid.buffered.end(vid.buffered.length - 1) : 0;
          if (t > 10) sb.remove(0, t - 5);
        } catch (_) {}
        return;
      }
      log(`appendBuffer gagal: ${err && err.message}`, 'error');
      P.queue = [];
    }
  }

  // Keep MSE playhead at live edge smoothly without rough seeking
  setInterval(() => {
    if (!vid || !vid.buffered.length) return;
    const end = vid.buffered.end(vid.buffered.length - 1);
    const lag = end - vid.currentTime;
    if (lag > 0.08) {
      vid.playbackRate = 1.1; // Accelerate 10% if lagging slightly
    } else {
      vid.playbackRate = 1.0;
    }
    if (vid.paused) vid.play().catch(() => {});
  }, 100);

  function onJpeg(d) {
    const u = URL.createObjectURL(new Blob([d.subarray(1)], { type: 'image/jpeg' }));
    if (!img) return;
    img.onload = () => {
      if (lastJpg && lastJpg !== u) URL.revokeObjectURL(lastJpg);
      lastJpg = u;
      if (img.naturalWidth && img.naturalHeight) {
        const w = img.naturalWidth, h = img.naturalHeight;
        if (valRes) valRes.textContent = `${w}x${h}`;
        const aspect = (w / h).toFixed(4);
        document.documentElement.style.setProperty('--stream-aspect', aspect);
        if (phoneFrame) phoneFrame.classList.toggle('landscape', w > h);
      }
    };
    img.src = u;
    onFrameRendered('image', 'JPEG Fallback');
  }

  // =========================================================================
  // Internal Phone Audio Streaming (Web Audio API) — gapless scheduler
  //
  // ROOT CAUSE FIXED: the wire packet is [opcode=5][PCM bytes...]. After
  // data.subarray(1) the byteOffset is 1 (ODD), so
  //   new Int16Array(buf, 1, n)
  // throws "RangeError: Byte offset is not aligned" on EVERY packet and the
  // old `catch (_) {}` swallowed it — audio was silently dead.
  // Fix: memcpy into a reusable 0-aligned staging buffer (no per-packet GC).
  //
  // Also: sources are scheduled back-to-back on a running playhead so the
  // stream is gapless instead of overlapping every ~45 ms.
  // =========================================================================
  const AUDIO_RATE = 44100;
  const AUDIO_CHANNELS = 2; // device captures CHANNEL_IN_STEREO
  const AUDIO_DRIFT_RESET = 0.35; // s — resync when we fall this far behind
  const AUDIO_JITTER = 0.08; // s — target queue depth to absorb network jitter

  let audioCtx = null;
  let isAudioMuted = true;
  let audioStreamDest = null;

  // Reusable 0-aligned scratch (grows on demand, never per-packet alloc)
  let aScratchU8 = new Uint8Array(8192);
  let aScratchI16 = new Int16Array(aScratchU8.buffer);
  let aPlayhead = 0; // absolute AudioContext time of next scheduled chunk
  let onAudioLevel = null; // installed by app.js to drive the UI meter

  const aStats = {
    packets: 0,
    frames: 0,
    errors: 0,
    dropped: 0,
    peak: 0,
    rms: 0,
    buffered: 0,
    lastError: null,
    started: false
  };

  function ensureScratch(bytes) {
    if (bytes <= aScratchU8.length) return;
    let cap = aScratchU8.length;
    while (cap < bytes) cap *= 2;
    aScratchU8 = new Uint8Array(cap);
    aScratchI16 = new Int16Array(aScratchU8.buffer);
  }

  function initAudioContext() {
    if (!audioCtx) {
      const Ctor = window.AudioContext || window.webkitAudioContext;
      if (!Ctor) {
        aStats.errors++;
        aStats.lastError = 'Web Audio API tidak didukung browser ini';
        log(aStats.lastError, 'error');
        return;
      }
      try {
        audioCtx = new Ctor({ sampleRate: AUDIO_RATE, latencyHint: 'interactive' });
        audioStreamDest = audioCtx.createMediaStreamDestination();
        aPlayhead = 0;
        log(`AudioContext siap (${audioCtx.sampleRate} Hz)`, 'success');
      } catch (err) {
        aStats.errors++;
        aStats.lastError = `Gagal membuat AudioContext: ${err.message}`;
        log(aStats.lastError, 'error');
        return;
      }
    }
    if (audioCtx.state === 'suspended') {
      audioCtx.resume().then(() => {
        log(`AudioContext resumed (${audioCtx.state})`, 'info');
      }).catch((err) => {
        aStats.errors++;
        aStats.lastError = `AudioContext resume gagal: ${err.message}`;
        log(aStats.lastError, 'warn');
      });
    }
  }

  function playPcmAudio(pcmData) {
    aStats.packets++;

    if (isAudioMuted) return;
    if (!audioCtx) {
      initAudioContext(); // lazy create on first packet after unmute
      if (!audioCtx) return;
    }
    if (audioCtx.state !== 'running') {
      audioCtx.resume().catch(() => {}); // needs user gesture
      aStats.dropped++;
      return;
    }

    const byteLen = pcmData.byteLength & ~1; // whole 16-bit samples only
    if (byteLen < 4) return;

    try {
      // 1) 0-aligned copy — fixes "RangeError: Byte offset is not aligned"
      ensureScratch(byteLen);
      aScratchU8.set(pcmData.subarray(0, byteLen), 0);
      const int16 = aScratchI16.subarray(0, byteLen >> 1);

      // 2) Interleaved stereo -> deinterleaved planar channels.
      // Buffer stays at the SOURCE rate (44100). Web Audio's own high-quality
      // resampler converts to the device rate; hand-rolled nearest-neighbour
      // conversion only adds artefacts.
      const frames = Math.floor(int16.length / AUDIO_CHANNELS);
      if (frames <= 0) return;

      const buffer = audioCtx.createBuffer(AUDIO_CHANNELS, frames, AUDIO_RATE);
      const left = buffer.getChannelData(0);
      const right = buffer.getChannelData(1);
      let peak = 0, sumSq = 0;
      for (let i = 0, j = 0; i < frames; i++, j += AUDIO_CHANNELS) {
        const l = int16[j] / 32768;
        const r = int16[j + 1] / 32768;
        left[i] = l;
        right[i] = r;
        const a = l < 0 ? -l : l;
        if (a > peak) peak = a;
        sumSq += l * l;
      }
      aStats.peak = peak;
      aStats.rms = Math.sqrt(sumSq / frames);

      // 3) Gapless scheduling on a monotonic playhead.
      // Jitter buffer: always keep ~80 ms queued ahead so normal network jitter
      // cannot starve the graph (which is what makes audio sound robotic).
      const now = audioCtx.currentTime;
      if (aPlayhead < now || aPlayhead - now > AUDIO_DRIFT_RESET) {
        aPlayhead = now + AUDIO_JITTER;
      }
      const source = audioCtx.createBufferSource();
      source.buffer = buffer;
      source.connect(audioCtx.destination);
      if (audioStreamDest) source.connect(audioStreamDest);
      source.start(aPlayhead);
      aPlayhead += frames / AUDIO_RATE;

      aStats.frames += frames;
      aStats.started = true;
      aStats.buffered = Math.max(0, aPlayhead - audioCtx.currentTime);
      if (onAudioLevel) onAudioLevel(aStats.peak, aStats.rms);
    } catch (err) {
      aStats.errors++;
      aStats.lastError = `Audio decode gagal: ${err.name}: ${err.message}`;
      if (aStats.errors <= 3) log(aStats.lastError, 'error');
    }
  }

  // Global Export
  window.GoStream = {
    onConfig,
    onFrame,
    onJpeg,
    playPcmAudio,
    showMode,
    curEl,
    initAudioContext,
    get audioCtx() { return audioCtx; },
    get audioStreamDest() { return audioStreamDest; },
    get isAudioMuted() { return isAudioMuted; },
    set isAudioMuted(val) { isAudioMuted = val; },
    get audioStats() { return aStats; },
    get audioState() {
      return {
        ctx: audioCtx ? audioCtx.state : 'none',
        rate: audioCtx ? audioCtx.sampleRate : 0,
        playhead: aPlayhead,
        now: audioCtx ? audioCtx.currentTime : 0
      };
    },
    set onAudioLevel(fn) { onAudioLevel = fn; },
    get vid() { return vid; },
    get cv() { return cv; },
    get hasWebCodecs() { return hasWebCodecs; },
    reset() {
      showMode(null);
      if (WC.dec) {
        try { WC.dec.close(); } catch (_) {}
        WC.dec = null;
        WC.key = false;
      }
      if (P.ms) {
        try { if (vid && vid.src) URL.revokeObjectURL(vid.src); } catch (_) {}
        P.ms = null;
        P.sb = null;
        P.queue = [];
        P.ready = false;
        P.key = false;
      }
      if (valEngine) valEngine.textContent = 'Stopped';
      if (valFps) valFps.textContent = '0.0';
    }
  };
})();

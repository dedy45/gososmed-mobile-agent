# gososmed-mobile-agent

APK Android untuk **GoSosmed BYOD device cloud** — menghubungkan HP real milik
user ke server otomasi GoSosmed **tanpa PC di tengah dan tanpa adb/root**.

Desain lengkap: `docs/2-architecture/10-PHONE-BYOD-REMOTE-ADB-RESEARCH.md` (di
repo `dedy45/gososmed`, §7.5).

## Pola arsitektur (Opsi C)

```
HP user:  [App sosial (FB/TikTok)]  +  AGENT APK ini
              │  connect OUTBOUND (WSS), auto-reconnect, pairing code
Server:   GoSosmed agenthub (WS registry)  →  transport ADBClient alternatif
              →  adapter platform (fb/tiktok/ig) TANPA DIUBAH
```

Kuncinya: agent menghasilkan hierarchy XML yang **sama persis** dengan format
`uiautomator dump` yang dibaca `internal/mobile/hierarchy.go`
(`ParseHierarchy`), sehingga driver GoSosmed tetap bekerja tanga mengubah
adapter login/verify/publish.

## Status: P0

- [x] Proyek Android + AccessibilityService
- [x] Hierarchy serializer (XML kompatibel `ParseHierarchy`)
- [x] Eksekusi tap/text/global via Accessibility (tanpa root)
- [x] WebSocket outbound client (auto-reconnect + heartbeat)
- [x] Pairing code + device_id persisten
- [x] GitHub Actions build → APK artifact
- [ ] Validasi di redroid (dump XML + tap/text)
- [ ] `internal/agenthub` GoSosmed (P1)

## Build

App dibangun di GitHub Actions (repo ini tidak menjalankan build lokal karena
Windows/Ubuntu dev mesin tidak punya Android SDK).

```bash
# Trigger manual
gh workflow run build-apk --repo dedy45/gososmed-mobile-agent
# Download artifact
gh run download <run-id> -n gososmed-agent-debug
```

## Drive akun via `adb` (validation only)

```bash
# Install APK ke redroid
adb -s 192.168.1.77:5555 install -r app/build/outputs/apk/debug/app-debug.apk
# Aktifkan AccessibilityService
adb -s 192.168.1.77:5555 shell settings put secure enabled_accessibility_services \
  com.gososmed.agent/com.gososmed.agent.AgentAccessibilityService
# Buka MainActivity (uji lokal: dump/tap/text)
adb -s 192.168.1.77:5555 shell am start -n com.gososmed.agent/.MainActivity
# Uji dump XML di device
adb -s 192.168.1.77:5555 shell am start -n com.gososmed.agent/.MainActivity --es cmd dump
```

## Peringatan kepatuhan

APK ini memakai **AccessibilityService untuk otomasi** → Google Play **PUBLIK
melarang & men-ban** kategori ini. Distribusi yang aman: **Play Internal/Closed
Testing** (privat) atau APK side-load. **JANGAN** publikasikan ke production
track tanpa persetujuan Google. (Lihat §7.5.2 di dokumen riset.)

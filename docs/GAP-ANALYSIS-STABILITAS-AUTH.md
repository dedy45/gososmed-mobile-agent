# Gap Analysis — Stabilitas & Auth Multi-User (referensi: droidrun/mobilerun-portal)

> Tanggal: 2026-09-04. Analisis berdasar baca kode nyata (bukan asumsi):
> repo ini (agent APK, main `ba226d2`) + backend GoSosmed (`internal/agenthub`,
> `internal/store/mobile_devices.go`, `internal/accounthttp/handler.go`,
> `internal/httpx/router.go`, `cmd/api/main.go`, migration 00006/00038).

## 1. Status quo (fakta kode)

### Agent APK (repo ini)
- 10 primitif via AccessibilityService: dump (XML kompatibel uiautomator), tap,
  tapByText, setText, back, home, recents, startApp, killApp, hasPackage,
  listPackages + dumpWindows + takeScreenshot.
- Outbound WS (OkHttp): register hello `{device_id, pairing_code}`, heartbeat
  ping 15s, reconnect backoff 2s→30s, eksekusi command di main thread.
- `device_id` = `agent-<uuid8>` dan `pairing_code` = 6 digit **dibuat sendiri
  di HP** (`MainActivity.loadOrCreateDeviceId/loadOrCreatePairingCode`),
  persisten di SharedPreferences; WS URL diketik manual.

### Backend GoSosmed (agenthub)
- Route `GET /v1/agent/ws` publik (tanpa middleware auth) — auth per-device
  lewat `pairing_code` di hello.
- `EnrollOrValidatePairing` (store/mobile_devices.go:250): **enroll-first** —
  koneksi pertama menyimpan kode apa pun yang dibawa agent; koneksi berikutnya
  harus cocok.
- Tabel `mobile_devices` **TIDAK punya kolom `tenant_id`/user** (migration
  00006 + 00038). Device kind=physical bersifat GLOBAL.

## 2. Gap kritis — auth multi-user (penyebab "belum bisa dipakai akun utama")

| # | Gap | Bukti | Dampak |
|---|---|---|---|
| A1 | **Device tidak terikat tenant/user.** Tidak ada kolom tenant di `mobile_devices`; enrollment anonim. | migration 00006/00038; `EnrollOrValidatePairing` | HP siapa pun tidak bisa dibedakan milik siapa — fondasi multi-user tidak ada. |
| A2 | **Pairing code dibuat di HP, bukan diterbitkan dasbor.** Enroll-first = siapa pun yang konek duluan dengan `device_id` apa pun meng-claim device itu. | `MainActivity.kt:329-344`; store enroll-first | Perampasan device (first-claim race), tidak ada bukti kepemilikan. |
| A3 | **Semua user melihat semua device agent + pairing code-nya.** `HandleByodInfo` mengembalikan `devices` + `PairingCode` tanpa filter tenant (tidak mungkin difilter — tidak ada kolom). | accounthttp/handler.go:859-872 | Kebocoran: kode 6-digit device orang lain terlihat → bisa dipakai spoofing/hello. |
| A4 | **Tidak ada gate kepemilikan pada aksi device.** `HandleConnectMobile` hanya gate redroid vs admin; plan/flow/screenshot/dumpWindows untuk kind=physical tidak cek pemilik. | handler.go:1308 (satu-satunya gate), router.go:363-373 | User tenant A bisa menargetkan HP user B (job, screenshot layar HP orang = privasi). |
| A5 | **Tidak ada jalur revoke/unpair.** `Registry.Unregister` ada tapi tidak pernah dipanggil endpoint mana pun; tidak ada rotasi kode. | registry.go:130-135 (caller nol) | HP hilang/dijual tidak bisa dicabut dari sisi server. |
| A6 | Register hello tanpa rate limit (endpoint publik, kode 6 digit = ruang 1 juta). | router.go:390; link.go readLoop | Brute-force pairing mungkin (ada audit log, belum ada lockout). |

### Pola referensi mobilerun-portal yang menutup gap ini
- Identitas **saat upgrade WS**, bukan di hello: header `Authorization: Bearer
  <token>` + `X-User-ID`, `X-Device-ID`, `X-Device-Name`, `X-Remote-Device-Key`
  → device terikat ke user penerbit token sejak handshake.
- Endpoint join per-user (`/v1/providers/personal/join`) — server cloud tahu
  device milik siapa karena token memutuskan.
- (docs/reverse-connection.md, repo mobilerun-portal)

## 3. Gap stabilitas — APK

| # | Gap | Bukti | Dampak |
|---|---|---|---|
| S1 | **WS dimiliki MainActivity, bukan Service.** `private var ws` di Activity; Service hanya menangani command adb P0; `START_STICKY` restart tidak pernah memulai ulang WS. | MainActivity.kt:34,178-190; AgentForegroundService.kt:64-93 | Activity ditutup sistem → koneksi yatim; HP reboot/proses mati → agent tidak pernah konek lagi sampai user buka app manual. |
| S2 | **Tidak ada pingInterval OkHttp** → half-open connection tidak terdeteksi klien. Server pun hanya tahu offline saat readLoop error (socket close), bukan saat diam. | AgentWsClient.kt:47-50; link.go:90-145 | Device tampil "online" padahal mati; command hang sampai timeout. |
| S3 | Reconnect tidak sadar-jaringan (tidak ada ConnectivityManager callback), backoff tanpa jitter. | AgentWsClient.kt:152-164 | Reconnect lambat setelah ganti WiFi/sinyal; reconnect storm saat server restart (semua agent bareng). |
| S4 | Tidak ada mekanisme events perangkat (app enter/exit) ala `events/device` mobilerun — verify flow hanya bisa polling dump. | — | Verifikasi sesi kurang efisien (bisa menyusul, bukan blokir). |

## 4. Gap stabilitas — server

| # | Gap | Bukti | Dampak |
|---|---|---|---|
| V1 | Registry in-memory satu proses; runner menolak BYOD (hanya api-pool pemegang WS). | worker/resolver.go:85 | Skala 2+ replika api tidak bisa — dokumen/keputusan arsitektur perlu eksplisit. |
| V2 | Tidak ada pong/read deadline server-side & tidak ada sweep device stale. | link.go readLoop | "Online palsu" saat jaringan mati diam-diam. |
| V3 | Heartbeat agent = request ping ber-id (masuk `pending` map 15 detik sekali) — ok tapi boros; ping frame WS lebih tepat. | AgentWsClient.kt:140-150 | Minor. |

## 5. Yang sudah BAIK (jangan dirusak)

- Koneksi outbound murni (NAT/CGNAT-aman) — sama dengan pola reverse connection mobilerun. ✅
- Antrean job Postgres (SKIP LOCKED + reaper + retry) di backend solid. ✅
- Format XML dump kompatibel uiautomator → parser server tidak berubah. ✅
- Pairing mismatch → audit log + koneksi ditutup (B1). ✅
- README transparansi + peringatan kepatuhan Play. ✅

## 6. Rencana eksekusi (urutan prioritas)

**M1 — Auth multi-user (backend GoSosmed, 1 commit)**
1. Migration baru: `mobile_devices.tenant_id UUID NULL` + index; tabel
   `device_pairings` (kode single-use, tenant_id, created_by, expires_at, used_at).
2. `POST /v1/mobile/pairing-codes` (auth tenant) — dasbor terbitkan kode.
3. `pairingCheck` baru: validasi kode single-use → enroll device **terikat
   tenant**; enroll-first anonim dihapus (device lama tanpa tenant dianggap
   butuh re-pair).
4. Gate kepemilikan di byod/devices/plan/flow/screenshot/windows:
   `device.tenant_id == claims.TenantID` (admin hanya untuk redroid).
   Pairing code tidak lagi dikirim mentah ke UI (mask).
5. `DELETE /v1/mobile/devices/{serial}` revoke: hapus baris + `Unregister` +
   tutup WS aktif. Rate limit hello gagal (per IP).
6. Test: store integration, fake handler test, rbac matrix, openapi.yaml.

**M2 — APK pairing (repo ini, 1 commit)**
- Field input kode pairing (server-issued), tampilkan error jelas saat ditolak,
  berhenti auto-reconnect saat 401 pairing; kirim `X-Device-Name`.

**M3 — APK stabilitas WS (repo ini, 1 commit)**
- Pindahkan kepemilikan WS ke `AgentForegroundService` (onCreate/onStartCommand
  memulai + restart konek ulang), `pingInterval(15s)` OkHttp,
  ConnectivityManager callback → reconnect instan saat network available,
  backoff + jitter acak.

**M4 — Server liveness (backend, bisa digabung M1)**
- Pong deadline server + sweep device stale registry.

**M5 — opsional menyusul:** QR/deep-link pairing, update checker APK,
WebRTC streaming (menggantikan polling screenshot), triggers ala mobilerun.

## 7. Kriteria "konek live & stabil" (definisi selesai)

1. Dua user tenant berbeda: masing-masing terbitkan kode pairing → dua HP
   berbeda konek → masing-masing HANYA melihat HP-nya sendiri di UI.
2. Job plan/flow di HP milik tenant A ditolak bila dipicu tenant B (403).
3. HP pesawat-mode 30 detik → online kembali otomatis < 30 detik setelah
   jaringan pulih, tanpa sentuhan.
4. Kill app dari recents → service restart → WS konek ulang sendiri.
5. Revoke device dari dasbor → WS terputus dan hello berikutnya ditolak.

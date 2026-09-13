# Kontrak Command Agent APK ↔ Backend (v0.9.0 — Transport ADB Lokal)

> **Untuk siapa dokumen ini:** agent/engineer yang menggarap **backend Go**
> (`internal/agenthub`, `internal/mobile`, `internal/platform/mobileharvest`),
> **frontend Svelte** (`/accounts`, `/byod`), dan **skema database**.
> Dokumen ini adalah **satu sumber kebenaran** untuk ketiga sisi.
>
> **Status:** target v0.9.0. Sebelumnya v0.8.0 memakai transport Shizuku
> (aplikasi pihak ketiga). Mulai v0.9.0, Shizuku **dihapus total** dan
> digantikan transport ADB lokal milik sendiri.
> Repo APK: `gososmed-mobile-agent`. Transport: WebSocket ke
> `wss://api.bamsbung.id/v1/agent/ws`.
>
> **Catatan urutan kerja:** dokumen ini ditulis SEBELUM kode diubah (prinsip
> dokumentasi-dulu). Bila membaca dokumen ini saat kode masih v0.8.0, ambil
> versi sebelumnya dari riwayat git, bukan dokumen ini.

---

## 1. Ringkasan perubahan v0.8.0 → v0.9.0

| Hal | v0.8.0 (Shizuku) | v0.9.0 (ADB lokal) |
|---|---|---|
| Cara dapat hak `shell` (uid 2000) | Aplikasi Shizuku pihak ketiga | **ADB client tertanam** di APK, pairing ke `adbd` lokal |
| Aplikasi tambahan yang harus dipasang user | Shizuku | **Tidak ada** |
| Command `shizukuRequest` | Ada | **Dihapus** |
| Command `adbPair` | — | **Baru** (mulai alur pairing) |
| Nilai `transport` | `shell_shizuku` | `shell_adb` |
| Kode alasan `shizuku_*` | 4 kode | **Dihapus**, diganti 6 kode `adb_*` |
| Nama field capabilities | `shizuku_installed`, `shizuku_running`, `shizuku_permission`, `shizuku_uid`, `shizuku_version`, `shizuku_permission_denied_forever` | `adb_paired`, `adb_connected`, `adb_uid`, `adb_error` |
| `can_shell` | dari Shizuku | **nama sama**, sumbernya `adb_connected` |
| Daftar putih biner `shell` | 8 biner | **Tidak berubah** |
| Aksesibilitas | Jalur cadangan | **Tetap jalur cadangan wajib** |
| Skema database | — | **Tidak berubah** (tanpa migrasi) |

**Yang TIDAK berubah:** 19 dari 21 command (hanya 2 yang berubah), bentuk
amplop pesan, daftar putih biner, aturan preflight, dan seluruh kode error
tingkat akun (`auth_expired`, `no_device`, `device_offline`, `needs_agent`,
`needs_bg_launch_permission`, `platform_error`, `internal`, `not_installed`).

### 1.1 Dua tingkat yang tetap harus dibedakan backend

- `transport == "shell_adb"` → perintah **deterministik** (setara `adb shell`).
- `transport == "accessibility"` → perintah **best-effort**, tunduk blokade
  BAL dan kebijakan OEM (MIUI/HyperOS). `force_stop` **mustahil** di tingkat ini.

**Ini bukan detail kosmetik.** Menyamakan keduanya adalah akar insiden
2026-09-11 (status akun "aktif" padahal tidak ada yang terkoneksi).

---

## 2. Bentuk pesan

Request (server → APK):

```json
{ "id": 42, "cmd": "startApp", "package": "com.facebook.katana", "activity": ".LoginActivity" }
```

Response (APK → server):

```json
{ "id": 42, "ok": true, "result": { "ok": false, "reason": "bal_blocked: ..." } }
```

### Aturan yang sering disalahpahami (sumber bug produksi)

- `ok` **level luar** = "command dikenali dan dieksekusi", **BUKAN** berarti
  aksinya berhasil.
- `result.ok` = **hasil nyata** aksi di layar HP.
- Backend **WAJIB** membaca `result.ok`. Membaca `ok` luar saja adalah akar
  insiden 2026-09-11.
- Setiap kegagalan membawa `result.reason` dengan format `kode: penjelasan`.
  Kode dipakai logika; penjelasan dipakai UI.

### 2.1 Pesan tingkat WebSocket (bukan command)

| Pesan | Arah | Isi |
|---|---|---|
| `register` | APK → server | `device_id`, `pairing_code`, `device_info{model, android_ver, sdk_int, screen, density, agent_version, installed_platforms[]}` |
| `register_ack` | server → APK | `ok`, `error?`, `result{latest_agent_version, apk_url}` |
| heartbeat | APK → server | `cmd:"ping"` tiap 15 detik |
| fanout event | server → APK | tidak ada; event job lewat SSE ke browser |

---

## 3. Daftar command — LENGKAP (21 entri)

Legenda kolom **T**: `A` = accessibility cukup, `S` = butuh shell (ADB),
`A→S` = otomatis pakai `S` bila tersedia, selain itu `A`.

| # | `cmd` | Argumen | `result` | T |
|---|---|---|---|---|
| 1 | `ping` | — | `{pong}` | A |
| 2 | `capabilities` | — | lihat §5 | A |
| 3 | `dump` | — | `{xml, package}` | A |
| 4 | `dumpWindows` | — | `{windows[], activePackage}` | A |
| 5 | `package` | — | `{package}` | A |
| 6 | `startApp` | `package`, `activity?` | `{ok, package, foreground, transport, reason?}` | A→S |
| 7 | `killApp` | `package` | `{ok, mode, force_stop, transport}` | A→S |
| 8 | `tap` | `x`, `y` | `{ok}` | A→S |
| 9 | `tapByText` | `text` | `{ok}` | A |
| 10 | `tapFirstClickable` | — | `{ok, bounds?}` | A |
| 11 | `setText` | `text` | `{ok}` | A |
| 12 | `back` | — | `{ok}` | A |
| 13 | `home` | — | `{ok}` | A |
| 14 | `recents` | — | `{ok}` | A |
| 15 | `notify` | — | `{ok}` | A |
| 16 | `wake` | — | `{ok}` | A→S |
| 17 | `hasPackage` | `package` | `{installed}` | A |
| 18 | `listPackages` | — | `{packages[]}` | A |
| 19 | `screenshot` | `scale?`, `format?`, `quality?` | `{format, data}` | A |
| 20 | `shell` | `command`, `timeoutMs?` | `{ok, exit_code, stdout, stderr, transport, reason?}` | S |
| 21 | **`adbPair`** (baru) | `host`, `port`, `code` | `{ok, paired, adb_connected, reason?}` | — |
| ~~—~~ | ~~`shizukuRequest`~~ | — | **DIHAPUS** | — |

### 3.1 `shell` — batas keamanan (TIDAK berubah)

Hanya biner berikut yang diizinkan APK:

```
am  input  monkey  pm  dumpsys  wm  settings  cmd
```

Perintah lain ditolak dengan `reason = "blocked: perintah 'X' tidak ada di daftar izin agent"`.
Ini sengaja: agent berjalan di HP pribadi pemilik akun, server **tidak boleh**
menjalankan shell sembarangan. Butuh biner baru = ubah APK, bukan bypass.

`timeoutMs` default 15000, dibatasi 1000–60000.

### 3.2 `adbPair` — command baru, alur pairing

Dipanggil server saat user memulai pairing dari UI (atau dari dalam APK sendiri).
`host` biasanya `127.0.0.1` (perangkat mem-pair dirinya sendiri).
`port` dan `code` berasal dari layar Opsi Pengembang > Debug nirkabel > Pairing baru.

`code` berlaku **< 10 menit**. Setelah berhasil, `adb_paired` menjadi true dan
`adb_connected` menyala; `can_shell` ikut menjadi true.

---

## 4. Daftar kode `reason` — LENGKAP (WAJIB ditangani backend)

### 4.1 Kode baru (ADB lokal) — menggantikan seluruh `shizuku_*`

| Kode | Arti | Tindakan backend | Copy frontend disarankan |
|---|---|---|---|
| `adb_not_paired` | Belum pernah pairing / kunci belum diotorisasi | alihkan ke alur pairing, jangan retry job | "Hubungkan otomasi lanjutan (opsional)" |
| `adb_pair_failed` | Kode pairing salah atau kedaluwarsa | minta kode baru | "Kode pairing salah/kedaluwarsa — buat kode baru" |
| `adb_auth_failed` | Kunci RSA ditolak `adbd` | putuskan lalu pairing ulang | "Kunci ditolak, silakan pairing ulang" |
| `adb_disconnected` | Sesi ada tapi terputus | `failed`, minta nyalakan ulang | "Koneksi otomasi terputus — nyalakan Debug nirkabel" |
| `adb_port_unknown` | Port tidak ditemukan otomatis | minta input manual | "Masukkan IP:port dari layar Debug nirkabel" |
| `adb_disabled` | Debug nirkabel mati | `failed` | "Debug nirkabel sedang mati di HP" |

### 4.2 Kode lama yang DIHAPUS
`shizuku_unavailable`, `shizuku_denied`, `shizuku_api_unavailable`, `shizuku_exec_error`.
Backend/frontend harus berhenti menanganinya.

### 4.3 Kode yang TIDAK berubah

| Kode | Arti | Tindakan backend |
|---|---|---|
| `not_installed` | App target tidak terpasang | `failed_permanent`, jangan retry |
| `bal_blocked` | Launch diblokir sistem; izin overlay belum aktif | `failed`, minta perbaikan izin |
| `launch_not_foreground` | Launch terkirim tapi app tidak muncul dalam 10 s | retry maks 1×, lalu `failed` |
| `launch_rejected` | Sistem menolak permintaan launch | `failed` |
| `blocked` | Biner di luar daftar putih | **bug backend**, jangan tampilkan ke user |

> **PENTING — perbaikan diagnostik (bug B2):** saat ini backend menampilkan
> **kegagalan percobaan terakhir** dan membuang alasan asli. Contoh nyata dari
> produksi: error TikTok tertulis `shell ditolak ... shizuku_unavailable`,
> padahal akar aslinya adalah launch aksesibilitas yang tidak muncul di
> foreground. Backend **WAJIB** menyimpan alasan dari **setiap** percobaan
> (mis. `attempts[]`), bukan hanya yang terakhir.

---

## 5. `capabilities` — sumber kebenaran untuk preflight

```json
{
  "agent_version": "0.9.0",
  "api_level": 34,
  "manufacturer": "Xiaomi",
  "model": "...",
  "a11y_ready": true,

  "transport_tier": "shell_adb",
  "last_launch_transport": "shell_adb",

  "adb_paired": true,
  "adb_connected": true,
  "adb_uid": 2000,
  "adb_error": "",

  "can_shell": true,
  "can_launch_app": true,
  "can_force_stop": true,
  "can_inject_input": true,
  "can_screenshot": true,
  "can_draw_overlay": false,
  "overlay_attached": false,
  "battery_unrestricted": true,
  "screen_interactive": true
}
```

**Pemetaan nama field lama → baru** (untuk backend yang sudah ada):

| Lama | Baru |
|---|---|
| `shizuku_running` | `adb_connected` |
| `shizuku_permission` | `adb_paired` (perkiraan terdekat) |
| `shizuku_uid` | `adb_uid` |
| `shizuku_installed` | *(dihapus)* |
| `shizuku_version` | *(dihapus)* |
| `shizuku_permission_denied_forever` | *(dihapus)* |
| `can_shell` | **sama** |

`adb_uid`: `2000` = hak adb/shell (normal), `0` = root, `-1` = belum terhubung.

### 5.1 Aturan preflight yang WAJIB diterapkan backend (fail-closed)

Sebelum mengantrikan job harvest/verify:

1. `a11y_ready == false` → tolak, kode `needs_agent`.
2. `can_launch_app == false` → tolak, kode `needs_bg_launch_permission`.
   (artinya: tidak ada shell **dan** izin overlay mati — job pasti gagal.)
3. `can_force_stop == false` **dan** langkah platform memakai `ForceStopFirst`
   → **jangan** kirim `killApp` lalu menganggap layar bersih. Pakai `home` +
   relaunch, atau tandai hasil sebagai tingkat rendah.
4. `screen_interactive == false` → kirim `wake` lebih dulu.
5. `can_screenshot == false` → jangan minta bukti screenshot (API < 30).
6. Tidak ada device online → tolak, kode `no_device`.

**TTL cache capabilities: maks 60 detik.** Sesi ADB bisa mati kapan pun
(perlu pairing ulang setelah reboot), jadi jangan cache lebih lama.

---

## 6. Yang perlu DIPERBAIKI di backend Go

1. **`internal/platform/mobileharvest/harvest.go`** — kelima platform memakai
   `ForceStopFirst: true`. Hanya sah bila `can_force_stop == true`. Bila false,
   jangan asumsikan layar direset.
2. **Hapus polling foreground ganda.** APK v0.7.1+ sudah menunggu foreground di
   HP (10 s, poll 250 ms) dan hanya membalas `result.ok=true` bila terbukti.
   `EnsureForeground` + `foregroundGrace` di server jadi dobel kerja.
3. **Preflight `capabilities`** (§5) sebelum enqueue.
4. **`internal/agenthub/agentclient.go`** — `Shell` meneruskan `dumpsys`/`wm`/
   `settings`/`cmd` ke `cmd:"shell"`. Sudah ada; pertahankan.
5. **Simpan `transport`** dari hasil `startApp`/`killApp` ke event job.
6. **`resultReason`** harus dipetakan ke `last_error_code`, bukan digabung jadi
   `platform_error` generik.
7. **BARU — `mobile.ShellCapable`** (sudah ditambahkan 2026-09-13): membaca
   `can_shell`. Nama tidak berubah, jadi tidak perlu diedit lagi.
8. **BARU — buang sisa penanganan `shizukuRequest`** dan rujukan
   `shizuku_unavailable` di `internal/accounthttp/preflight.go` serta
   `internal/store/social_account_errors.go`.

---

## 7. Yang perlu DIPERBAIKI di frontend `/accounts` dan `/byod`

1. Tampilkan **tingkat transport** per device: `shell_adb` = "Mode stabil",
   `accessibility` = "Mode terbatas" + alasan.
2. Remediasi spesifik per kode `reason` (§4) — bukan "platform_error" generik.
3. Alur pairing **opsional**, bukan wajib:
   - `adb_paired == false` → tawarkan "Otomasi lanjutan (opsional)".
   - `adb_connected == false` → jelaskan Debug nirkabel harus dinyalakan dan
     **diulang setelah HP reboot**.
   - `adb_error != ""` → tampilkan kode spesifik, bukan pesan umum.
4. Jangan pernah menampilkan "Siap Otomasi" tanpa `verified_at` **dan**
   `a11y_ready` **dan** `can_launch_app`.
5. Hapus seluruh teks yang menyebut "Shizuku" dari i18n
   (`packages/shared/messages/{id,en}.json`) dan dari `errorHint()`.

---

## 8. Pemetaan database (agar tidak ada yang drift)

**Tidak ada migrasi.** Transport tidak menyentuh skema. Yang wajib dijaga:

| Data | Tabel | Dipakai oleh |
|---|---|---|
| Akun sosial | `social_accounts` | `/accounts`, `preflight`, `harvest` |
| Perangkat | `mobile_devices` | `/byod`, `preflight`, `Capabilities` |
| Job | `jobs` (`ref_id` = account id untuk kind akun) | worker, `/jobs` |
| Event job | `job_events` | panel log, SSE |

**Relasi device–akun punya DUA arah** (warisan + migrasi 00047):
```
social_accounts.device_id        → mobile_devices.id      (arah baru)
mobile_devices.social_account_id → social_accounts.id     (arah warisan)
```
Ketidakcocokan dua arah ini sudah pernah menyebabkan loop job
(`requeued_needs_agent` berulang). **Transport tidak boleh menyentuh relasi ini.**

Invarian yang harus dijaga: **satu platform = satu akun `active` per tenant.**
Ini pernah bocor di produksi (dua akun Instagram `active` bersamaan).

---

## 9. Matriks uji yang harus lulus sebelum disebut stabil

| No | Skenario | Harapan |
|---|---|---|
| 1 | ADB tersambung, layar padam, harvest 5 platform | `transport=shell_adb`, `force_stop=true`, handle terbaca |
| 2 | ADB belum di-pair (HP baru reboot) | `reason=adb_not_paired`, UI menawarkan pairing, **bukan** `platform_error` |
| 3 | Kode pairing salah | `reason=adb_pair_failed`, minta kode baru |
| 4 | Debug nirkabel dimatikan saat sesi aktif | `reason=adb_disconnected`, job `failed` jujur |
| 5 | Tanpa ADB, izin overlay aktif | launch jalan lewat accessibility, `transport=accessibility`, `can_force_stop=false` |
| 6 | Tanpa ADB, izin overlay mati | preflight menolak `needs_bg_launch_permission` (job tidak dibuat) |
| 7 | App target tidak terpasang | `reason=not_installed`, job `failed_permanent` |
| 8 | `shell` dengan biner di luar daftar putih | `reason=blocked`, tidak dieksekusi |
| 9 | Dua akun platform sama dibuat | yang kedua ditolak (invarian §8) |
| 10 | `grep -ri shizuku` pada APK | 0 hasil |

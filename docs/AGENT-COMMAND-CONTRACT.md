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
| 3 | `dump` **[DEPRECATED for new app cards; retained for backward compatibility]** | — | `{xml, package}` | A |
| 4 | `dumpWindows` **[DEPRECATED for new app cards; retained for backward compatibility]** | — | `{windows[], activePackage}` | A |
| 5 | `package` | — | `{package}` | A |
| 6 | `startApp` | `package`, `activity?` | `{ok, package, foreground, transport, reason?}` | A→S |
| 7 | `killApp` | `package` | `{ok, mode, force_stop, transport}` | A→S |
| 8 | `tap` **[DEPRECATED for new app cards; retained for backward compatibility]** | `x`, `y` | `{ok}` | A→S |
| 9 | `tapByText` **[DEPRECATED for new app cards; retained for backward compatibility]** | `text` | `{ok}` | A |
| 10 | `tapFirstClickable` **[DEPRECATED for new app cards; retained for backward compatibility]** | — | `{ok, bounds?}` | A |
| 11 | `setText` **[DEPRECATED for new app cards; retained for backward compatibility]** | `text` | `{ok}` | A |
| 12 | `back` | — | `{ok}` | A |
| 13 | `home` | — | `{ok}` | A |
| 14 | `recents` | — | `{ok}` | A |
| 15 | `notify` | — | `{ok}` | A |
| 16 | `wake` | — | `{ok}` | A→S |
| 17 | `hasPackage` | `package` | `{installed}` | A |
| 18 | `listPackages` | — | `{packages[]}` | A |
| 19 | `screenshot` **[DEPRECATED for new app cards; retained for backward compatibility]** | `scale?`, `format?`, `quality?` | `{format, data}` | A |
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

**PERBEDAAN v0.8.0 → v0.9.0 pada `shell` (WAJIB diketahui backend):**

| Hal | v0.8.0 (Shizuku) | v0.9.0 (ADB lokal) |
|---|---|---|
| `stdout` + `stderr` | terpisah | **DIGABUNG** ke `stdout` (`stderr` selalu `""`) |
| Sumber exit code | `Process.waitFor()` | penanda `__GOSOSMED_EXIT__$?` yang ditambahkan APK |
| Bila exit code tak terbaca | — | `exit_code = -1` dan `ok = false` (tidak ditebak) |
| `transport` | `shell_shizuku` | `shell_adb` |

Alasannya teknis: layanan `shell:` pada protokol ADB hanya menyediakan **satu**
aliran dan tidak mengembalikan exit code (shell v2 belum dipakai library).
Konsumen yang ada (`startAppShell`) sudah memeriksa `stdout`+`stderr`
**digabung**, jadi perilakunya tidak berubah. Backend baru **tidak boleh**
mengandalkan `stderr` berisi apa pun untuk transport ADB — periksa `stdout`
dan `exit_code`.

### 3.2 `adbPair` — command baru, alur pairing

Dipanggil server saat user memulai pairing dari UI (atau dari dalam APK sendiri).
`host` biasanya `127.0.0.1` (perangkat mem-pair dirinya sendiri).
`port` dan `code` berasal dari layar Opsi Pengembang > Debug nirkabel > Pairing baru.

`code` berlaku **< 10 menit**.

**DUA BENTUK PEMANGGILAN:**
- Dengan `host`+`port`+`code` → jalankan pairing.
- Tanpa argumen → hanya laporkan status (`paired`, `adb_connected`).

**SEMANTIK PENTING — `result.ok` berarti PAIRING, bukan sesi tersambung:**

Setelah pairing berhasil, penyambungan sesi dijalankan **di belakang** (asinkron),
sehingga `result.ok = true` dapat datang bersama `adb_connected = false`.

Alasannya batas waktu: command agent dibatasi **30 detik** di server
(`agenthub.DefaultTimeout`). Pairing saja memakai sampai ~15 dtk + kelonggaran;
menambahkan discovery mDNS (5 dtk) + socket (6 dtk) secara sinkron akan
melewati 30 dtk — server melaporkan GAGAL padahal pairing sudah berhasil dan
tersimpan. Itu kegagalan palsu yang menyesatkan pemilik HP.

Konsekuensi untuk backend/frontend:
- Perlakukan `result.ok = true` sebagai **"pairing berhasil, kunci tersimpan"**.
- Jangan menyimpulkan sesi hidup dari `result.ok`. Baca **`adb_connected`**,
  dan bila masih `false` biarkan UI menampilkan "menyambung…" lalu perbarui
  pada polling `capabilities`/status berikutnya (TTL cache 60 dtk).
- **Kegagalan `result.ok = false` bersifat terminal untuk percobaan itu** —
  `reason` menjelaskan sebabnya (`adb_pair_failed` kode salah/kedaluwarsa,
  `adb_auth_failed` kunci ditolak, dst).

### 3.3 Kebijakan Deprecasi Command Legacy (v1)

Command legacy berikut ditandai sebagai **[DEPRECATED for new app cards; retained for backward compatibility]**:
- `dump` & `dumpWindows`: digantikan oleh `observe` (Protocol v2) yang mengumpulkan multi-window hierarchy, orientasi, display metrics, screen fingerprint, dan tree hash secara terkoordinasi.
- `tap`, `tapByText`, `tapFirstClickable`, `setText`: digantikan oleh kombinasi `resolve` + `actNode` / `actAndVerify` (Protocol v2) yang deterministik, memiliki scoring kandidat, proteksi ambiguitas (zero ambiguous taps), resolusi clickable ancestor, dan verifikasi transisi settle.
- `screenshot`: digantikan oleh synchronized frame capture di dalam `observe` dan streaming preview terkuantisasi via `startDebugFrames` / `stopDebugFrames`.

**Kompatibilitas:** Seluruh command legacy tetap berfungsi penuh pada APK untuk memastikan alur kartu aplikasi lama tidak rusak. Namun, seluruh kartu aplikasi produksi baru WAJIB menggunakan command Protocol Version 2 (§10).

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

---

## 10. Protocol Version 2 — Deterministic Android Portal

Protokol Version 2 dirancang untuk mengatasi kelemahan dan non-determinisme pada protokol v1 (seperti envelope yang kontradiktif, tap berbasis teks tanpa scoring, tidak adanya sinkronisasi antara hierarchy dan screenshot, serta tidak adanya proteksi ambiguitas).

### 10.1 Request Envelope Schema (v2)

Setiap request dari server ke APK pada Protocol v2 memiliki format:

```json
{
  "protocol_version": 2,
  "request_id": "req-uuid-or-ulid",
  "cmd": "observe",
  "deadline_ms": 10000,
  "args": {}
}
```

| Field | Tipe | Keterangan |
|---|---|---|
| `protocol_version` | integer | Bernilai `2` untuk protokol v2. Jika tidak ada atau bernilai `1`, ditangani sebagai legacy v1. |
| `request_id` | string | ID unik per request (UUID/ULID). Wajib di-echo kembali pada response. |
| `cmd` | string | Nama command canonical v2. |
| `deadline_ms` | long | Batas waktu eksekusi dalam milidetik (default 10000ms). APK memeriksa deadline sebelum langkah mahal. |
| `args` | object | Parameter spesifik command. |

### 10.2 Response Envelope Schema (v2)

Setiap response dari APK ke server pada Protocol v2 memiliki format:

```json
{
  "protocol_version": 2,
  "request_id": "req-uuid-or-ulid",
  "ok": true,
  "retryable": false,
  "result": {},
  "timing": {
    "started_at_ms": 1728000000000,
    "completed_at_ms": 1728000000150,
    "duration_ms": 150
  }
}
```

Bila terjadi kegagalan (`ok: false`):

```json
{
  "protocol_version": 2,
  "request_id": "req-uuid-or-ulid",
  "ok": false,
  "reason_code": "ambiguous",
  "retryable": false,
  "result": {},
  "timing": {
    "started_at_ms": 1728000000000,
    "completed_at_ms": 1728000000020,
    "duration_ms": 20
  }
}
```

### 10.3 Aturan Integritas Envelope (Invariant)

1. **Top-level `ok` adalah kebenaran final (ground truth):** Tidak ada lagi pola v1 di mana outer `ok: true` namun aksi internal gagal.
2. **`reason_code` WAJIB saat `ok == false`:** Jika perintah gagal, `reason_code` kanonikal harus disertakan; sebaliknya saat `ok == true`, `reason_code` wajib bernilai null / tidak disertakan.
3. **Nested `result.ok` dilarang bertentangan:** Jika objek `result` memiliki field `ok`, nilainya tidak boleh berbeda dari top-level `ok`. Pelanggaran memicu `IllegalArgumentException` pada parser APK.
4. **Request tanpa versi:** Request tanpa `protocol_version` diproses oleh adapter v1 demi kompatibilitas mundur.
5. **Versi tidak didukung:** Versi selain 1 dan 2 ditolak seketika dengan `reason_code: "protocol_mismatch"`.

### 10.4 Command Baru Protocol Version 2

| Command | Argumen Utama | Hasil Utama (`result`) | Deskripsi |
|---|---|---|---|
| `observe` | `include_nodes`, `include_image`, `max_depth` | `snapshot_id`, `display`, `foreground`, `tree_hash`, `screen_fingerprint`, `quality`, `nodes[]`, `image` | Mengumpulkan multi-window hierarchy, orientasi, display metrics, hash integritas, dan screenshot tersinkronisasi. |
| `resolve` | `snapshot_id`, `selector`, `ambiguity_threshold` | `candidates[]`, `selected_node_id`, `ambiguity_state`, `clickable_ancestor_id` | Menilai kandidat node dengan 8 tier selektor dan berhenti aman bila ambigu (zero ambiguous taps). |
| `actNode` | `snapshot_id`, `node_id`, `action`, `postcondition_settle_ms` | `action`, `action_type`, `performed`, `settled` | Menjalankan aksi native (`ACTION_CLICK`, `ACTION_SET_TEXT`, dll.) atau fallback gesture terukur pada node yang sudah teresolusi. |
| `actAndVerify` | `snapshot_id`, `node_id`, `action`, `expected_screen`, `settle_timeout_ms` | `action_performed`, `verified`, `before_snapshot_id`, `after_snapshot_id`, `screen_fingerprint` | Menjalankan aksi dan memverifikasi transisi layar telah settle sebelum menyatakan sukses. |
| `waitForNode` | `selector`, `timeout_ms`, `interval_ms` | `found`, `node_id`, `snapshot_id`, `elapsed_ms` | Polling hierarki sampai node dengan kriteria selektor muncul di layar atau batas waktu habis. |
| `waitForScreen` | `target_package`, `fingerprint`, `timeout_ms` | `matched`, `current_package`, `screen_fingerprint`, `elapsed_ms` | Polling transisi sampai layar dan paket aplikasi target stabil sesuai fingerprint. |
| `cancelAction` | `target_request_id` | `cancelled` (boolean) | Membatalkan aksi asinkron in-flight (`waitForNode`, `waitForScreen`, `actNode`). Bila target aktif ditemukan di memori, mengembalikan `{"cancelled": true}` dan target abort seketika dengan `reason_code: "cancelled"`. Bila target tidak ditemukan/selesai, mengembalikan `{"cancelled": false}`. |
| `startDebugFrames` | `fps`, `format`, `quality`, `scale`, `session_id` | `status: "started"`, `queue_stats` | Memulai streaming frame debug downsampled (antrean terbatas kapasitas minimum 1, in-memory ByteArray, drop-oldest, eviksi ACK kadaluwarsa, zero disk write). |
| `stopDebugFrames` | (opsional) | `status: "stopped"`, `queue_stats` | Menghentikan sesi streaming frame debug dan membersihkan antrean. |

### 10.5 Daftar 18 Kode Alasan Kanonikal (Canonical Reason Codes)

| Kode `reason_code` | Retryable | Keterangan & Kondisi Terjadi |
|---|---|---|
| `no_node` | Tidak | Tidak ditemukan node yang cocok dengan selektor pada snapshot aktif. |
| `ambiguous` | Tidak | Dua atau lebih kandidat node memiliki skor terlalu dekat dalam ambang batas ambiguitas (mencegah klik salah target). |
| `invalid_bounds` | Tidak | Bounding box node target bernilai negatif, 0x0, atau berada sepenuhnya di luar layar. |
| `stale_snapshot` | Ya | Snapshot ID yang dirujuk request sudah usang (layar telah berubah atau snapshot baru telah dibuat). |
| `wrong_package` | Tidak | Foreground package saat eksekusi berbeda dari paket aplikasi target yang disyaratkan. |
| `unstable_tree` | Ya | Hierarki pohon aksesibilitas berubah-ubah di tengah pembacaan (animasi atau loading). |
| `action_not_supported` | Tidak | Node view tidak mendukung aksi aksesibilitas yang diminta (misal: mencoba setText pada node non-editable). |
| `action_rejected` | Tidak | Sistem UI view menolak eksekusi `performAction()`. |
| `gesture_cancelled` | Ya | Injeksi gesture aksesibilitas dibatalkan oleh WindowManager. |
| `screen_not_changed` | Ya | Aksi telah dijalankan namun tampilan layar dan fingerprint tidak berubah pasca settle window. |
| `unexpected_screen` | Tidak | Layar berpindah ke activity atau dialog checkpoint yang tidak diharapkan. |
| `blocked_dialog` | Tidak | Interaksi terhalang oleh dialog sistem OS, izin runtime, atau overlay keamanan. |
| `timeout` | Ya | Batas waktu operasi (deadline) terlampaui sebelum kondisi terpenuhi. |
| `cancelled` | Tidak | Operasi dibatalkan secara eksplisit oleh server melalui perintah `cancelAction`. |
| `device_busy` | Ya | Perangkat sedang menjalankan operasi kritis lain atau gesture playback concurrently. |
| `submit_barrier` | Tidak | Aksi destruktif/finansial ditahan oleh kebijakan barrier dan memerlukan otorisasi eksplisit. |
| `screenshot_failed` | Ya | Pengambilan tangkapan layar bitmap display gagal pada tingkat sistem. |
| `protocol_mismatch` | Tidak | Versi protokol yang diminta tidak didukung oleh runtime APK. |


### 10.6 SnapshotRegistry Lifecycle & Stale Snapshot Invariant

1. **Kapasitas & Retensi (LRU):** Snapshot disimpan dalam cache memori berkapasitas 5 entri dengan TTL 30 detik (`SnapshotRegistry`).
2. **Pendaftaran Otomatis:** Setiap eksekusi `observe` secara otomatis mendaftarkan snapshot baru dan memperbarui `latestSnapshotId`.
3. **Penolakan Snapshot Kadaluarsa:** Perintah mutasi (`actNode`, `actAndVerify`) mewajibkan `snapshot_id`. Bila ID tidak ditemukan atau telah berumur lebih dari 30 detik, perintah ditolak seketika dengan `reason_code: "stale_snapshot"` (`retryable: true`).
4. **Verifikasi Integritas Pohon (`expectedTreeHash`):** Perintah `actNode` memvalidasi `treeHash` sebelum aksi dieksekusi terhadap hash snapshot yang didaftarkan. Bila layar telah bergeser/berubah sebelum klik dieksekusi, perintah ditolak dengan `stale_snapshot`.

### 10.7 Durable Submit Barrier & Idempotency Key

1. **Barrier Operasi Finansial / Destruktif:** Untuk aksi submit formulir atau pos publikasi, request menyertakan `operation_id` (atau `idempotency_key`) dan/atau `is_submit: true`.
2. **Pencegahan Replay Lintas Request ID:** `MutationGuard` mencatat `operation_id` ke dalam tabel in-memory dengan retensi TTL 10 menit (`SUBMIT_TTL_MS = 600000`).
3. **Penolakan Duplikasi:** Jika client mengulang permintaan yang sama menggunakan `request_id` baru tetapi membawa `operation_id` yang sama, perintah kedua ditolak seketika dengan `reason_code: "submit_barrier"` (`retryable: false`).
4. **Single Mutation Lock:** Maksimal hanya satu aksi mutasi aktif diperbolehkan per perangkat. Request mutasi konkuren yang datang bersamaan ditolak dengan `reason_code: "device_busy"` (`retryable: true`).
### 10.8 Handshake Kapabilitas Protokol

APK melaporkan dukungan protokol secara eksplisit kepada server melalui array `protocol_versions`:
- **WebSocket Hello (`register`):** Pesan registrasi menyertakan field `"protocol_versions": [1, 2]`.
- **Command `capabilities`:** Snapshot kapabilitas menyertakan field `"protocol_versions": [1, 2]`.

### 10.9 Rujukan Fixture Netral (JSON Contracts)

Spesifikasi data lengkap, payload request/response, dan skenario pengujian disimpan secara netral di:
- `docs/contracts/v1-fixtures.json` : Fixture lengkap command legacy v1 (termasuk kasus kegagalan dan envelope kontradiktif).
- `docs/contracts/v2-fixtures.json` : Fixture lengkap Protocol Version 2 untuk seluruh command baru dan ke-18 canonical reason codes.
- `docs/contracts/capability-fixture.json` : Snapshot referensi respon `capabilities` aktual dengan `protocol_versions: [1, 2]`.

# Kontrak Command Agent APK <-> Backend (v0.8.0)

> **Untuk siapa dokumen ini:** agent/engineer yang menggarap **backend Go**
> (`internal/agenthub`, `internal/mobile`, `internal/platform/mobileharvest`)
> dan **frontend Svelte** (`/accounts`). APK v0.8.0 sudah dirilis dengan
> kontrak di bawah. **Sisi APK tidak akan diubah lagi untuk menyesuaikan
> backend** — backend/frontend yang menyesuaikan kontrak ini.
>
> Status: APK v0.8.0 (versionCode 15), repo `gososmed-mobile-agent`.
> Transport: WebSocket ke `wss://api.bamsbung.id/v1/agent/ws`.

---

## 1. Ringkasan perubahan yang WAJIB diketahui backend/frontend

| Hal | Sebelum (<= v0.7.1) | Sekarang (v0.8.0) |
|---|---|---|
| Launch app | `LauncherApps`/`startActivity` — sering ditelan Background Activity Launch, foreground tetap `com.miui.home` | Jika Shizuku aktif: `am start` sebagai **uid 2000 (shell)** — bukan BAL, tidak butuh izin overlay |
| Force stop | **Tidak mungkin** (`killBackgroundProcesses` saja) | `am force-stop` nyata saat transport shell |
| Tap | `dispatchGesture` (bisa gagal senyap) | `input tap` (INJECT_EVENTS) + fallback gesture |
| Wake | `WAKE_LOCK` (bisa ditolak OEM) | `input keyevent 224` + fallback wakelock |
| Command baru | — | `shell`, `shizukuRequest` |
| Field hasil baru | — | `transport` pada `startApp`/`killApp`/`shell` |
| Capabilities baru | — | `transport_tier`, `can_shell`, `can_force_stop`, `can_inject_input`, `shizuku_*` |

**Konsekuensi paling penting:** ada **dua tingkat keandalan** yang harus
dibedakan backend, tidak boleh disamakan lagi:

- `transport_tier == "shell_shizuku"` → perintah **deterministik** (setara `adb shell`).
- `transport_tier == "accessibility"` → perintah **best-effort**, tunduk pada
  blokade BAL dan kebijakan OEM (MIUI/HyperOS). `force_stop` **mustahil** di
  tingkat ini.

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

- `ok` di **level luar** = "command dikenali dan dieksekusi", **BUKAN** berarti
  aksinya berhasil.
- `result.ok` = **hasil nyata** aksi di layar HP.
- Backend **WAJIB** membaca `result.ok`. Membaca `ok` luar saja adalah akar
  insiden 2026-09-11 (status akun "aktif" padahal tidak ada yang terkoneksi).
- Setiap kegagalan membawa `result.reason` dengan format `kode: penjelasan`.
  Kode dipakai logika; penjelasan dipakai UI.

---

## 3. Daftar command

| cmd | Argumen | `result` |
|---|---|---|
| `ping` | — | `{pong}` |
| `capabilities` | — | lihat bagian 5 |
| `dump` | — | `{xml, package}` |
| `dumpWindows` | — | `{windows[], activePackage}` |
| `package` | — | `{package}` |
| `startApp` | `package`, `activity?` | `{ok, package, foreground, transport, reason?}` |
| `killApp` | `package` | `{ok, mode, force_stop, transport}` |
| `tap` | `x`, `y` | `{ok}` |
| `tapByText` | `text` | `{ok}` |
| `tapFirstClickable` | — | `{ok, bounds?}` |
| `setText` | `text` | `{ok}` |
| `back` / `home` / `recents` / `notify` | — | `{ok}` |
| `wake` | — | `{ok}` |
| `hasPackage` | `package` | `{installed}` |
| `listPackages` | — | `{packages[]}` |
| `screenshot` | `scale?`, `format?`, `quality?` | `{format, data}` |
| **`shell`** (baru) | `command`, `timeoutMs?` | `{ok, exit_code, stdout, stderr, transport, reason?}` |
| **`shizukuRequest`** (baru) | — | `{ok, shizuku_running, shizuku_permission, reason?}` |

### 3.1 `shell` — batas keamanan

Hanya biner berikut yang diizinkan APK (daftar putih di `ShizukuShell.kt`):

```
am  input  monkey  pm  dumpsys  wm  settings  cmd
```

Perintah lain ditolak dengan `reason = "blocked: perintah 'X' tidak ada di daftar izin agent"`.
Ini sengaja: agent berjalan di HP pribadi pemilik akun, server tidak boleh
menjalankan shell sembarangan. Butuh biner baru = ubah APK, bukan bypass.

`timeoutMs` default 15000, dibatasi 1000–60000.

---

## 4. Daftar kode `reason` (WAJIB ditangani backend)

| Kode | Arti | Tindakan backend | Copy frontend yang disarankan |
|---|---|---|---|
| `not_installed` | App target tidak terpasang | `failed_permanent`, jangan retry | "Aplikasi belum terpasang di HP ini" |
| `bal_blocked` | Launch diblokir sistem; izin overlay belum aktif (tingkat accessibility) | `failed`, minta perbaikan izin | "Aktifkan izin 'Tampilkan di atas aplikasi lain'" (+ MIUI: pop-up latar belakang & Autostart) |
| `launch_not_foreground` | Launch terkirim tapi app tidak muncul dalam 10 s | retry maks 1×, lalu `failed` | "HP lambat membuka aplikasi, coba lagi" |
| `launch_rejected` | Sistem menolak permintaan launch | `failed` | "Sistem HP menolak membuka aplikasi" |
| `shizuku_unavailable` | Layanan Shizuku tidak berjalan | tolak command shell; **jangan** pakai `ForceStopFirst` | "Buka aplikasi Shizuku di HP lalu start service" |
| `shizuku_denied` | Shizuku jalan tapi agent belum diizinkan | kirim `shizukuRequest` | "Setujui izin GoSosmed Agent di Shizuku" |
| `shizuku_api_unavailable` | API `newProcess` tidak tersedia di versi Shizuku ini | turunkan ke tingkat accessibility | "Perbarui aplikasi Shizuku" |
| `shizuku_exec_error` | Perintah shell error tak terduga | log + `failed` | "Perintah perangkat gagal, coba lagi" |
| `blocked` | Biner tidak ada di daftar izin | **bug backend**, bukan masalah user | — (jangan tampilkan ke user) |

---

## 5. `capabilities` — sumber kebenaran untuk preflight (P1-8)

```json
{
  "agent_version": "0.8.0",
  "api_level": 34,
  "manufacturer": "Xiaomi",
  "model": "...",
  "a11y_ready": true,

  "transport_tier": "shell_shizuku",
  "last_launch_transport": "shell_shizuku",
  "shizuku_installed": true,
  "shizuku_running": true,
  "shizuku_permission": true,
  "shizuku_permission_denied_forever": false,
  "shizuku_uid": 2000,
  "shizuku_version": 13,

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

`shizuku_uid`: `2000` = hak adb/shell (normal), `0` = root (Sui), `-1` = tidak diketahui.

### Aturan preflight yang WAJIB diterapkan backend (fail-closed)

Sebelum mengantrikan job harvest/verify:

1. `a11y_ready == false` → tolak, kode `needs_agent`.
2. `can_launch_app == false` → tolak, kode `needs_bg_launch_permission`.
   (artinya: tidak ada Shizuku **dan** izin overlay mati — job pasti gagal.)
3. `can_force_stop == false` **dan** langkah platform memakai `ForceStopFirst`
   → **jangan** kirim `killApp` lalu menganggap layar bersih. Pakai
   `home` + relaunch, atau tandai hasil sebagai tingkat rendah.
4. `screen_interactive == false` → kirim `wake` lebih dulu.
5. `can_screenshot == false` → jangan minta bukti screenshot (API < 30).
6. Tidak ada device online → tolak, kode `no_device`.

TTL cache capabilities: **maks 60 detik**. Shizuku bisa mati kapan pun
(perlu pairing ulang setelah reboot), jadi jangan cache lebih lama.

---

## 6. Yang perlu DIPERBAIKI di backend Go (temuan dari sisi APK)

Semua ini **belum** dikerjakan dari sisi APK karena di luar lingkup Kotlin:

1. **`internal/platform/mobileharvest/harvest.go`** — kelima platform memakai
   `ForceStopFirst: true`, padahal sebelum v0.8.0 force-stop **tidak pernah
   terjadi**. Sekarang: hanya sah bila `capabilities.can_force_stop == true`.
   Bila false, jangan asumsikan layar direset.
2. **Hapus polling foreground ganda.** APK v0.7.1+ sudah menunggu foreground
   di HP (10 s, poll 250 ms) dan hanya membalas `result.ok=true` bila terbukti.
   `EnsureForeground` + `foregroundGrace` di server jadi dobel kerja dan
   menambah puluhan round-trip WS. Cukup percayai `result.ok` + `reason`.
3. **Preflight `capabilities`** (bagian 5) sebelum enqueue — blueprint P1-8.
4. **`internal/agenthub/agentclient.go`**: `Shell` masih hanya menerjemahkan
   `input`/`am`/`pm` ke command khusus. Tambahkan jalur langsung ke `cmd:"shell"`
   agar `dumpsys`/`wm`/`settings` bisa dipakai (mis. `dumpsys window` untuk
   verifikasi foreground yang lebih murah daripada dump XML).
5. **Simpan `transport`** dari hasil `startApp`/`killApp` ke event job. Tanpa
   ini tidak akan pernah bisa dibuktikan apakah kegagalan berasal dari
   tingkat accessibility atau dari selector.
6. **`resultReason`** sudah ada (v0.8.0 backend) — pastikan kode `reason`
   di bagian 4 dipetakan ke `last_error_code`, bukan digabung jadi
   `platform_error` generik.

## 7. Yang perlu DIPERBAIKI di frontend `/accounts`

1. Tampilkan **tingkat transport** per device: `shell_shizuku` = "Mode stabil
   (Shizuku)", `accessibility` = "Mode terbatas" + alasan.
2. Remediasi spesifik per kode `reason` (tabel bagian 4) — bukan pesan
   generik "platform_error".
3. Bila `shizuku_installed == false` → tombol/instruksi pasang Shizuku.
   Bila `shizuku_running == false` → instruksi start service (pairing
   wireless debugging **wajib diulang setiap reboot** di Android 11+).
   Bila `shizuku_permission == false` → tombol "Minta izin" (kirim
   `shizukuRequest`).
4. Jangan pernah menampilkan "Siap Otomasi" tanpa `verified_at` **dan**
   `a11y_ready` **dan** `can_launch_app`.

---

## 8. Matriks uji yang harus lulus sebelum disebut stabil

| No | Skenario | Harapan |
|---|---|---|
| 1 | Shizuku aktif + diizinkan, layar padam, harvest 5 platform | `transport=shell_shizuku`, `force_stop=true`, handle terbaca |
| 2 | Shizuku mati (belum start setelah reboot) | `reason=shizuku_unavailable`, UI minta start Shizuku, **bukan** `platform_error` |
| 3 | Shizuku belum diizinkan | `reason=shizuku_denied`, `shizukuRequest` memunculkan dialog |
| 4 | Tanpa Shizuku, izin overlay aktif | launch tetap jalan lewat accessibility, `transport=accessibility`, `can_force_stop=false` |
| 5 | Tanpa Shizuku, izin overlay mati | preflight menolak dengan `needs_bg_launch_permission` (job tidak dibuat) |
| 6 | App target tidak terpasang | `reason=not_installed`, job `failed_permanent` |
| 7 | `shell` dengan biner di luar daftar putih | `reason=blocked`, tidak dieksekusi |

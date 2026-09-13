# Peta Selaras: Database ↔ Backend API ↔ Command Agent ↔ Frontend

> **Tujuan dokumen ini:** satu tempat untuk melihat bagaimana keempat lapisan
> terhubung, supaya perubahan di satu sisi tidak membuat sisi lain drift.
> Diverifikasi dari `internal/httpx/router.go` dan `docs/api/openapi.yaml`
> pada 2026-09-13.
> Kontrak command: [AGENT-COMMAND-CONTRACT.md](AGENT-COMMAND-CONTRACT.md).

---

## 1. Empat lapisan dan tanggung jawabnya

| Lapisan | Lokasi | Tanggung jawab | DILARANG |
|---|---|---|---|
| Database | `migrations/` | Skema, invarian, isolasi tenant | Aturan bisnis |
| Backend | `internal/**` | Validasi, otorisasi, kuota, orkestrasi job | Proses berat (ffmpeg/adb/browser) sinkron di handler |
| Agent APK | `gososmed-mobile-agent` | Sentuh perangkat nyata (launch, tap, dump, shell) | Kebijakan bisnis |
| Frontend | `web/src/**` | Tampilan, tiga keadaan (memuat/gagal/data) | Panggilan `fetch` telanjang, data dummy |

Aturan lapisan backend (dari `internal/AGENTS.md`): handler hanya memvalidasi
dan mengantrikan job; eksekusi berat di `internal/worker` lewat `jobqueue`.

---

## 2. Alur utama `/accounts`

```
Browser                Backend (cmd/api)              Worker            Agent APK
  |                          |                           |                  |
  |-- POST /v1/accounts/detect-profile ----------------->|                  |
  |                          |-- preflightDevice()       |                  |
  |                          |   (capabilities) -------------------------->|
  |                          |-- CreateSocialAccount --->|                  |
  |                          |-- LinkDevice ------------->|                  |
  |                          |-- CreateJob(harvest) ----->|                  |
  |<-- {account, job} -------|                           |                  |
  |                          |                           |-- claim job ---->|
  |                          |                           |-- startApp ----->|
  |                          |                           |-- dump --------->|
  |                          |                           |<-- xml ----------|
  |                          |                           |-- tap ---------->|
  |                          |                           |-- UpdateAccountHandle
  |                          |                           |-- job_events --->|
  |-- GET /v1/jobs/{id}/events (poll 1,5s) ------------->|                  |
  |<-- events[] -------------|                           |                  |
```

---

## 3. Tabel database yang terlibat

| Tabel | Kolom kunci | Dipakai oleh |
|---|---|---|
| `social_accounts` | `id`, `tenant_id`, `platform`, `handle`, `status`, `device_id`, `verified_at`, `last_error_code`, `last_error` | `/accounts`, preflight, harvest |
| `mobile_devices` | `id`, `serial`, `kind`, `tenant_id`, `status`, `social_account_id`, `pairing_code`, `last_seen` | `/byod`, preflight, capabilities |
| `jobs` | `id`, `tenant_id`, `kind`, `ref_id`, `state`, `attempts`, `max_attempts`, `run_after`, `idempotency_key` | worker, `/jobs` |
| `job_events` | `job_id`, `seq`, `type`, `payload` (append-only, trigger menolak UPDATE/DELETE) | panel log, SSE |

### 3.1 Kind job yang relevan

| `kind` | `ref_id` | Executor |
|---|---|---|
| `account_verify` | `social_accounts.id` | verify session |
| `account_harvest_profile` | `social_accounts.id` | ambil identitas profil |
| `account_login` | `social_accounts.id` | login otomatis (bila adapter ada) |

### 3.2 Relasi DUA ARAH yang harus dijaga

```
social_accounts.device_id        → mobile_devices.id      (arah BARU, migrasi 00047)
mobile_devices.social_account_id → social_accounts.id     (arah WARISAN)
```

**Riwayat insiden:** ketika filter klaim job dan gerbang eksekutor memakai arah
yang **berbeda**, runner mengklaim job BYOD lalu menolaknya, dan me-requeue
setiap 15 detik tanpa menaikkan `attempts` → loop tak berujung
(`requeued_needs_agent`). Kedua sisi sekarang memakai definisi yang sama
(`byodExclusionSQL` mencocokkan **kedua** arah).

**Aturan:** setiap perubahan yang menyentuh kepemilikan akun–device WAJIB
memeriksa kedua arah.

### 3.3 Invarian yang wajib ditegakkan

| Invarian | Status | Bukti |
|---|---|---|
| Satu platform = satu akun `active` per tenant | **PERNAH BOCOR** | Dua akun Instagram `active` bersamaan (2026-09-13) |
| `handle` bukan placeholder/teks layar | **PERNAH BOCOR** | `"Start your first note..."`, `"4 hours ago"` tersimpan sebagai username |
| `job_events.seq` unik per job | Terjaga | `MAX(seq)+1` (catatan: `FOR UPDATE` pada agregat ILEGAL di Postgres) |
| Tenant isolation | Terjaga | Setiap query difilter `tenant_id` di SQL |

---

## 4. Endpoint backend yang relevan (terverifikasi dari router)

### 4.1 Akun
| Metode | Path | Handler | Frontend |
|---|---|---|---|
| GET | `/v1/accounts` | `HandleListAccounts` | `/accounts` |
| POST | `/v1/accounts/detect-profile` | `HandleDetectProfile` | `/accounts` (Hubungkan) |
| POST | `/v1/accounts/mobile` | `HandleConnectMobile` | — |
| POST | `/v1/accounts/{id}/verify` | `HandleVerifyAccount` | `/accounts` (Cek Sesi) |
| DELETE | `/v1/accounts/{id}` | `HandleDisconnect` | `/accounts` (Putus) |
| GET | `/v1/accounts/{id}/logs` | `HandleAccountLogs` | panel log |
| DELETE | `/v1/accounts/{id}/logs` | `HandleClearAccountError` | tombol hapus log |
| GET | `/v1/accounts/{platform}/callback` | `HandleCallback` | OAuth (tidak dipakai BYOD) |

### 4.2 Perangkat
| Metode | Path | Frontend |
|---|---|---|
| GET | `/v1/mobile/devices` | `/accounts`, `/byod` |
| GET | `/v1/mobile/byod` | `/byod` |
| GET | `/v1/mobile/devices/{serial}/screenshot` | layar live |
| GET | `/v1/mobile/devices/{serial}/windows` | diagnostik ops |
| GET | `/v1/mobile/devices/{serial}/packages` | diagnostik |
| POST | `/v1/mobile/devices/{serial}/plan` | `/task-runner` |
| POST | `/v1/mobile/devices/{serial}/flow` | ops/CLI |
| GET/POST | `/v1/mobile/pairings` | `/byod` |
| DELETE | `/v1/mobile/pairings/{id}` | `/byod` |
| DELETE | `/v1/mobile/devices/{serial}` | `/byod` (revoke) |

### 4.3 Job & realtime
| Metode | Path | Frontend |
|---|---|---|
| GET | `/v1/jobs` | `/jobs` |
| GET | `/v1/jobs/{id}` | detail |
| GET | `/v1/jobs/{id}/events` | panel log, `JobWatch` |
| POST | `/v1/jobs/{id}/retry` \| `/cancel` | `/jobs` |
| DELETE | `/v1/jobs/{id}` | `/jobs` |
| POST | `/v1/jobs/clear-terminal` | `/jobs` |
| GET | `/v1/events` | SSE realtime |
| GET | `/v1/agent/ws` | **WebSocket APK** (bukan browser) |

---

## 5. Command agent ↔ aksi backend

| Aksi backend | Command APK | Tier |
|---|---|---|
| Cek perangkat siap | `capabilities` | A |
| Bangunkan layar | `wake` | A→S |
| Reset app ke kondisi awal | `killApp` | A→S |
| Buka aplikasi platform | `startApp` | A→S |
| Baca hierarki layar | `dump` | A |
| Tekan tab profil | `tap` / `tapByText` | A→S |
| Ambil bukti layar | `screenshot` | A |
| Foreground murah | `shell` (`dumpsys window`) | S |
| Pairing otomasi lanjutan | `adbPair` | — |

---

## 6. Kontrak yang mudah drift (periksa setiap kali menyentuh satu sisi)

| Titik drift | Sisi A | Sisi B | Aturan |
|---|---|---|---|
| Nama field capabilities | APK `capabilitiesJson()` | Go `DeviceInfo` struct | Harus sama persis; tambah field = tambah di keduanya |
| Bentuk respons verify | Go `{"job":{"id"}}` | Svelte `res.job?.id` | Pernah salah: frontend membaca `job_id` (tidak ada) |
| `status` akun | Nilai di kolom `status` | `isTerminalJobState`/UI | `active`/`expired`/`revoked`/`error` |
| State job | `internal/job/job.go` | `JobWatch` terminal events | Set terminal harus mencakup semua state akhir |
| Kode error akun | `internal/store/social_account_errors.go` | `web/.../platforms.ts` `errorHint()` | Kode baru wajib punya teks di `id.json` **dan** `en.json` |
| Versi APK | `GOSOSMED_AGENT_LATEST_VERSION` + `_APK_URL` di `.env` | Rilis GitHub | **PERNAH DRIFT:** server `0.7.0`, rilis terbaru `v0.8.0` |

---

## 7. Prosedur memeriksa keselarasan (jalankan sebelum rilis)

```bash
# 1. Kode error akun: pastikan setiap kode punya penanganan frontend
grep -o 'AccountErrCode[A-Za-z]* *= *"[a-z_]*"' internal/store/social_account_errors.go
grep -o "case '[a-z_]*':" "web/src/routes/(cockpit)/accounts/+page.svelte"

# 2. Command agent: bandingkan daftar di APK dengan tabel §3 kontrak
grep -o 'CMD_[A-Z_]* = "[a-zA-Z]*"' ../gososmed-mobile-agent/app/src/main/java/com/gososmed/agent/AgentCommand.kt

# 3. Field capabilities: bandingkan APK dengan struct Go
grep -o 'put("[a-z_]*"' ../gososmed-mobile-agent/app/src/main/java/com/gososmed/agent/AgentAccessibilityService.kt
grep -o 'json:"[a-z_]*' internal/agenthub/agentclient.go

# 4. Versi APK selaras
grep -E 'AGENT_LATEST_VERSION|AGENT_APK_URL' /path/ke/.env
#   harus sama dengan tag rilis terbaru di GitHub Releases

# 5. Tidak ada sisa Shizuku (setelah F2)
grep -ri shizuku ../gososmed-mobile-agent/app/src/ internal/ web/src/ | wc -l   # harus 0
```

---

## 8. Rujukan

- [AGENT-COMMAND-CONTRACT.md](AGENT-COMMAND-CONTRACT.md) — kontrak command lengkap
- [TRANSPORT-ADB-LOKAL.md](TRANSPORT-ADB-LOKAL.md) — transport dan batasnya
- [F0-LIBRARY-VALIDATION.md](F0-LIBRARY-VALIDATION.md) — bukti pemilihan library
- `docs/api/openapi.yaml` (repo Go-sosmed) — spesifikasi API
- `internal/httpx/router.go` (repo Go-sosmed) — registrasi rute sebenarnya

# Panduan Integrasi Arsitektur: ORPV Feedback Layer & Maestro Parity v2
**Repositori:** `gososmed-mobile-agent` (Android APK)  
**Target Pembaca:** Agen Backend / Server (`Go-sosmed`), `mcphub`, `fsm_runner`, `planner`, & SRE  
**Status Kontrak:** Wire Protocol v2 (`protocol_version: 2`), `feedback_contract: "1.0"`  
**Versi APK:** `v1.2.6-dev.1` (versionCode 40 ke atas)  

---

## 📌 Ringkasan Eksekutif (TL;DR untuk Pengembang Backend)

Pada versi sebelumnya (v1.2.5 kebawah), terjadi **kegagalan struktural pada langkah kedua** dalam rangkaian aksi (trajectory) otomatisasi:
1. `actNode` dan `actAndVerify` mengembalikan `after_snapshot_id`, tetapi snapshot tersebut **tidak pernah didaftarkan ke `SnapshotRegistry`**. Ketika backend/agent memanggil aksi berikutnya menggunakan `snapshot_id` tersebut, APK menolaknya dengan `stale_snapshot` (`retryable: true`), memicu *retry storm* dan kegagalan `MaxSameState 3`.
2. Field kontrak yang dijanjikan (`verified`, `action_performed`) **tidak pernah dipancarkan** oleh APK.
3. Listener toast tidak pernah menerima event karena `typeNotificationStateChanged` belum terdaftar pada mask aksesibilitas.
4. Selektor rapuh pada UI dinamis tanpa ID unik karena ketiadaan relasi spasial 2D dan auto-scroll native.

**Versi 1.2.6-dev.1 menyelesaikan seluruh isu di atas secara 1:1 setara Maestro dengan zero breaking change pada wire protocol v2.**

---

## 1. Siklus Hidup Snapshot & Perbaikan Registrasi `after_snapshot_id` (Temuan #0)

### 1.1 Mekanisme Baru (Action Chaining)
* Setiap pemanggilan `actNode` dan `actAndVerify` yang berhasil menyelesaikan transisi layar (`isSettled = true`) **secara otomatis mendaftarkan snapshot `after` ke dalam `SnapshotRegistry`**.
* Backend/mcphub dapat **langsung menggunakan `after_snapshot_id`** pada pemanggilan perintah berikutnya (`resolve`, `actNode`, `actAndVerify`) tanpa perlu melakukan panggilan `observe` redundan.

### 1.2 Peningkatan Kapasitas & Isolasi Pin-on-Failure
* **Kapasitas LRU Inti:** Dinaikkan dari 5 menjadi **8 slot**.
* **Penyimpanan Terpisah `pin-on-failure`:**
  * Snapshot saat terjadi kegagalan (`!transition.isSettled`) disimpan dalam map terpisah berkapasitas maksimal **2 snapshot global**.
  * **Invarian:** Snapshot kegagalan yang di-pin **TIDAK memakan slot kerja LRU**, sehingga antrean kerja aktif tidak ter-evict secara prematur.
  * TTL Pin: 300 detik (independen dari TTL LRU 30 detik).

---

## 2. Kontrak Amplop Respons & Penegakan Kebijakan Retry

### 2.1 Format Respons Aksi Sukses
```json
{
  "protocol_version": 2,
  "request_id": "act-step-1",
  "ok": true,
  "result": {
    "target_node_id": "node-submit",
    "execution_node_id": "node-submit",
    "used_gesture_fallback": false,
    "before_snapshot_id": "snap-before-123",
    "before_state_ref": "snap-before-123",
    "after_snapshot_id": "snap-after-456",
    "screen_fingerprint": "com.example.app:1:1234abcd",
    "verified": true,
    "action_performed": true
  },
  "timing": {
    "started_at_ms": 1729670000000,
    "completed_at_ms": 1729670000450,
    "duration_ms": 450
  }
}
```

### 2.2 Penegakan Aturan `retryable` (Temuan #3 & #4)
| Kondisi Kegagalan | `reason_code` | `retryable` | Tindakan Backend / Planner |
|---|---|:---:|---|
| `snapshot_id` kosong / tidak dikirim | `stale_snapshot` | **`false`** | **DILARANG RETRY.** Client salah mengirim request; retry dengan payload identik akan selalu gagal. |
| Snapshot ID tidak ditemukan / kadaluarsa | `stale_snapshot` | `false` | Lakukan observasi ulang (`observe`) untuk memperoleh snapshot baru. |
| Aksi submit (`is_submit: true`) gagal berubah | `screen_not_changed` | **`false`** | **DILARANG RETRY OTOMATIS.** Mencegah *double post* / posting ganda di media sosial. Eskalasi ke penanganan verifikasi manual / query receipt. |
| Aksi navigasi biasa (`is_submit: false`) | `screen_not_changed` | **`true`** | Boleh dicoba ulang 1-2 kali (mengatasi lag rendering / sentuhan meleset). |

---

## 3. Penyeragaman Kadens & Jaminan Pembatalan (ADR-06)

* **Konstanta Kadens Sistem:**
  * `DEFAULT_POLL_INTERVAL_MS = 100L`
  * `MIN_POLL_INTERVAL_MS = 50L`
  * `MAX_POLL_INTERVAL_MS = 200L`
* **Penjagaan Clamp Sisi APK:**
  Jika backend mengirim nilai `poll_interval_ms` sembarang (misal: `60000L`), APK otomatis meng-clamp ke rentang `[50, 200]` ms. Hal ini menjamin pembatalan (`cancelAction`) tidak pernah terhambat oleh loop tidur (*sleep*) yang terlalu lama.
* **Dual Checkpoint Cancellation:**
  Pembatalan diperiksa pada awal iterasi loop observasi **DAN** tepat sebelum eksekusi `delay()`.

---

## 4. `nodes_digest` Deterministik & Pre-Cap `node_count` (ADR-05)

### 4.1 Spesifikasi Algoritma `nodes_digest`
Untuk mendukung perbandingan kondisi layar lintas mode tanpa terpengaruh perbedaan urutan traversal:
1. Setiap elemen diubah menjadi representasi string kanonik:  
   `"${resourceId ?: "\u0000NULL\u0000"}|${className}|${text ?: "\u0000NULL\u0000"}|${left},${top},${right},${bottom}"`
2. **`node_id` DIBUANG SEPENUHNYA** dari perhitungan digest (karena `node_id` mengandung indeks sibling dan jalur hierarki yang volatil).
3. Daftar string diurutkan secara leksikografis (alfabetis), digabung dengan pemisah newline `\n`.
4. Dihitung nilai hash SHA-256 (`sha256:...`).
5. **Karakteristik:**
   * Stabil terhadap pengacakan urutan traversal.
   * Sensitif terhadap perubahan teks maupun koordinat piksel `bounds`.
   * Membedakan teks `null` dan teks kosong `""`.

### 4.2 Properti Tambahan pada `SnapshotResult`
```json
{
  "snapshot_id": "uuid-...",
  "tree_hash": "sha256:...",
  "nodes_digest": "sha256:8f4b...",
  "node_count": 2450,
  "truncated": true
}
```
* `node_count`: Jumlah node riil yang terdeteksi di memori **sebelum** batas `MAX_NODES = 2000` diterapkan.
* `truncated`: Bernilai `true` jika `node_count > 2000`.

---

## 5. Pelacak Notifikasi Toast (`ToastTracker` & ADR-01)

### 5.1 Karakteristik Teknis
* APK mendengarkan event Android `TYPE_NOTIFICATION_STATE_CHANGED`.
* **Filter Wajib:** Hanya menerima jika `className` merupakan komponen Toast (`android.widget.Toast`) dan `packageName` **tidak boleh kosong** serta bukan package milik agent sendiri.
* **Sliding Window:** Disimpan dalam memori dengan TTL **3500 ms**.
* **Integrasi dengan `waitForNode`:**
  Jika pencarian elemen di hierarki window tidak menemukan hasil, `waitForNode` memeriksa buffer toast aktif yang sesuai dengan `package_name` target.
  Jika cocok, elemen dikembalikan sebagai node virtual dengan atribut:
  ```json
  {
    "found": true,
    "node": {
      "node_id": "toast-1729670000000",
      "class_name": "android.widget.Toast",
      "text": "Video telah dibagikan",
      "ancestor_path": "toast"
    },
    "non_authoritative": true
  }
  ```
* **Aturan Pengambilan Keputusan:** Flag `non_authoritative = true` menandakan bahwa kemunculan toast adalah sinyal lemah. Ia **tidak boleh** digunakan sebagai bukti akhir penutupan trajectory tanpa verifikasi `submit_receipt`.

---

## 6. Primitif Maestro Parity

### 6.1 Spatial Filtering 2D (`below`, `above`, `leftOf`, `rightOf`)
Memungkinkan penargetan elemen berdasarkan relasi posisi terhadap teks atau ID patokan.

#### Format Request JSON:
```json
{
  "protocol_version": 2,
  "request_id": "req-resolve-spatial",
  "cmd": "resolve",
  "args": {
    "text": "Follow",
    "right_of": "Bams Bung"
  }
}
```
* **Parameter Tersedia:** `below`, `above`, `left_of`, `right_of` (menerima teks atau resource_id elemen patokan).
* **Heuristik:** Menghitung jarak Euclidean terdekat. Elemen yang melanggar orientasi geometris (misal berada di sebelah kiri padahal diminta `right_of`) otomatis didiskualifikasi (skor 0).

### 6.2 Native `scrollUntilVisible`
Menghilangkan keharusan backend/LLM memanggil perulangan perintah `swipe` berkali-kali.

#### Format Request JSON:
```json
{
  "protocol_version": 2,
  "request_id": "req-scroll-1",
  "cmd": "scrollUntilVisible",
  "args": {
    "selector": {
      "text": "Syarat & Ketentuan"
    },
    "direction": "down",
    "max_scrolls": 8
  }
}
```
* **Jaminan Keamanan:** APK melakukan pengguliran in-memory (jeda 300ms per siklus) hingga elemen target berada di **area aman viewport** (15% hingga 85% dari tinggi layar) agar elemen tidak terpotong header atau bottom navigation bar.

### 6.3 Stale Accessibility Cache Refresh
APK menyediakan mekanisme pembersihan cache internal OS `AccessibilityInteractionClient` (`refreshAccessibilityCache()`) yang secara otomatis menyegarkan status tree ketika transisi antar-layar yang kompleks terjadi di aplikasi seperti TikTok dan Instagram.

---

## 7. Runbook Integrasi untuk Konsumen Backend (`mcphub` & `fsm_runner`)

1. **Gunakan `after_snapshot_id` Secara Berantai:**
   * Langkah 1: `actNode` $\rightarrow$ simpan `res.result.after_snapshot_id`.
   * Langkah 2: Panggil aksi berikutnya dengan `"snapshot_id": res.result.after_snapshot_id`. Jangan memanggil `observe` baru jika tidak ada jeda waktu yang lama.
2. **Periksa Field `verified`:**
   * Jangan hanya memeriksa `ok: true`. Periksa juga `result.verified === true` dan `result.action_performed === true`.
3. **Patuhi Flag `retryable`:**
   * Jika respons menghasilkan `ok: false` dengan `retryable: false`, segera batalkan trajectory atau alihkan ke eskalasi manual. Jangan melakukan retry loop buta.
4. **Manfaatkan Spatial Filtering:**
   * Untuk tombol media sosial yang tidak memiliki ID unik (seperti tombol "Like", "Follow", "Share"), sertakan parameter `right_of` atau `below` dengan teks profil terdekat sebagai patokan.

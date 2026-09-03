# Cara Kerja & Alur Pairing (agent ↔ server)

> Dokumen alur publik. Bagian internal server (skema DB, handler, keputusan
> keamanan detail) tidak dibahas di sini — repo server privat.

## Alur koneksi (ringkas)

```
HP (agent APK)                          Server (GoSosmed agenthub)
────────────────                        ──────────────────────────
1. User menyalakan service              (foreground service + notifikasi)
2. User memasukkan:                        │
   - URL WS server                         │
   - Kode pairing (8 karakter)             │
3. Koneksi WS KELUAR (outbound) ────────►  upgrade WebSocket
4. Hello `register`                        │
   {id, device_id, pairing_code} ────────► validasi kode (single-use /
   ◄─────────────────────────────────────  reconnect dengan kode tersimpan)
   `register_ack` {ok, error?}
5a. ok:true  → device terdaftar & online di dasbor (terikat ke pemilik kode)
5b. ok:false → agent BERHENTI mencoba ulang; user memasukkan kode baru
6. Heartbeat ping tiap ~15s  ───────────►  server balas pong
   (jeda baca 45s di server mematikan koneksi zombie)
7. Command server→HP: dump / tap / text /
   back / home / startApp / screenshot ──►  dieksekusi via AccessibilityService,
   ◄─────────────────────────────────────  hasil dikirim balas ke server
```

## Aturan penting bagi pemilik HP

- **Kode pairing diterbitkan dasbor**, bukan dibuat di HP. Satu kode hanya
  untuk **satu pemasangan**, berlaku 15 menit.
- Setelah HP terpasang, `device_id` + kode tersimpan di HP untuk
  **reconnect otomatis** — tanpa memasang ulang tiap kali.
- HP bisa dicabut kapan saja dari dasbor ("Cabut device"): koneksi langsung
  diputus dan kode lama tidak bisa dipakai lagi.
- Kode yang salah/kedaluwarsa **tidak** di-retry terus-menerus oleh agent —
  aplikasi berhenti dan menunggu kode baru. Ini mencegah pencurian kode lewat
  brute force (server juga membatasi percobaan gagal per IP).

## Prinsip desain (mengapa aman untuk BYOD)

1. **Koneksi selalu keluar dari HP** (outbound WSS) — tidak ada port masuk,
   tidak perlu IP publik, tidak perlu root.
2. **Server yang menerbitkan identitas** (kode pairing) — bukan HP yang
   meng-claim sendiri. Kepemilikan device terbukti oleh kode dari dasbor.
3. **Perintah tertutup** — daftar command fix, tanpa remote-control bebas.
   Semua bisa diaudit di kode `AgentCommand.kt`.
4. **Data minimal** — identitas device + heartbeat + hierarki layar aplikasi
   target saat job berjalan + hasil eksekusi. Tidak ada kredensial akun
   sosmed yang dikirim ke server.

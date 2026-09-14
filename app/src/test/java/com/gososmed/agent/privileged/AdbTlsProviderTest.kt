package com.gososmed.agent.privileged

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import javax.net.ssl.SSLSocket

/**
 * v0.9.8 — PENJAGA REGRESI UNTUK SYARAT TERSEMBUNYI `libadb-android`.
 *
 * ================== MENGAPA TEST INI ADA ==================
 *
 * Fitur pairing ADB pernah GAGAL TOTAL di HP pengguna dengan:
 *
 *   adb_pair_failed: java.lang.NoSuchMethodException:
 *     com.android.org.conscrypt.Conscrypt.exportKeyingMaterial
 *     [class javax.net.ssl.SSLSocket, class java.lang.String, class [B, int
 *
 * Sebabnya bukan kode kita, melainkan SYARAT yang tidak terlihat dari dependency
 * graph. `libadb-android` sengaja TIDAK mendeklarasikan Conscrypt, karena ia
 * menyerahkan pilihan kepada aplikasi (lihat README-nya, bagian "Adding
 * Dependencies"):
 *
 *   if (SslUtils.isCustomConscrypt()) conscryptClass = Class.forName("org.conscrypt.Conscrypt");
 *   else                              conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt");
 *
 * Tanpa Conscrypt sendiri, `SslUtils` jatuh ke Conscrypt PLATFORM, dan
 * `com.android.org.conscrypt.Conscrypt.exportKeyingMaterial` adalah API
 * TERSEMBUNYI (`@UnsupportedAppUsage`) yang tidak dapat direfleksikan oleh
 * aplikasi `targetSdk 34`. Pairing mati SEBELUM mengirim kode apa pun — dan
 * pesan galatnya waktu itu menyesatkan pengguna menjadi "kode salah".
 *
 * Karena syarat itu tidak muncul di `gradle dependencies`, build tetap HIJAU
 * walau Conscrypt dihapus. Satu-satunya cara mencegahnya terulang adalah
 * menguji INVARIAN-nya secara langsung — itulah yang dilakukan berkas ini.
 *
 * ================== CATATAN TEKNIS ==================
 *
 * Test berjalan di JVM (bukan perangkat), jadi `initialize = false` dipakai
 * pada `Class.forName`: kita hanya membuktikan KELAS dan TANDA TANGAN METODE-nya
 * ADA di runtime classpath, TANPA menjalankan static initializer yang akan
 * mencoba memuat pustaka native (yang tidak tersedia di desktop). Justru itulah
 * inti regresinya: yang dulu gagal adalah "kelasnya tidak ada sama sekali".
 */
class AdbTlsProviderTest {

    /**
     * SslUtils.getSslContext() memuat kelas ini untuk memutuskan apakah ia boleh
     * memakai Conscrypt sendiri. Bila `Class.forName` di sini gagal, berarti
     * dependensi `org.conscrypt:conscrypt-android` HILANG dan pairing akan
     * kembali memakai Conscrypt platform yang tersembunyi.
     */
    @Test
    fun `conscrypt sendiri tersedia untuk SslUtils`() {
        val provider = Class.forName(
            "org.conscrypt.OpenSSLProvider",
            false,
            javaClass.classLoader
        )
        assertNotNull(
            "org.conscrypt.OpenSSLProvider tidak ada di runtime classpath. " +
                "Dependensi 'org.conscrypt:conscrypt-android' HILANG — pairing ADB " +
                "akan gagal di perangkat dengan NoSuchMethodException " +
                "com.android.org.conscrypt.Conscrypt.exportKeyingMaterial. " +
                "Lihat README libadb-android bagian \"Adding Dependencies\".",
            provider
        )
    }

    /**
     * Meniru PERSIS panggilan yang dilakukan PairingConnectionCtx saat
     * customConscrypt aktif:
     *
     *   Class.forName("org.conscrypt.Conscrypt")
     *       .getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class)
     *
     * Bila tanda tangan ini berubah atau hilang, pairing akan gagal walau
     * Conscrypt-nya ada — jadi keduanya harus dijaga.
     */
    @Test
    fun `Conscrypt punya exportKeyingMaterial dengan tanda tangan yang dipakai libadb`() {
        val cls = Class.forName("org.conscrypt.Conscrypt", false, javaClass.classLoader)
        val method = cls.getMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java,
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType
        )
        assertNotNull(method)
        assertTrue(
            "exportKeyingMaterial harus static — libadb memanggilnya dengan " +
                "invoke(null, ...).",
            Modifier.isStatic(method.modifiers)
        )
    }
}

package com.gososmed.agent.capture

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Hardware-backed self-signed TLS certificate generator via AndroidKeyStore (vitRemote 1:1 proven pattern).
 * Unlocks WebCodecs 60 FPS VideoDecoder on local Wi-Fi networks by providing a Secure Context (HTTPS/WSS).
 * Zero external libraries or heavy dependencies required.
 */
object Tls {
    private const val ALIAS = "gososmed-tls"

    val context: SSLContext? by lazy {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val now = System.currentTimeMillis()
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512
                    )
                    .setCertificateSubject(X500Principal("CN=GoSosmedRemote"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 86_400_000L))
                    .setCertificateNotAfter(Date(now + 3650L * 86_400_000L))
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(spec)
                    generateKeyPair()
                }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, null)
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        } catch (_: Throwable) {
            null
        }
    }
}

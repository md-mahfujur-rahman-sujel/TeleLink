package com.telelink.tv.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** A stable, self-signed server identity kept in Android Keystore. */
class TlsIdentity {
    @Synchronized
    fun serverContext(): SSLContext {
        val keyStore = loadOrCreateKeyStore()
        val keyManagers = KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, null) }

        return SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, null, SecureRandom())
        }
    }

    @Synchronized
    fun certificateFingerprint(): String {
        val certificate = loadOrCreateKeyStore().getCertificate(KEY_ALIAS)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
        return digest.joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun loadOrCreateKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, CERTIFICATE_VALIDITY_YEARS) }
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
            )
                .setCertificateSubject(X500Principal("CN=TeleLink TV"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(start.time)
                .setCertificateNotAfter(end.time)
                .setKeySize(2048)
                // Modern TLS (1.3) signs with RSA-PSS. Conscrypt performs that through the keystore
                // using raw (no-padding) RSA, so the key must allow PSS and ENCRYPTION_PADDING_NONE
                // as well as the older PKCS1 modes, otherwise the handshake with the phone can fail.
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512
                )
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    KeyProperties.SIGNATURE_PADDING_RSA_PSS
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE,
                    KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1
                )
                .build()

            KeyPairGenerator.getInstance("RSA", ANDROID_KEY_STORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
        return keyStore
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        // v2: a new alias so the key is regenerated with the broader capabilities above.
        // The TV certificate fingerprint changes once; phones simply pair again.
        const val KEY_ALIAS = "telelink_tv_tls_identity_v2"
        const val CERTIFICATE_VALIDITY_YEARS = 10
    }
}

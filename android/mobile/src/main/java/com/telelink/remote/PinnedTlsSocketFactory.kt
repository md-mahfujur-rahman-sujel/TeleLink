package com.telelink.remote

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

object PinnedTlsSocketFactory {
    // The QR code is the trust anchor: accept only its exact leaf certificate fingerprint.
    @SuppressLint("CustomX509TrustManager")
    fun create(expectedFingerprint: String): SSLSocketFactory {
        val trustManager = createPinnedTrustManager(expectedFingerprint)
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }.socketFactory
    }
}

@SuppressLint("CustomX509TrustManager")
internal fun createPinnedTrustManager(expectedFingerprint: String): X509TrustManager {
    require(expectedFingerprint.matches(Regex("[A-Fa-f0-9]{64}"))) {
        "Expected certificate fingerprint must contain 64 hexadecimal characters"
    }
    val expected = expectedFingerprint.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw CertificateException("TV did not provide a certificate")
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
            if (!MessageDigest.isEqual(expected, actual)) {
                throw CertificateException("TV certificate does not match the scanned pairing code")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

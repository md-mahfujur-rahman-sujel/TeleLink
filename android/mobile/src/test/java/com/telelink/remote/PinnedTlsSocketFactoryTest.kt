package com.telelink.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class PinnedTlsSocketFactoryTest {
    @Test
    fun trustsOnlyTheCertificateFingerprintProvidedByTheQrCode() {
        val (certificate, fingerprint) = createTestCertificate()
        val chain = arrayOf(certificate)

        createPinnedTrustManager(fingerprint).checkServerTrusted(chain, "RSA")

        val wrongFingerprint = fingerprint.toCharArray().also { chars ->
            chars[0] = if (chars[0] == '0') '1' else '0'
        }.concatToString()
        val rejected = runCatching {
            createPinnedTrustManager(wrongFingerprint).checkServerTrusted(chain, "RSA")
        }.exceptionOrNull()
        assertTrue(rejected is CertificateException)
        assertEquals(
            "TV certificate does not match the scanned pairing code",
            rejected?.message
        )
    }

    @Test
    fun rejectsMalformedFingerprintBeforeOpeningAConnection() {
        val rejected = runCatching { createPinnedTrustManager("not-a-fingerprint") }
            .exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        assertFalse(rejected?.message.isNullOrBlank())
    }

    private fun createTestCertificate(): Pair<X509Certificate, String> {
        val directory = Files.createTempDirectory("telelink-pin-test-").toFile()
        try {
            val keystoreFile = File(directory, "certificate.p12")
            val javaHome = File(requireNotNull(System.getProperty("java.home")))
            val keytoolName = if (System.getProperty("os.name")?.startsWith("Windows", true) == true) {
                "keytool.exe"
            } else {
                "keytool"
            }
            val process = ProcessBuilder(
                File(javaHome, "bin/$keytoolName").absolutePath,
                "-genkeypair", "-alias", "telelink-pin-test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keystoreFile.absolutePath,
                "-storepass", "test-password", "-keypass", "test-password",
                "-dname", "CN=TeleLink test TV", "-noprompt"
            ).redirectErrorStream(true).start()
            assertTrue("keytool did not finish", process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("keytool failed: $output", 0, process.exitValue())

            val store = KeyStore.getInstance("PKCS12").apply {
                FileInputStream(keystoreFile).use { load(it, "test-password".toCharArray()) }
            }
            val certificate = store.getCertificate("telelink-pin-test") as X509Certificate
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { byte -> "%02X".format(byte) }
            return certificate to fingerprint
        } finally {
            directory.deleteRecursively()
        }
    }
}

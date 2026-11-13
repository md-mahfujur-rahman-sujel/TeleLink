package com.telelink.tv.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairedDevice(
    val id: String,
    val name: String,
    val pairedAt: Long
)

data class PairingCredential(
    val device: PairedDevice,
    val token: String
)

class PairedDeviceStorageException(cause: Exception) : IllegalStateException(
    "Paired-device data could not be decrypted. Clear TeleLink TV app data to pair phones again.",
    cause
)

/** Stores only token hashes, with the device registry encrypted by an Android Keystore key. */
class PairedDeviceStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun create(name: String): PairingCredential = synchronized(lock) {
        val device = PairedDevice(
            id = UUID.randomUUID().toString(),
            name = name.filterNot(Char::isISOControl)
                .trim()
                .take(MAX_NAME_LENGTH)
                .ifBlank { "Android phone" },
            pairedAt = System.currentTimeMillis()
        )
        val tokenBytes = ByteArray(TOKEN_SIZE_BYTES).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val devices = readArray()
        devices.put(
            JSONObject()
                .put("id", device.id)
                .put("name", device.name)
                .put("pairedAt", device.pairedAt)
                .put("tokenHash", sha256(token))
        )
        writeArray(devices)
        PairingCredential(device, token)
    }

    fun list(): List<PairedDevice> = synchronized(lock) {
        val array = readArray()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { row ->
                PairedDevice(
                    id = row.optString("id"),
                    name = row.optString("name", "Android phone"),
                    pairedAt = row.optLong("pairedAt")
                )
            }
        }
    }

    fun authenticate(deviceId: String, token: String): Boolean = synchronized(lock) {
        val expectedHash = find(deviceId)?.optString("tokenHash") ?: return false
        MessageDigest.isEqual(
            expectedHash.toByteArray(Charsets.US_ASCII),
            sha256(token).toByteArray(Charsets.US_ASCII)
        )
    }

    fun remove(deviceId: String): Boolean = synchronized(lock) {
        val source = readArray()
        val filtered = JSONArray()
        var removed = false
        for (index in 0 until source.length()) {
            val row = source.optJSONObject(index) ?: continue
            if (row.optString("id") == deviceId) {
                removed = true
            } else {
                filtered.put(row)
            }
        }
        if (removed) writeArray(filtered)
        removed
    }

    private fun find(deviceId: String): JSONObject? {
        val array = readArray()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            if (row.optString("id") == deviceId) return row
        }
        return null
    }

    private fun readArray(): JSONArray {
        val encrypted = preferences.getString(DEVICE_DATA_KEY, null) ?: return JSONArray()
        return try {
            val packed = Base64.decode(encrypted, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            JSONArray(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (exception: Exception) {
            throw PairedDeviceStorageException(exception)
        }
    }

    private fun writeArray(array: JSONArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        preferences.edit {
            putString(DEVICE_DATA_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "telelink_paired_devices_key"
        const val PREFERENCES_NAME = "telelink_paired_devices"
        const val DEVICE_DATA_KEY = "encrypted_registry"
        const val TOKEN_SIZE_BYTES = 32
        const val MAX_NAME_LENGTH = 64
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

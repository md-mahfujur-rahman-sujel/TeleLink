package com.telelink.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedTvCredential(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
    val token: String,
    val deviceName: String
)

class SecureCredentialStoreException(cause: Exception) : IllegalStateException(
    "Saved TV credentials could not be decrypted. Clear TeleLink Remote app data and pair again.",
    cause
)

class SecureCredentialStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(credential: SavedTvCredential) {
        val values = readArray()
        val filtered = JSONArray()
        for (index in 0 until values.length()) {
            val row = values.optJSONObject(index) ?: continue
            if (row.optString("fingerprint") != credential.fingerprint) filtered.put(row)
        }
        filtered.put(
            JSONObject()
                .put("host", credential.host)
                .put("port", credential.port)
                .put("fingerprint", credential.fingerprint)
                .put("deviceId", credential.deviceId)
                .put("token", credential.token)
                .put("deviceName", credential.deviceName)
        )
        writeArray(filtered)
    }

    @Synchronized
    fun latest(): SavedTvCredential? = readArray().let { values ->
        if (values.length() == 0) null else parse(values.optJSONObject(values.length() - 1))
    }

    @Synchronized
    fun list(): List<SavedTvCredential> = readArray().let { values ->
        (0 until values.length()).mapNotNull { index -> parse(values.optJSONObject(index)) }
    }

    @Synchronized
    fun find(fingerprint: String): SavedTvCredential? {
        val values = readArray()
        for (index in 0 until values.length()) {
            val credential = parse(values.optJSONObject(index)) ?: continue
            if (credential.fingerprint == fingerprint) return credential
        }
        return null
    }

    @Synchronized
    fun clear() {
        preferences.edit { remove(STORE_KEY) }
    }

    @Synchronized
    fun remove(fingerprint: String) {
        val values = readArray()
        val filtered = JSONArray()
        for (index in 0 until values.length()) {
            val row = values.optJSONObject(index) ?: continue
            if (row.optString("fingerprint") != fingerprint) filtered.put(row)
        }
        if (filtered.length() == 0) clear() else writeArray(filtered)
    }

    private fun parse(row: JSONObject?): SavedTvCredential? = row?.let {
        SavedTvCredential(
            host = it.optString("host"),
            port = it.optInt("port"),
            fingerprint = it.optString("fingerprint"),
            deviceId = it.optString("deviceId"),
            token = it.optString("token"),
            deviceName = it.optString("deviceName", "TeleLink TV")
        )
    }

    private fun readArray(): JSONArray {
        val encoded = preferences.getString(STORE_KEY, null) ?: return JSONArray()
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            JSONArray(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (exception: Exception) {
            throw SecureCredentialStoreException(exception)
        }
    }

    private fun writeArray(values: JSONArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(values.toString().toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString(STORE_KEY, Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP))
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

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "telelink_remote_credentials_key"
        const val PREFERENCES_NAME = "telelink_saved_tvs"
        const val STORE_KEY = "encrypted_credentials"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
